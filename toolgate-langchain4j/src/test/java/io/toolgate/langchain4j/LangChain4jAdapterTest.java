package io.toolgate.langchain4j;

import static io.toolgate.api.Effect.IRREVERSIBLE;
import static io.toolgate.api.Effect.READ;
import static io.toolgate.api.Effect.REVERSIBLE;
import static io.toolgate.api.Label.EXTERNAL_SINK;
import static io.toolgate.api.Label.PRIVATE_DATA;
import static io.toolgate.api.Label.UNTRUSTED_CONTENT;
import static io.toolgate.api.policy.ArgumentRules.atMost;
import static io.toolgate.api.policy.ArgumentRules.hosts;
import static io.toolgate.api.policy.OnViolation.DEFER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import io.toolgate.api.Approval;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.Decision;
import io.toolgate.api.Mode;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.langchain4j.MockModelServer.Call;
import io.toolgate.spi.AuditEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The adapter on real AI services against a scripted model: what reaches the tools, and what the audit shows. */
class LangChain4jAdapterTest {

    private static final MockModelServer MODEL = start();
    private static final Principal MARIA = Principal.user("maria", Tenant.of("acme"));
    private static final Principal JOB = Principal.service("refund-agent", Tenant.of("acme"));
    private static final Principal LENA = Principal.user("lena", Tenant.of("acme"), "finance");

    private final OpenAiChatModel chatModel = OpenAiChatModel.builder()
            .baseUrl(MODEL.baseUrl()).apiKey("test").modelName("gpt-4o").build();
    private final OpenAiStreamingChatModel streamingModel = OpenAiStreamingChatModel.builder()
            .baseUrl(MODEL.baseUrl()).apiKey("test").modelName("gpt-4o").build();
    private final List<AuditEvent> audit = new CopyOnWriteArrayList<>();

    private ToolGateLangChain4j toolGate;
    private String session;

    interface Assistant {
        String chat(@UserMessage String message, InvocationParameters parameters);
    }

    interface ImmediateAssistant {
        Result<String> chat(@UserMessage String message, InvocationParameters parameters);
    }

    interface StreamingAssistant {
        TokenStream chat(@UserMessage String message, InvocationParameters parameters);
    }

    interface AsyncAssistant {
        CompletableFuture<String> chat(@UserMessage String message, InvocationParameters parameters);
    }

    interface MemoryAssistant {
        String chat(@MemoryId String conversation, @UserMessage String message);
    }

    interface DefaultMemoryAssistant {
        String chat(@UserMessage String message);
    }

    static ToolGatePolicy policy(Mode mode) {
        return ToolGatePolicy.builder()
                .mode(mode)
                .tool("getWeather", t -> t.effect(READ))
                .tool("lookupOrder", t -> t.effect(READ))
                .tool("fetchPage", t -> t.labels(UNTRUSTED_CONTENT, EXTERNAL_SINK).effect(READ))
                .tool("readInvoice", t -> t.labels(PRIVATE_DATA).effect(READ))
                .tool("sendEmail", t -> t.labels(EXTERNAL_SINK).effect(IRREVERSIBLE))
                .tool("draftEmail", t -> t.effect(REVERSIBLE))
                .tool("issueRefund", t -> t.effect(IRREVERSIBLE))
                .agent("support-chat", a -> a
                        .allow("getWeather", "lookupOrder", "fetchPage", "readInvoice", "sendEmail", "draftEmail")
                        .rule("fetchPage.url", hosts("docs.example"))
                        .downgrade("sendEmail", "draftEmail"))
                .agent("three-calls", a -> a.allow("getWeather").budget(b -> b.toolCalls(3)))
                .agent("refund-agent", a -> a
                        .principal(JOB)
                        .reviewers("finance")
                        .allow("issueRefund")
                        .rule("issueRefund.amount", atMost("1000"), atMost("200").otherwise(DEFER)))
                .build();
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
        toolGate = gate(Mode.ENFORCE);
    }

    @AfterEach
    void close() {
        toolGate.close();
    }

    private ToolGateLangChain4j gate(Mode mode) {
        return ToolGateLangChain4j.builder(policy(mode)).gate(gate -> gate.auditSink(audit::add)).build();
    }

    @Test
    void anAllowedToolRunsAndTheDecisionIsAudited() {
        MODEL.thenToolCalls(new Call("call_1", "getWeather", "{\"city\":\"Paris\"}")).thenText("It is sunny.");

        assertThat(assistant("support-chat").chat("Weather?", parameters())).isEqualTo("It is sunny.");

        assertThat(TestTools.RAN).containsExactly("getWeather Paris");
        assertThat(decisions()).singleElement().satisfies(decided -> {
            assertThat(decided.tool().toString()).isEqualTo("local/getWeather");
            assertThat(decided.principal()).isEqualTo(MARIA);
            assertThat(decided.applied()).isInstanceOf(Decision.Allow.class);
        });
    }

