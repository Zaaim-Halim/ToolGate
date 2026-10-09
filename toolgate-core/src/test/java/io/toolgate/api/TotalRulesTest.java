package io.toolgate.api;

import static io.toolgate.api.policy.ArgumentRules.atMost;
import static io.toolgate.api.policy.ArgumentRules.totalAtMost;
import static io.toolgate.api.policy.OnViolation.DEFER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.toolgate.api.policy.InvalidPolicyException;
import io.toolgate.api.policy.ToolGatePolicy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Per-session totals: a model cannot split one large refund into several small ones. */
class TotalRulesTest {

    private static final Principal JOB = Principal.service("refund-agent", Tenant.of("acme"));
    private static final Principal LENA = Principal.user("lena", Tenant.of("acme"), "finance");

    private final AtomicInteger callCounter = new AtomicInteger();

    @Test
    void splittingARefundStopsAtTheTotal() {
        ToolGate gate = gate(policy(a -> a.rule("issueRefund.amount", atMost("200"), totalAtMost("500"))));

        assertThat(refund(gate, "s1", ArgValue.number(200))).isInstanceOf(Decision.Allow.class);
        assertThat(refund(gate, "s1", ArgValue.number(200))).isInstanceOf(Decision.Allow.class);
        assertThat(refund(gate, "s1", ArgValue.number(160))).isInstanceOfSatisfying(Decision.Deny.class,
                deny -> assertThat(deny.reason()).contains("total 400 plus 160 is above 500"));
        assertThat(refund(gate, "s1", ArgValue.number(100))).as("exactly up to the total").isInstanceOf(Decision.Allow.class);
        assertThat(refund(gate, "s2", ArgValue.number(200))).as("another session has its own total")
                .isInstanceOf(Decision.Allow.class);
    }

    @Test
    void reviewPastOneTotalAndDenyPastAnother() {
        ToolGate gate = gate(policy(a -> a.rule("issueRefund.amount", totalAtMost("2000"), totalAtMost("500").otherwise(DEFER))));

        assertThat(refund(gate, "s1", ArgValue.number(450))).isInstanceOf(Decision.Allow.class);
        assertThat(refund(gate, "s1", ArgValue.number(100))).isInstanceOf(Decision.Defer.class);
        assertThat(refund(gate, "s1", ArgValue.number(1600))).isInstanceOf(Decision.Deny.class);
    }

    /** A negative refund would lower the total and make room for more. */
    @Test
    void negativeAmountsAreDeniedAndNeverLowerTheTotal() {
        ToolGate gate = gate(policy(a -> a.rule("issueRefund.amount", totalAtMost("500"))));

        assertThat(refund(gate, "s1", ArgValue.number(-1000))).isInstanceOfSatisfying(Decision.Deny.class,
                deny -> assertThat(deny.reason()).contains("negative"));
        assertThat(refund(gate, "s1", ArgValue.number(500))).isInstanceOf(Decision.Allow.class);
        assertThat(refund(gate, "s1", ArgValue.number(1))).isInstanceOf(Decision.Deny.class);
    }

    /** A deferred call counts once released, and the re-check sees what ran meanwhile. */
    @Test
    void releasedCallsCountAndTheReCheckSeesTheTotal() {
        ToolGate gate = gate(policy(a -> a.reviewers("finance")
                .rule("issueRefund.amount", totalAtMost("1000"), totalAtMost("300").otherwise(DEFER))));
        String first = deferred(refund(gate, "s1", ArgValue.number(400)));
        String second = deferred(refund(gate, "s1", ArgValue.number(400)));

        assertThat(gate.approve(first, LENA, JOB)).isInstanceOf(Approval.Released.class);
        assertThat(gate.approve(second, LENA, JOB)).isInstanceOf(Approval.Released.class);
        String third = deferred(refund(gate, "s1", ArgValue.number(150)));
        assertThat(refund(gate, "s1", ArgValue.number(250))).as("800 released, 250 more passes 1000")
                .isInstanceOf(Decision.Deny.class);
        assertThat(gate.approve(third, LENA, JOB)).isInstanceOf(Approval.Released.class);
        assertThat(refund(gate, "s1", ArgValue.number(51))).isInstanceOf(Decision.Deny.class);
    }

