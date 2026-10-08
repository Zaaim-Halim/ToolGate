package io.toolgate.api;

import static io.toolgate.api.policy.ArgumentRules.atLeast;
import static io.toolgate.api.policy.ArgumentRules.atMost;
import static io.toolgate.api.policy.ArgumentRules.hosts;
import static io.toolgate.api.policy.OnViolation.DEFER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.toolgate.api.policy.InvalidPolicyException;
import io.toolgate.api.policy.ToolGatePolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NumericRulesTest {

    private static final Principal JOB = Principal.service("refund-agent", Tenant.of("acme"));
    private static final Principal LENA = Principal.user("lena", Tenant.of("acme"), "finance");

    private final AtomicInteger callCounter = new AtomicInteger();

    /** Case F as written in the design: refunds over 200 go to the finance review queue. */
    @Test
    void caseFRefundOverTwoHundredIsDeferredAndThenReleased() {
        ToolGate gate = gate(policy(a -> a.rule("issueRefund.amount", atMost("1000"), atMost("200").otherwise(DEFER))));

        assertThat(refund(gate, ArgValue.number(150))).isInstanceOf(Decision.Allow.class);
        Decision large = refund(gate, ArgValue.number(480));
        assertThat(large).isInstanceOfSatisfying(Decision.Defer.class, defer -> {
            assertThat(defer.ruleId()).isEqualTo("issueRefund.amount");
            assertThat(defer.reason()).contains("480 is above 200");
        });
        assertThat(refund(gate, ArgValue.number(1500))).isInstanceOfSatisfying(Decision.Deny.class,
                deny -> assertThat(deny.reason()).contains("1500 is above 1000"));

        assertThat(gate.approve(((Decision.Defer) large).approvalId(), LENA, JOB))
                .as("the approval covers the limit that deferred it")
                .isInstanceOf(Approval.Released.class);
    }

    @Test
    void limitsAreInclusive() {
        ToolGate gate = gate(policy(a -> a.rule("issueRefund.amount", atLeast("1"), atMost("200"))));

        assertThat(refund(gate, ArgValue.number(200))).isInstanceOf(Decision.Allow.class);
        assertThat(refund(gate, ArgValue.number(1))).isInstanceOf(Decision.Allow.class);
        assertThat(refund(gate, ArgValue.number(new BigDecimal("200.01")))).isInstanceOf(Decision.Deny.class);
        assertThat(refund(gate, ArgValue.number(0))).isInstanceOf(Decision.Deny.class);
        assertThat(refund(gate, ArgValue.number(-480))).isInstanceOf(Decision.Deny.class);
    }

    @Test
    void reviewBelowAMinimumToo() {
        ToolGate gate = gate(policy(a -> a.rule("issueRefund.amount", atLeast("0"), atLeast("5").otherwise(DEFER))));

        assertThat(refund(gate, ArgValue.number(3))).isInstanceOf(Decision.Defer.class);
        assertThat(refund(gate, ArgValue.number(-1))).isInstanceOf(Decision.Deny.class);
    }

    /** Models often send numbers as text, which the tool coerces; only one reading is accepted. */
    @Test
    void plainDecimalTextCounts() {
        ToolGate gate = gate(policy(a -> a.rule("issueRefund.amount", atMost("200").otherwise(DEFER))));

        assertThat(refund(gate, ArgValue.text("150"))).isInstanceOf(Decision.Allow.class);
        assertThat(refund(gate, ArgValue.text("480.50"))).isInstanceOf(Decision.Defer.class);
    }

    /** Text a tool might read as a different number than ToolGate would is denied, not guessed at. */
    @ParameterizedTest
    @ValueSource(strings = {"1e3", "480,00", " 480", "480 ", "+480", "0x1F4", "480.", ".5", "\u0664\u0668\u0660", "NaN", "Infinity", ""})
    void ambiguousNumbersAreDenied(String text) {
        ToolGate gate = gate(policy(a -> a.rule("issueRefund.amount", atMost("1000000"))));

        assertThat(refund(gate, ArgValue.text(text))).isInstanceOfSatisfying(Decision.Deny.class,
                deny -> assertThat(deny.reason()).contains("must be a number"));
    }

    @Test
    void nonNumbersAreDenied() {
        ToolGate gate = gate(policy(a -> a.rule("issueRefund.amount", atMost("200"))));

        assertThat(refund(gate, ArgValue.bool(true))).isInstanceOf(Decision.Deny.class);
        assertThat(refund(gate, ArgValue.nullValue())).isInstanceOf(Decision.Deny.class);
    }

    @Test
    void rejectsLimitsThatContradictOrCanNeverFire() {
        assertInvalid(a -> a.rule("issueRefund.amount", atMost("200"), atMost("1000").otherwise(DEFER)), "never fire");
        assertInvalid(a -> a.rule("issueRefund.amount", atMost("200"), atMost("200").otherwise(DEFER)), "never fire");
        assertInvalid(a -> a.rule("issueRefund.amount", atLeast("5"), atLeast("1").otherwise(DEFER)), "never fire");
        assertInvalid(a -> a.rule("issueRefund.amount", atLeast("10"), atMost("5")), "every value is denied");
        assertInvalid(a -> a.rule("issueRefund.amount", atMost("200"), atMost("300")), "atMost twice");
        assertInvalid(a -> a.rule("issueRefund.amount", atMost("200"), hosts("x.example")), "not a mix");
    }

    @Test
    void limitsMustBePlainDecimals() {
        for (String limit : new String[] {"1e3", "200.", "1,000", " 200", "2OO"}) {
            assertThatIllegalArgumentException().as(limit).isThrownBy(() -> atMost(limit));
        }
        assertThat(atMost("199.99").limit()).isEqualByComparingTo("199.99");
    }

    @Test
    void limitsChangeTheHash() {
        String deny = policy(a -> a.rule("issueRefund.amount", atMost("200"))).hash();
        String defer = policy(a -> a.rule("issueRefund.amount", atMost("200").otherwise(DEFER))).hash();
        String higher = policy(a -> a.rule("issueRefund.amount", atMost("201"))).hash();

        assertThat(List.of(deny, defer, higher)).doesNotHaveDuplicates();
    }

    private static ToolGatePolicy policy(Consumer<ToolGatePolicy.AgentBuilder> extra) {
        return ToolGatePolicy.builder()
                .tool("issueRefund", t -> t.effect(Effect.IRREVERSIBLE))
                .agent("refund-agent", a -> {
                    a.allow("issueRefund").reviewers("finance");
                    extra.accept(a);
                })
                .build();
    }

    private static ToolGate gate(ToolGatePolicy policy) {
        return ToolGate.builder(policy).auditSink(event -> { }).build();
    }

    private Decision refund(ToolGate gate, ArgValue amount) {
        return gate.check(new ToolCall("c" + callCounter.incrementAndGet(), JOB, "refund-agent", "refunds",
                ToolId.local("issueRefund"), ObjectValue.of(Map.of("order", ArgValue.number(7731), "amount", amount)),
                Instant.EPOCH));
    }

    private static void assertInvalid(Consumer<ToolGatePolicy.AgentBuilder> agent, String message) {
        assertThatThrownBy(() -> policy(agent)).isInstanceOf(InvalidPolicyException.class).hasMessageContaining(message);
    }
}