    @Test
    void aDeniedToolDoesNotRunAndTheModelLearnsOnlyThat() {
        MODEL.thenToolCalls(new Call("call_1", "fetchPage", "{\"url\":\"https://evil.example/x\"}")).thenText("Sorry.");

        assistant("support-chat").chat("Read it", parameters());

        assertThat(TestTools.RAN).isEmpty();
        assertThat(MODEL.requests().get(1)).contains(Decision.Deny.MODEL_MESSAGE).doesNotContain("fetchPage.url");
        assertThat(decisions()).singleElement().satisfies(decided -> assertThat(decided.applied().ruleId())
                .isEqualTo("fetchPage.url"));
    }

    @Test
    void aSendAfterUntrustedAndPrivateDataIsDowngradedToItsSafeTool() {
        MODEL.thenToolCalls(new Call("c1", "readInvoice", "{\"id\":\"INV-118\"}"))
                .thenToolCalls(new Call("c2", "fetchPage", "{\"url\":\"https://docs.example/terms\"}"))
                .thenToolCalls(new Call("c3", "sendEmail", "{\"to\":\"billing@mycompany.com\",\"body\":\"IBAN\"}"))
                .thenText("Done.");

        assistant("support-chat").chat("Process INV-118", parameters());

        assertThat(TestTools.RAN).containsExactly("readInvoice INV-118", "fetchPage", "draftEmail billing@mycompany.com");
        assertThat(decisions()).extracting(decided -> decided.applied().getClass().getSimpleName())
                .containsExactly("Allow", "Allow", "Downgrade");
    }

    /** The spike's gap, closed: the run ends before anything runs, and no further model request is made. */
    @Test
    void aToolRegisteredAroundToolGateOrInventedEndsTheRun() {
        MODEL.thenToolCalls(new Call("call_1", "deleteAllOrders", "{}")).thenText("Done.");

        Assistant around = toolGate.agent("support-chat").tools(new TestTools())
                .applyTo(AiServices.builder(Assistant.class).chatModel(chatModel).tools(new TestTools.Admin()))
                .build();

        assertThatThrownBy(() -> around.chat("Clean up", parameters()))
                .isInstanceOfSatisfying(ToolGateViolationException.class,
                        ended -> assertThat(ended.ruleId()).isEqualTo(AgentTools.UNREGISTERED));
        assertThat(TestTools.RAN).isEmpty();
        assertThat(MODEL.requests()).hasSize(1);
    }

    @Test
    void aTerminateEndsTheRunBeforeTheRestOfTheBatch() {
        MODEL.thenToolCalls(new Call("c1", "getWeather", "{\"city\":\"Paris\"}"),
                new Call("c2", "getWeather", "{\"city\":\"Oslo\"}"),
                new Call("c3", "getWeather", "{\"city\":\"Rome\"}"),
                new Call("c4", "getWeather", "{\"city\":\"Lima\"}"),
                new Call("c5", "getWeather", "{\"city\":\"Kyiv\"}")).thenText("unreachable");

        assertThatThrownBy(() -> assistant("three-calls").chat("Weather everywhere", parameters()))
                .isInstanceOfSatisfying(ToolGateViolationException.class,
                        ended -> assertThat(ended.ruleId()).isEqualTo(BuiltInRules.TOOL_CALL_BUDGET));
        assertThat(TestTools.RAN).containsExactly("getWeather Paris", "getWeather Oslo", "getWeather Rome");
        assertThat(MODEL.requests()).hasSize(1);
    }

    @Test
    void ambiguousArgumentsAreRefused() {
        MODEL.thenToolCalls(new Call("c1", "getWeather", "{\"city\":\"Paris\",\"city\":\"Oslo\"}")).thenText("Sorry.");

        assistant("support-chat").chat("Weather?", parameters());

        assertThat(TestTools.RAN).isEmpty();
        assertThat(decisions()).singleElement().satisfies(decided -> assertThat(decided.applied().ruleId())
                .isEqualTo(BuiltInRules.ARGUMENTS_READABLE));
    }

    @Test
    void parallelCallsAreEachChecked() {
        MODEL.thenToolCalls(new Call("c1", "getWeather", "{\"city\":\"Paris\"}"),
                new Call("c2", "fetchPage", "{\"url\":\"https://evil.example\"}")).thenText("Paris is sunny.");

        toolGate.agent("support-chat").tools(new TestTools())
                .applyTo(AiServices.builder(Assistant.class).chatModel(chatModel).executeToolsConcurrently())
                .build().chat("Weather and page?", parameters());

        assertThat(TestTools.RAN).containsExactly("getWeather Paris");
        assertThat(decisions()).hasSize(2);
    }