    @Test
    void parallelCallsCannotOvershootTheTotal() throws Exception {
        ToolGate gate = gate(policy(a -> a.rule("issueRefund.amount", totalAtMost("500"))));
        var calls = new ArrayList<Callable<Decision>>();
        for (int index = 0; index < 100; index++) {
            calls.add(() -> refund(gate, "busy", ArgValue.number(30)));
        }

        var decisions = new ArrayList<Decision>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Decision> decision : executor.invokeAll(calls)) {
                decisions.add(decision.get());
            }
        }

        assertThat(decisions).filteredOn(Decision.Allow.class::isInstance).hasSize(16);
    }

    @Test
    void textAmountsCountAndOtherToolsDoNot() {
        ToolGate gate = gate(policy(a -> a.allow("quoteRefund").rule("issueRefund.amount", totalAtMost("500"))));

        assertThat(refund(gate, "s1", ArgValue.text("300"))).isInstanceOf(Decision.Allow.class);
        gate.check(call("s1", "quoteRefund", ArgValue.number(10_000)));
        assertThat(refund(gate, "s1", ArgValue.text("200.00"))).isInstanceOf(Decision.Allow.class);
        assertThat(refund(gate, "s1", ArgValue.text("0.01"))).isInstanceOf(Decision.Deny.class);
    }

    /** Comparing against limit minus total, never adding, keeps a model's 1e999999999 harmless. */
    @Test
    void hugeAmountsAreDeniedQuickly() {
        ToolGate gate = gate(policy(a -> a.rule("issueRefund.amount", totalAtMost("500"))));
        refund(gate, "s1", ArgValue.number(new BigDecimal("120.50")));

        assertThat(assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> refund(gate, "s1", ArgValue.number(new BigDecimal("1e999999999"))))).isInstanceOf(Decision.Deny.class);
    }

    @Test
    void rejectsTotalLimitsThatCanNeverFire() {
        assertThatThrownBy(() -> policy(a -> a.rule("issueRefund.amount", totalAtMost("500"), totalAtMost("500").otherwise(DEFER))))
                .isInstanceOf(InvalidPolicyException.class).hasMessageContaining("never fire");
        assertThatThrownBy(() -> policy(a -> a.rule("issueRefund.amount", totalAtMost("-1"))))
                .isInstanceOf(InvalidPolicyException.class);
        assertThat(policy(a -> a.rule("issueRefund.amount", totalAtMost("500"))).hash())
                .isNotEqualTo(policy(a -> a.rule("issueRefund.amount", totalAtMost("501"))).hash());
    }

    private static ToolGatePolicy policy(Consumer<ToolGatePolicy.AgentBuilder> extra) {
        return ToolGatePolicy.builder()
                .tool("issueRefund", t -> t.effect(Effect.IRREVERSIBLE))
                .agent("refund-agent", a -> {
                    a.allow("issueRefund");
                    extra.accept(a);
                })
                .build();
    }

    private static ToolGate gate(ToolGatePolicy policy) {
        return ToolGate.builder(policy).toolCatalog(Catalogs.declaring(policy)).auditSink(event -> { }).build();
    }

    private Decision refund(ToolGate gate, String session, ArgValue amount) {
        return gate.check(call(session, "issueRefund", amount));
    }

    private ToolCall call(String session, String tool, ArgValue amount) {
        return new ToolCall("c" + callCounter.incrementAndGet(), JOB, "refund-agent", session, ToolId.local(tool),
                ObjectValue.of(Map.of("amount", amount)), Instant.EPOCH);
    }

    private static String deferred(Decision decision) {
        assertThat(decision).isInstanceOf(Decision.Defer.class);
        return ((Decision.Defer) decision).approvalId();
    }
}
