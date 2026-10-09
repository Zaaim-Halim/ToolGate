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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** How applications really use Spring AI: concurrent requests, memory across turns, failing tools, several agents. */
@SpringBootTest(classes = TestApplication.class)
class RealWorldPathsTest {

    private static final MockModelServer MODEL = start();
    private static final Principal MARIA = Principal.user("maria", Tenant.of("acme"));

    @Autowired
    private ChatClient.Builder chatClients;

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
        session = "rw-" + UUID.randomUUID();
    }

    /** Requests of one session racing each other can never overshoot its budget together. */
    @Test
    void concurrentRequestsInOneSessionShareItsBudget() throws Exception {
        MODEL.alwaysToolCallsThenText("Sunny.", new Call("call_1", "getWeather", "{\"city\":\"Paris\"}"));
        var requests = new ArrayList<Callable<String>>();
        for (int index = 0; index < 8; index++) {
            requests.add(() -> chatClients.build().prompt().user("Weather?").tools(new TestTools())
                    .toolContext(ToolGateContext.of("three-calls", MARIA, session)).call().content());
        }

        int ended = 0;
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<String> request : threads.invokeAll(requests)) {
                try {
                    request.get();
                } catch (java.util.concurrent.ExecutionException failed) {
                    assertThat(failed.getCause()).isInstanceOf(ToolGateViolationException.class)
                            .hasMessageContaining(BuiltInRules.TOOL_CALL_BUDGET);
                    ended++;
                }
            }
        }

        assertThat(TestTools.RAN).as("exactly the budget, whatever the interleaving").hasSize(3);
        assertThat(ended).isEqualTo(5);
    }

    /** With chat memory, each turn is a new request in the same conversation: taint carries over. */
    @Test
    void taintCarriesAcrossTurnsOfAConversation() {
        var memory = MessageWindowChatMemory.builder().build();
        ChatClient chat = chatClients.defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build()).build();
        MODEL.thenToolCalls(new Call("call_1", "readInvoice", "{\"id\":\"INV-118\"}")).thenText("Read it.")
                .thenToolCalls(new Call("call_2", "fetchPage", "{\"url\":\"https://docs.example/terms\"}")).thenText("Fetched.")
                .thenToolCalls(new Call("call_3", "sendEmail", "{\"to\":\"billing@mycompany.com\",\"body\":\"x\"}"))
                .thenText("Done.");

        for (String turn : List.of("Read INV-118", "Check the terms", "Email billing")) {
            chat.prompt().user(turn).tools(new TestTools())
                    .toolContext(ToolGateContext.of("support-chat", MARIA, session))
                    .advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, session))
                    .call().content();
        }

        assertThat(TestTools.RAN).as("turn 3 is downgraded by taint from turns 1 and 2")
                .containsExactly("readInvoice", "fetchPage", "draftEmail");
        assertThat(MODEL.requests().getLast())
                .as("Spring AI's memory replays earlier turns, but not tool results; ToolGate's session still holds their taint")
                .contains("Read INV-118", "Check the terms").doesNotContain("DE00 1234");
    }

    /** A tool failing with an empty message: Spring AI wraps it in its ToolExecutionException. */
    @Test
    void aToolFailingWithoutAMessageIsRecordedAndTheModelIsTold() {
        MODEL.thenToolCalls(new Call("call_1", "checkQuota", "{\"partner\":\"acme\"}")).thenText("Quota unknown.");

        String answer = chatClients.build().prompt().user("Quota?").tools(new TestTools())
                .toolContext(ToolGateContext.of("probe", Principal.service("probe", Tenant.of("acme")), session))
                .call().content();

        assertThat(answer).isEqualTo("Quota unknown.");
        assertThat(TestTools.RAN).containsExactly("checkQuota");
    }

    /** Two agents in one application, each its own client, in one conversation: each keeps its own rules. */
    @Test
    void severalAgentsKeepTheirOwnRules() {
        // A builder's defaults stay shared with the clients it built, so each agent gets its own copy.
        ChatClient support = chatClients.clone().defaultToolContext(java.util.Map.of(ToolGateContext.AGENT, "support-chat")).build();
        ChatClient weather = chatClients.clone().defaultToolContext(java.util.Map.of(ToolGateContext.AGENT, "weather-desk")).build();
        MODEL.thenToolCalls(new Call("call_1", "readInvoice", "{\"id\":\"INV-118\"}")).thenText("Read.")
                .thenToolCalls(new Call("call_2", "readInvoice", "{\"id\":\"INV-118\"}")).thenText("Not allowed.");

        support.prompt().user("Read").tools(new TestTools()).toolContext(java.util.Map.of(
                ToolGateContext.PRINCIPAL, MARIA, ToolGateContext.SESSION, session)).call().content();
        weather.prompt().user("Read").tools(new TestTools()).toolContext(java.util.Map.of(
                ToolGateContext.PRINCIPAL, MARIA, ToolGateContext.SESSION, session)).call().content();

        assertThat(TestTools.RAN).as("only support-chat may read invoices").containsExactly("readInvoice");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(TestApplication.AUDIT.stream()
                .filter(AuditEvent.Decided.class::isInstance).map(AuditEvent.Decided.class::cast)
                .filter(decided -> decided.sessionId().equals(session)))
                .extracting(AuditEvent.Decided::agentId, decided -> decided.applied().getClass().getSimpleName())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("support-chat", "Allow"),
                        org.assertj.core.groups.Tuple.tuple("weather-desk", "Deny")));
    }

    private static MockModelServer start() {
        try {
            return new MockModelServer();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    @SuppressWarnings("unused")
    private static final Class<?> DECISION = Decision.class;
}