    @Test
    void anImmediateReturnToolIsGated() {
        MODEL.thenToolCalls(new Call("c1", "lookupOrder", "{\"order\":\"7731\"}"));

        Result<String> result = toolGate.agent("support-chat").tools(new TestTools())
                .applyTo(AiServices.builder(ImmediateAssistant.class).chatModel(chatModel)).build()
                .chat("Where is 7731?", parameters());

        assertThat(result.toolExecutions()).singleElement()
                .satisfies(execution -> assertThat(execution.result()).contains("order 7731 shipped"));
        assertThat(decisions()).hasSize(1);
    }

    @Test
    void aStreamingRunIsGated() throws Exception {
        MODEL.thenToolCalls(new Call("c1", "fetchPage", "{\"url\":\"https://evil.example\"}")).thenText("I cannot read that.");
        var done = new CompletableFuture<String>();

        toolGate.agent("support-chat").tools(new TestTools())
                .applyTo(AiServices.builder(StreamingAssistant.class).streamingChatModel(streamingModel)).build()
                .chat("Read it", parameters())
                .onPartialResponse(token -> { })
                .onCompleteResponse(response -> done.complete(response.aiMessage().text()))
                .onError(done::completeExceptionally)
                .start();

        assertThat(done.get(20, TimeUnit.SECONDS)).isEqualTo("I cannot read that.");
        assertThat(TestTools.RAN).isEmpty();
        assertThat(decisions()).singleElement().satisfies(decided -> assertThat(decided.applied().ruleId())
                .isEqualTo("fetchPage.url"));
    }

    @Test
    void anAsyncServiceMethodIsGated() throws Exception {
        MODEL.thenToolCalls(new Call("c1", "getWeather", "{\"city\":\"Paris\"}"),
                new Call("c2", "fetchPage", "{\"url\":\"https://evil.example\"}")).thenText("Paris is sunny.");

        String answer = toolGate.agent("support-chat").tools(new TestTools())
                .applyTo(AiServices.builder(AsyncAssistant.class).chatModel(chatModel)).build()
                .chat("Weather and page?", parameters()).get(20, TimeUnit.SECONDS);

        assertThat(answer).isEqualTo("Paris is sunny.");
        assertThat(TestTools.RAN).containsExactly("getWeather Paris");
        assertThat(decisions()).hasSize(2);
    }

    @Test
    void theMemoryIdIsTheSessionWhenNoneIsPassed() {
        MODEL.thenToolCalls(new Call("c1", "getWeather", "{\"city\":\"Paris\"}")).thenText("It is sunny.");

        Principal declared = Principal.service("refund-agent", Tenant.of("acme"));
        toolGate.agent("refund-agent").tools(new TestTools())
                .applyTo(AiServices.builder(MemoryAssistant.class).chatModel(chatModel)
                        .chatMemoryProvider(id -> MessageWindowChatMemory.withMaxMessages(10)))
                .build().chat(session, "Weather?");

        assertThat(decisions()).singleElement().satisfies(decided -> {
            assertThat(decided.sessionId()).isEqualTo(session);
            assertThat(decided.principal()).as("the identity the policy declares").isEqualTo(declared);
        });
    }

    /** LangChain4j's "default" memory ID is every user's; it never stands for a session. */
    @Test
    void withoutASessionTheRunEnds() {
        MODEL.thenToolCalls(new Call("c1", "getWeather", "{\"city\":\"Paris\"}")).thenText("It is sunny.");

        DefaultMemoryAssistant shared = toolGate.agent("refund-agent").tools(new TestTools())
                .applyTo(AiServices.builder(DefaultMemoryAssistant.class).chatModel(chatModel)
                        .chatMemory(MessageWindowChatMemory.withMaxMessages(10)))
                .build();

        assertThatThrownBy(() -> shared.chat("Weather?"))
                .isInstanceOfSatisfying(ToolGateViolationException.class,
                        ended -> assertThat(ended.ruleId()).isEqualTo(AgentTools.NO_CONTEXT));
        assertThat(TestTools.RAN).isEmpty();
    }

    @Test
    void withoutAPrincipalTheRunEnds() {
        MODEL.thenToolCalls(new Call("c1", "getWeather", "{\"city\":\"Paris\"}")).thenText("It is sunny.");

        assertThatThrownBy(() -> assistant("support-chat").chat("Weather?",
                InvocationParameters.from(ToolGateParameters.SESSION, session)))
                .isInstanceOfSatisfying(ToolGateViolationException.class,
                        ended -> assertThat(ended.ruleId()).isEqualTo(AgentTools.NO_CONTEXT));
        assertThat(TestTools.RAN).isEmpty();
    }

