package io.toolgate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.api.policy.ToolGatePolicy;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Calls an adapter refused before they could be checked still count, so they cannot be repeated forever. */
class RejectTest {

    private final AtomicInteger calls = new AtomicInteger();

    @Test
    void aRefusalIsADenyOfItsOwnRule() {
        ToolGate gate = gate(Mode.ENFORCE);

        assertThat(gate.reject(call(), BuiltInRules.ARGUMENTS_READABLE, "duplicate key 'to'"))
                .isInstanceOfSatisfying(Decision.Deny.class, deny -> {
                    assertThat(deny.ruleId()).isEqualTo(BuiltInRules.ARGUMENTS_READABLE);
                    assertThat(deny.reason()).contains("duplicate key");
                });
    }

    /** T6: a model sending ambiguous JSON again and again ends its run like any prober. */
    @Test
    void refusalsUseUpTheDenialBudget() {
        ToolGate gate = gate(Mode.ENFORCE);

        gate.reject(call(), BuiltInRules.ARGUMENTS_READABLE, "unreadable");
        gate.reject(call(), BuiltInRules.DEFINITION_READABLE, "unreadable");

        assertThat(gate.reject(call(), BuiltInRules.ARGUMENTS_READABLE, "unreadable"))
                .isInstanceOfSatisfying(Decision.Terminate.class,
                        terminate -> assertThat(terminate.ruleId()).isEqualTo(BuiltInRules.DENIAL_BUDGET));
        assertThat(gate.check(call())).as("and so does the next ordinary call").isInstanceOf(Decision.Terminate.class);
    }

    @Test
    void inMonitorModeARefusalIsRecordedButAllowed() {
        assertThat(gate(Mode.MONITOR).reject(call(), BuiltInRules.ARGUMENTS_READABLE, "unreadable"))
                .isInstanceOfSatisfying(Decision.Allow.class,
                        allow -> assertThat(allow.ruleId()).isEqualTo(BuiltInRules.MODE_MONITOR));
    }

    @Test
    void onlyAdapterRefusalsCanBeRecorded() {
        assertThatIllegalArgumentException().isThrownBy(() -> gate(Mode.ENFORCE).reject(call(), BuiltInRules.ALLOW_LIST, "x"));
        assertThatIllegalArgumentException().isThrownBy(() -> gate(Mode.ENFORCE).reject(call(), BuiltInRules.ARGUMENTS_READABLE, " "));
    }

    private static ToolGate gate(Mode mode) {
        ToolGatePolicy policy = ToolGatePolicy.builder().mode(mode).defaults(d -> d.denialBudget(2))
                .agent("mailer", a -> a.allow("sendEmail")).build();
        return ToolGate.builder(policy).noDefaultLog().build();
    }

    private ToolCall call() {
        return new ToolCall("c" + calls.incrementAndGet(), Principal.service("mailer", Tenant.none()), "mailer", "s1",
                ToolId.local("sendEmail"), ObjectValue.empty(), Instant.EPOCH);
    }
}
