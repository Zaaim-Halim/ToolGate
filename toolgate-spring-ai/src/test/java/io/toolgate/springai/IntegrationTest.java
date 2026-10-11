package io.toolgate.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.toolgate.api.Approval;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.Decision;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.spi.PendingCall;
import io.toolgate.springai.MockModelServer.Call;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Paths found by probing the real integration: deferral and release, failures, refusals, direct answers. */
@SpringBootTest(classes = TestApplication.class)
class IntegrationTest {

    private static final MockModelServer MODEL = start();
    private static final Principal LENA = Principal.user("lena", Tenant.of("acme"), "finance");
    private static final Principal JOB = Principal.service("refund-job", Tenant.of("acme"));

    @Autowired
    private ChatClient.Builder chatClients;
    @Autowired
    private ToolGateApprovals approvals;
    @Autowired
    private Gating gating;

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
        session = "it-" + UUID.randomUUID();
    }

    /** Case F in Spring AI: a refund over 200 waits; once approved it runs, once, as the model wrote it. */
    @Test
    void anApprovedDeferredCallRunsOnceWithTheModelsArguments() {
        MODEL.thenToolCalls(new Call("call_1", "issueRefund", "{\"order\":\"7731\",\"amount\":480}")).thenText("Pending.");

        ask("refund-review", JOB);

        assertThat(TestTools.RAN).isEmpty();
        assertThat(MODEL.requests().getLast()).contains(Decision.Defer.MODEL_MESSAGE);
        PendingCall pending = pendingOfThisSession();

        assertThat(approvals.approve(pending.approvalId(), LENA, JOB)).isInstanceOf(Approval.Released.class);
        assertThat(TestTools.RAN).containsExactly("issueRefund 480");
        assertThat(approvals.approve(pending.approvalId(), LENA, JOB))
                .isEqualTo(new Approval.AlreadyDecided(ApprovalStatus.EXECUTED));
        assertThat(TestTools.RAN).as("released at most once").containsExactly("issueRefund 480");
        assertThat(gating.gate().withStatus(ApprovalStatus.EXECUTED)).extracting(PendingCall::approvalId)
                .contains(pending.approvalId());
    }

    /** G5: a chat screen learns which of its request's calls wait for review, from the response itself. */
    @Test
    void theResponseNamesTheRequestsDeferredCalls() {
        MODEL.thenToolCalls(new Call("call_1", "issueRefund", "{\"order\":\"7731\",\"amount\":480}")).thenText("Pending.");

        ChatClientResponse response = chatClients.build().prompt().user("Go").tools(new TestTools())
                .toolContext(ToolGateContext.of("refund-review", JOB, session)).call().chatClientResponse();

        assertThat(ToolGateDeferrals.of(response)).singleElement().satisfies(action -> {
            assertThat(action.approvalId()).isEqualTo(pendingOfThisSession().approvalId());
            assertThat(action.tool()).isEqualTo(ToolId.local("issueRefund"));
            assertThat(action.expiresAt()).isAfter(Instant.now());
        });
    }

    /** A call approved elsewhere, such as from a separate console, is run by this instance's executor, once. */
    @Test
    void aCallApprovedElsewhereIsRunByTheExecutor() {
        MODEL.thenToolCalls(new Call("call_1", "issueRefund", "{\"order\":\"7731\",\"amount\":480}")).thenText("Pending.");
        ask("refund-review", JOB);
        PendingCall pending = pendingOfThisSession();

        assertThat(gating.gate().approve(pending.approvalId(), LENA, JOB)).isInstanceOf(Approval.Released.class);
        assertThat(TestTools.RAN).isEmpty();

        assertThat(approvals.runApproved()).isEqualTo(1);
        assertThat(approvals.runApproved()).isZero();
        assertThat(TestTools.RAN).containsExactly("issueRefund 480");
        assertThat(gating.gate().withStatus(ApprovalStatus.EXECUTED)).extracting(PendingCall::approvalId)
                .contains(pending.approvalId());
    }

    /** Without the tool, nothing is approved: the release is not spent on a call that cannot run. */
    @Test
    void aDeferredCallWhoseToolIsNotHereStaysPending() {
        MODEL.thenToolCalls(new Call("call_1", "issueRefund", "{\"order\":\"7731\",\"amount\":480}")).thenText("Pending.");
        ask("refund-review", JOB);
        PendingCall pending = pendingOfThisSession();
        gating.forget(pending.approvalId());

        assertThat(approvals.approve(pending.approvalId(), LENA, JOB)).isInstanceOf(Approval.NotPermitted.class);

        assertThat(TestTools.RAN).isEmpty();
        assertThat(approvals.pending()).extracting(PendingCall::approvalId).contains(pending.approvalId());
    }

    /** T6: ambiguous JSON is recorded as a refusal, so repeating it ends the run like any probing. */
    @Test
    void repeatedUnreadableArgumentsEndTheRun() {
        var ambiguous = "{\"url\":\"https://docs.example/\",\"url\":\"https://evil.example/\"}";
        Call[] calls = new Call[6];
        for (int index = 0; index < calls.length; index++) {
            calls[index] = new Call("call_" + index, "fetchPage", ambiguous);
        }
        MODEL.thenToolCalls(calls).thenText("never asked for");

        assertThatThrownBy(() -> ask("support-chat", Principal.user("maria", Tenant.of("acme"))))
                .isInstanceOf(ToolGateViolationException.class)
                .hasMessageContaining(BuiltInRules.DENIAL_BUDGET);
        assertThat(TestTools.RAN).isEmpty();
    }

    /** A failing tool's error can carry an attacker's text as easily as a result, so it taints the session. */
    @Test
    void aFailingUntrustedToolStillTaintsTheSession() {
        MODEL.thenToolCalls(new Call("call_1", "readInvoice", "{\"id\":\"INV-118\"}"))
                .thenToolCalls(new Call("call_2", "fetchStatus", "{\"url\":\"https://status.example\"}"))
                .thenToolCalls(new Call("call_3", "sendEmail", "{\"to\":\"billing@mycompany.com\",\"body\":\"x\"}"))
                .thenText("Done.");

        ask("probe", Principal.service("probe", Tenant.of("acme")));

        assertThat(TestTools.RAN).containsExactly("readInvoice", "fetchStatus", "draftEmail");
    }

    /**
     * The safe tool has argument rules, and so needs its definition, though only the downgraded tool was
     * called: it was offered with the same request, which is where its definition comes from.
     */
    @Test
    void aDowngradeToASafeToolWithArgumentRulesRuns() {
        MODEL.thenToolCalls(new Call("call_1", "readTicket", "{\"id\":\"48213\"}"))
                .thenToolCalls(new Call("call_2", "issueRefund", "{\"order\":\"7731\",\"amount\":480}"))
                .thenText("Queued.");

        ask("refunds-ruled", JOB);

        assertThat(TestTools.RAN).containsExactly("readTicket", "queueRefund 480");
    }

    /** A tool whose result goes straight to the user: the user gets the generic denial, and the model nothing. */
    @Test
    void aDeniedDirectAnswerToolAnswersWithTheGenericDenial() {
        MODEL.thenToolCalls(new Call("call_1", "lookupOrder", "{\"id\":\"7731\"}")).thenText("never asked for");

        assertThat(ask("support-chat", Principal.user("maria", Tenant.of("acme")))).isEqualTo(Decision.Deny.MODEL_MESSAGE);
        assertThat(TestTools.RAN).isEmpty();
        assertThat(MODEL.requests()).hasSize(1);
    }

    private PendingCall pendingOfThisSession() {
        return approvals.pending().stream().filter(pending -> pending.call().sessionId().equals(session))
                .findFirst().orElseThrow();
    }

    private String ask(String agent, Principal principal) {
        return chatClients.build().prompt().user("Go").tools(new TestTools())
                .toolContext(ToolGateContext.of(agent, principal, session)).call().content();
    }

    private static MockModelServer start() {
        try {
            return new MockModelServer();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
