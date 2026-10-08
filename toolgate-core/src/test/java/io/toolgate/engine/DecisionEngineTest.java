package io.toolgate.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.Decision;
import io.toolgate.api.Mode;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.rules.BuiltInEvaluators;
import io.toolgate.session.InMemorySessionStore;
import io.toolgate.spi.SessionEvent;
import io.toolgate.spi.SessionState;
import io.toolgate.spi.SessionStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Combining and failure behavior, driven by scripted evaluators instead of real rules. */
class DecisionEngineTest {

    private static final ToolId SEND_EMAIL = ToolId.local("sendEmail");
    private static final ToolId DRAFT_EMAIL = ToolId.local("draftEmail");

    private final ToolGatePolicy policy = ToolGatePolicy.builder()
            .agent("invoice-processor", a -> a.allow("sendEmail", "draftEmail"))
            .build();

    /** Case A, row 4: the trifecta's block is softened into the configured downgrade. */
    @Test
    void taintDenySoftensIntoDowngrade() {
        Decision decision = gate(
                fixed("trifecta", Stage.SESSION, new Verdict.Deny("trifecta", Origin.TAINT)),
                downgradeSendToDraft())
                .check(call(SEND_EMAIL));

        assertThat(decision).isEqualTo(new Decision.Downgrade("sendEmail.whenTainted", DRAFT_EMAIL, ObjectValue.empty()));
    }

    @Test
    void taintDeferSoftensIntoDowngrade() {
        Decision decision = gate(
                fixed("irreversible", Stage.SESSION, new Verdict.Defer("untrusted session", Origin.TAINT)),
                downgradeSendToDraft())
                .check(call(SEND_EMAIL));

        assertThat(decision).isInstanceOf(Decision.Downgrade.class);
    }

    /** Case A, row 3: an outside recipient is denied even though a downgrade exists. */
    @Test
    void accessDenyIsNeverSoftened() {
        Decision decision = gate(
                fixed("sendEmail.to", Stage.ARGUMENTS, new Verdict.Deny("outside domain", Origin.ACCESS)),
                fixed("trifecta", Stage.SESSION, new Verdict.Deny("trifecta", Origin.TAINT)),
                downgradeSendToDraft())
                .check(call(SEND_EMAIL));

        assertThat(decision).isEqualTo(new Decision.Deny("sendEmail.to", "outside domain"));
    }

    @Test
    void accessDeferBeatsDowngrade() {
        Decision decision = gate(
                fixed("refund.amount", Stage.ARGUMENTS, new Verdict.Defer("over 200", Origin.ACCESS)),
                downgradeSendToDraft())
                .check(call(SEND_EMAIL));

        assertThat(decision).isEqualTo(new Decision.Defer("refund.amount", "over 200", "job-0412:job-0412/4"));
    }

    @Test
    void enforcedAccessDenySkipsLaterEvaluators() {
        var calls = new ArrayList<String>();
        gate(
                recording(calls, "first", Stage.IDENTIFY, new Verdict.Deny("no", Origin.ACCESS)),
                recording(calls, "second", Stage.SESSION, Verdict.PASS))
                .check(call(SEND_EMAIL));

        assertThat(calls).containsExactly("first");
    }

    @Test
    void throwingEvaluatorDenies() {
        Decision decision = gate(evaluator("broken", Stage.ARGUMENTS, context -> {
            throw new IllegalStateException("lookup failed");
        })).check(call(SEND_EMAIL));

        assertThat(decision).isInstanceOfSatisfying(Decision.Deny.class, deny -> {
            assertThat(deny.ruleId()).isEqualTo("broken");
            assertThat(deny.reason()).contains("lookup failed");
        });
    }

    @Test
    void nullVerdictDenies() {
        assertThat(gate(evaluator("sloppy", Stage.ARGUMENTS, context -> null)).check(call(SEND_EMAIL)))
                .isInstanceOf(Decision.Deny.class);
    }

    @Test
    void conflictingDowngradesDeny() {
        Decision decision = gate(
                downgradeSendToDraft(),
                fixed("other", Stage.SESSION, new Verdict.Downgrade(ToolId.local("queueEmail"))))
                .check(call(SEND_EMAIL));

        assertThat(decision).isInstanceOf(Decision.Deny.class);
    }

    /** A downgrade can never reach a tool the agent may not call. */
    @Test
    void downgradeToForbiddenToolDenies() {
        Decision decision = gate(
                fixed("sendEmail.whenTainted", Stage.SESSION, new Verdict.Downgrade(ToolId.local("notAllowed"))))
                .check(call(SEND_EMAIL));

        assertThat(decision).isInstanceOfSatisfying(Decision.Deny.class,
                deny -> assertThat(deny.ruleId()).isEqualTo(BuiltInRules.ALLOW_LIST));
    }