    /** The application's hook runs after ToolGate's check: for a call ToolGate ends the run on, it never runs. */
    @Test
    void theApplicationsHookRunsAfterToolGatesCheck() {
        var seen = new CopyOnWriteArrayList<String>();
        MODEL.thenToolCalls(new Call("c1", "getWeather", "{\"city\":\"Paris\"}"),
                new Call("c2", "getWeather", "{\"city\":\"Oslo\"}"),
                new Call("c3", "getWeather", "{\"city\":\"Rome\"}"),
                new Call("c4", "getWeather", "{\"city\":\"Lima\"}")).thenText("unreachable");

        assertThatThrownBy(() -> toolGate.agent("three-calls").tools(new TestTools())
                .hook(before -> seen.add(before.request().id()))
                .applyTo(AiServices.builder(Assistant.class).chatModel(chatModel)).build()
                .chat("Weather everywhere", parameters())).isInstanceOf(ToolGateViolationException.class);
        assertThat(seen).as("the fourth call ended the run before the application's hook").containsExactly("c1", "c2", "c3");
    }

    /** A hook set on the builder afterwards replaces ToolGate's; the executors still check every call. */
    @Test
    void aHookThatReplacesToolGatesLeavesItsExecutorsChecking() {
        MODEL.thenToolCalls(new Call("c1", "fetchPage", "{\"url\":\"https://evil.example\"}"),
                new Call("c2", "getWeather", "{\"city\":\"Paris\"}")).thenText("Paris is sunny.");

        toolGate.agent("support-chat").tools(new TestTools())
                .applyTo(AiServices.builder(Assistant.class).chatModel(chatModel))
                .beforeToolExecution(before -> { })
                .build().chat("Page and weather?", parameters());

        assertThat(TestTools.RAN).containsExactly("getWeather Paris");
        assertThat(decisions()).extracting(decided -> decided.applied().ruleId())
                .containsExactly("fetchPage.url", BuiltInRules.ALLOW_LIST);
    }

    @Test
    void monitorModeLogsTheDenialAndRunsTheTool() {
        try (ToolGateLangChain4j monitored = gate(Mode.MONITOR)) {
            MODEL.thenToolCalls(new Call("c1", "fetchPage", "{\"url\":\"https://evil.example\"}")).thenText("Read.");

            monitored.agent("support-chat").tools(new TestTools())
                    .applyTo(AiServices.builder(Assistant.class).chatModel(chatModel)).build()
                    .chat("Read it", parameters());

            assertThat(TestTools.RAN).containsExactly("fetchPage");
            assertThat(decisions()).singleElement().satisfies(decided -> {
                assertThat(decided.applied()).isInstanceOf(Decision.Allow.class);
                assertThat(decided.computed().ruleId()).isEqualTo("fetchPage.url");
            });
        }
    }

    /** Case F's mechanics: a refund over 200 waits; approved, it runs once, as the model wrote it. */
    @Test
    void anApprovedDeferredCallRunsOnce() {
        MODEL.thenToolCalls(new Call("c1", "issueRefund", "{\"order\":\"7731\",\"amount\":480}")).thenText("Pending.");

        assistant("refund-agent").chat("Refund 7731", InvocationParameters.from(ToolGateParameters.SESSION, session));
        assertThat(MODEL.requests().get(1)).contains(Decision.Defer.MODEL_MESSAGE);
        String approvalId = toolGate.pending().getFirst().approvalId();

        assertThat(toolGate.approve(approvalId, LENA, JOB)).isInstanceOf(Approval.Released.class);
        assertThat(toolGate.approve(approvalId, LENA, JOB)).isEqualTo(new Approval.AlreadyDecided(ApprovalStatus.EXECUTED));
        assertThat(TestTools.RAN).containsExactly("issueRefund 480");
    }

    private Assistant assistant(String agent) {
        return toolGate.agent(agent).tools(new TestTools())
                .applyTo(AiServices.builder(Assistant.class).chatModel(chatModel)).build();
    }

    private InvocationParameters parameters() {
        return ToolGateParameters.of(MARIA, session);
    }

    /** This test's decisions, once the asynchronous audit has delivered them. */
    private List<AuditEvent.Decided> decisions() {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        List<AuditEvent.Decided> found;
        int stable = 0;
        int last = -1;
        do {
            found = audit.stream().filter(AuditEvent.Decided.class::isInstance).map(AuditEvent.Decided.class::cast)
                    .filter(decided -> decided.sessionId().equals(session)).toList();
            stable = found.size() == last ? stable + 1 : 0;
            last = found.size();
            sleep();
        } while (stable < 3 && System.nanoTime() < deadline);
        return found;
    }

    private static void sleep() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static MockModelServer start() {
        try {
            return new MockModelServer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
