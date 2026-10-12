package io.toolgate.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.toolgate.api.Decision;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.spi.AuditEvent;
import io.toolgate.springai.MockModelServer.Call;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The adapter in a Spring Boot application, against a scripted model: what reaches the tools. */
@SpringBootTest(classes = TestApplication.class)
class SpringAiAdapterTest {

    private static final MockModelServer MODEL = start();
    private static final Principal MARIA = Principal.user("maria", Tenant.of("acme"));

    @Autowired
    private ChatClient.Builder chatClients;
    @Autowired
    private ChatModel chatModel;
    @Autowired
    private ToolGateChatClients toolGateChatClients;

    private String session;

    @DynamicPropertySource
    static void model(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", MODEL::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "test");
        registry.add("spring.ai.openai.chat.options.model", () -> "gpt-4o");
    }

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @BeforeEach
    void reset() {
        MODEL.reset();
        TestTools.RAN.clear();
        session = "s-" + UUID.randomUUID();
    }

    @Test
    void anAllowedToolRunsAndTheDecisionIsAudited() {
        MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}")).thenText("It is sunny.");

        assertThat(ask("support-chat")).isEqualTo("It is sunny.");

        assertThat(TestTools.RAN).containsExactly("getWeather");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(decisions())
                .singleElement().satisfies(decided -> {
                    assertThat(decided.tool().toString()).isEqualTo("local/getWeather");
                    assertThat(decided.principal()).isEqualTo(MARIA);
                    assertThat(decided.applied()).isInstanceOf(Decision.Allow.class);
                }));
    }

    @Test
    void aToolOffTheAllowListIsDeniedAndTheModelIsToldOnlyThat() {
        MODEL.thenToolCalls(new Call("call_1", "deleteAllOrders", "{}")).thenText("I cannot do that.");

        ask("support-chat");

        assertThat(TestTools.RAN).isEmpty();
        assertThat(MODEL.requests().getLast()).contains(Decision.Deny.MODEL_MESSAGE).doesNotContain(BuiltInRules.ALLOW_LIST);
    }

    @Test
    void argumentRulesSeeTheArgumentsTheToolWouldGet() {
        MODEL.thenToolCalls(new Call("call_1", "fetchPage", "{\"url\":\"https://evil.example/\"}")).thenText("No.");

        ask("support-chat");

        assertThat(TestTools.RAN).isEmpty();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(decisions()).singleElement()
                .satisfies(decided -> assertThat(decided.applied().ruleId()).isEqualTo("fetchPage.url")));
    }

    /** The catalog is filled from the callback's own schema, so an extra argument is caught. */
    @Test
    void anArgumentTheToolDoesNotDeclareIsDenied() {
        MODEL.thenToolCalls(new Call("call_1", "fetchPage", "{\"url\":\"https://docs.example/\",\"proxy\":\"evil\"}"))
                .thenText("No.");

        ask("support-chat");

        assertThat(TestTools.RAN).isEmpty();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(decisions()).singleElement()
                .satisfies(decided -> assertThat(decided.applied().ruleId()).isEqualTo(BuiltInRules.ARGUMENTS_DECLARED)));
    }

    /** Ambiguous JSON, which a lenient parser reads one way and ToolGate another, never runs. */
    @Test
    void duplicateArgumentKeysAreRefused() {
        MODEL.thenToolCalls(new Call("call_1", "fetchPage",
                "{\"url\":\"https://docs.example/\",\"url\":\"https://evil.example/\"}")).thenText("No.");

        ask("support-chat");

        assertThat(TestTools.RAN).isEmpty();
        assertThat(MODEL.requests().getLast()).contains(Decision.Deny.MODEL_MESSAGE);
    }

    /** Case A in Spring AI: after private data and untrusted content, the send becomes a draft. */
    @Test
    void aTaintedSendRunsTheSafeToolInstead() {
        MODEL.thenToolCalls(new Call("call_1", "readInvoice", "{\"id\":\"INV-118\"}"))
                .thenToolCalls(new Call("call_2", "fetchPage", "{\"url\":\"https://docs.example/terms\"}"))
                .thenToolCalls(new Call("call_3", "sendEmail", "{\"to\":\"billing@mycompany.com\",\"body\":\"invoice\"}"))
                .thenText("Drafted.");

        ask("support-chat");

        assertThat(TestTools.RAN).containsExactly("readInvoice", "fetchPage", "draftEmail");
        assertThat(MODEL.requests().getLast()).contains("draft saved for billing@mycompany.com");
    }

    /** A downgrade hands the safe tool the model's own arguments, numbers exactly as written. */
    @Test
    void aDowngradeKeepsNumbersAsTheModelWroteThem() {
        MODEL.thenToolCalls(new Call("call_1", "readTicket", "{\"id\":\"48213\"}"))
                .thenToolCalls(new Call("call_2", "issueRefund", "{\"order\":\"7731\",\"amount\":480}"))
                .thenText("Queued for review.");

        ask("refunds");

        assertThat(TestTools.RAN).containsExactly("readTicket", "createPendingRefund 480");
    }

    @Test
    void terminateEndsTheRun() {
        MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}"),
                new Call("call_2", "getWeather", "{\"city\":\"Oslo\"}")).thenText("never asked for");

        assertThatThrownBy(() -> ask("one-call"))
                .as("thrown to the caller as it is, not wrapped")
                .isInstanceOf(ToolGateViolationException.class)
                .hasMessageContaining(BuiltInRules.TOOL_CALL_BUDGET);

        assertThat(TestTools.RAN).containsExactly("getWeather");
        assertThat(MODEL.requests()).hasSize(1);
    }

    @Test
    void aCallWithoutToolGatesContextEndsTheRun() {
        MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}")).thenText("It is sunny.");

        assertThatThrownBy(() -> chatClients.build().prompt().user("Weather?").tools(new TestTools()).call().content())
                .isInstanceOf(ToolGateViolationException.class)
                .hasMessageContaining("adapter.context");
        assertThat(TestTools.RAN).isEmpty();
    }

    /** The spike's second gap: the request's callbacks are gated before an application's tool advisor runs. */
    @Test
    void anApplicationsOwnToolAdvisorStillRunsGatedTools() {
        MODEL.thenToolCalls(new Call("call_1", "deleteAllOrders", "{}")).thenText("I cannot do that.");

        chatClients.build().prompt().user("Clean up").advisors(ToolCallingAdvisor.builder().build())
                .tools(new TestTools()).toolContext(ToolGateContext.of("support-chat", MARIA, session)).call().content();

        assertThat(TestTools.RAN).isEmpty();
        assertCheckedOnce();
    }

    /** Even one ordered first of all: ToolGate's advisor runs at the highest precedence. */
    @Test
    void aToolAdvisorOrderedFirstStillRunsGatedTools() {
        MODEL.thenToolCalls(new Call("call_1", "deleteAllOrders", "{}")).thenText("I cannot do that.");

        chatClients.build().prompt().user("Clean up")
                .advisors(ToolCallingAdvisor.builder().advisorOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 1).build())
                .tools(new TestTools()).toolContext(ToolGateContext.of("support-chat", MARIA, session)).call().content();

        assertThat(TestTools.RAN).isEmpty();
        assertCheckedOnce();
    }

    @Test
    void aStreamingRunIsGated() {
        MODEL.thenToolCalls(new Call("call_1", "deleteAllOrders", "{}")).thenText("I cannot do that.");

        var chunks = chatClients.build().prompt().user("Clean up").tools(new TestTools())
                .toolContext(ToolGateContext.of("support-chat", MARIA, session))
                .stream().content().collectList().block(Duration.ofSeconds(20));

        assertThat(String.join("", chunks)).isEqualTo("I cannot do that.");
        assertThat(TestTools.RAN).isEmpty();
    }

    /**
     * ChatClient.create(model) would run tools without ToolGate, so the model refuses its request before
     * offering any tool ({@link HandBuiltClientTest}); ToolGateChatClients builds a gated one.
     */
    @Test
    void aClientBuiltByHandIsRefusedAndToolGateChatClientsIsGated() {
        MODEL.thenToolCalls(new Call("call_1", "deleteAllOrders", "{}")).thenText("Done.");
        assertThatThrownBy(() -> ChatClient.create(chatModel).prompt().user("Clean up").tools(new TestTools())
                .toolContext(ToolGateContext.of("support-chat", MARIA, session)).call().content())
                .isInstanceOf(ToolGateViolationException.class);
        assertThat(TestTools.RAN).isEmpty();
        assertThat(MODEL.requests()).isEmpty();

        reset();
        MODEL.thenToolCalls(new Call("call_1", "deleteAllOrders", "{}")).thenText("I cannot do that.");
        toolGateChatClients.builder(chatModel).build().prompt().user("Clean up").tools(new TestTools())
                .toolContext(ToolGateContext.of("support-chat", MARIA, session)).call().content();
        assertThat(TestTools.RAN).isEmpty();
        assertCheckedOnce();
    }

    /** Callbacks are wrapped by the advisor and again by the manager; a call must still be checked once. */
    private void assertCheckedOnce() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(decisions()).hasSize(1));
    }

    private String ask(String agent) {
        return chatClients.build().prompt().user("Go").tools(new TestTools())
                .toolContext(ToolGateContext.of(agent, MARIA, session)).call().content();
    }

    private java.util.List<AuditEvent.Decided> decisions() {
        return TestApplication.AUDIT.stream()
                .filter(AuditEvent.Decided.class::isInstance).map(AuditEvent.Decided.class::cast)
                .filter(decided -> decided.sessionId().equals(session))
                .toList();
    }

    private static MockModelServer start() {
        try {
            return new MockModelServer();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