    @Test
    void downgradeWhoseSafeToolIsAlsoDowngradedDenies() {
        Decision decision = gate(evaluator("everything.downgrades", Stage.SESSION, context ->
                new Verdict.Downgrade(context.call().tool().equals(SEND_EMAIL) ? DRAFT_EMAIL : SEND_EMAIL)))
                .check(call(SEND_EMAIL));

        assertThat(decision).isInstanceOfSatisfying(Decision.Deny.class,
                deny -> assertThat(deny.ruleId()).isEqualTo(BuiltInRules.DOWNGRADE_CHAIN));
    }

    @Test
    void downgradeWhoseSafeToolIsDeferredDefers() {
        Decision decision = gate(
                downgradeSendToDraft(),
                evaluator("draft.review", Stage.ARGUMENTS, context -> context.call().tool().equals(DRAFT_EMAIL)
                        ? new Verdict.Defer("drafts need review", Origin.ACCESS)
                        : Verdict.PASS))
                .check(call(SEND_EMAIL));

        assertThat(decision).isEqualTo(new Decision.Defer("draft.review", "drafts need review", "job-0412:job-0412/4"));
    }

    @Test
    void downgradeWhoseSafeToolEndsTheRunEndsIt() {
        Decision decision = gate(
                downgradeSendToDraft(),
                evaluator("draft.limit", Stage.LIMITS, context -> context.call().tool().equals(DRAFT_EMAIL)
                        ? new Verdict.Terminate("draft limit reached")
                        : Verdict.PASS))
                .check(call(SEND_EMAIL));

        assertThat(decision).isEqualTo(new Decision.Terminate("draft.limit", "draft limit reached"));
    }

    @Test
    void storeThatNeverAcceptsAnAppendDeniesWithoutRecording() {
        var unconditionalAppends = new ArrayList<SessionEvent>();
        SessionStore alwaysConflicting = new SessionStore() {
            @Override
            public SessionState state(String sessionId) {
                return SessionState.empty(sessionId);
            }

            @Override
            public boolean append(SessionEvent event, long expectedVersion) {
                return false;
            }

            @Override
            public void append(SessionEvent event) {
                unconditionalAppends.add(event);
            }
        };
        var gate = new DefaultToolGate(policy, BuiltInEvaluators.all(), alwaysConflicting);

        assertThat(gate.check(call(SEND_EMAIL))).isInstanceOfSatisfying(Decision.Deny.class,
                deny -> assertThat(deny.ruleId()).isEqualTo(BuiltInRules.SESSION_CONTENTION));
        assertThat(unconditionalAppends).isEmpty();
    }

    @Test
    void monitorModeKeepsTheComputedDecision() {
        ToolGatePolicy monitored = ToolGatePolicy.builder()
                .mode(Mode.MONITOR)
                .agent("invoice-processor", a -> a.allow("sendEmail"))
                .build();
        var engine = new DecisionEngine(monitored,
                List.of(fixed("sendEmail.to", Stage.ARGUMENTS, new Verdict.Deny("outside domain", Origin.ACCESS))));

        Evaluation evaluation = engine.evaluate(call(SEND_EMAIL), SessionState.empty("job-0412"));

        assertThat(evaluation.effective()).isEqualTo(new Decision.Allow(BuiltInRules.MODE_MONITOR));
        assertThat(evaluation.computed()).isEqualTo(new Decision.Deny("sendEmail.to", "outside domain"));
    }

    private ToolGate gate(Evaluator... extra) {
        var evaluators = new ArrayList<>(BuiltInEvaluators.all());
        evaluators.addAll(List.of(extra));
        return new DefaultToolGate(policy, evaluators, new InMemorySessionStore());
    }

    /** Downgrades only the send, so the safe call's own check passes. */
    private static Evaluator downgradeSendToDraft() {
        return evaluator("sendEmail.whenTainted", Stage.SESSION, context -> context.call().tool().equals(SEND_EMAIL)
                ? new Verdict.Downgrade(DRAFT_EMAIL)
                : Verdict.PASS);
    }

    private static Evaluator fixed(String id, Stage stage, Verdict verdict) {
        return evaluator(id, stage, context -> context.call().tool().equals(SEND_EMAIL) ? verdict : Verdict.PASS);
    }

    private static Evaluator recording(List<String> calls, String id, Stage stage, Verdict verdict) {
        return evaluator(id, stage, context -> {
            calls.add(id);
            return verdict;
        });
    }

    private static Evaluator evaluator(String id, Stage stage, Function<EvaluationContext, Verdict> body) {
        return new Evaluator() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Stage stage() {
                return stage;
            }

            @Override
            public Verdict evaluate(EvaluationContext context) {
                return body.apply(context);
            }
        };
    }

    private static ToolCall call(ToolId tool) {
        return new ToolCall("job-0412/4", Principal.service("invoice-processor", Tenant.of("acme")),
                "invoice-processor", "job-0412", tool, ObjectValue.empty(), Instant.parse("2026-10-07T19:42:11Z"));
    }
}
