package io.toolgate.api;

import static io.toolgate.api.policy.ArgumentRules.hosts;
import static io.toolgate.api.policy.ArgumentRules.validated;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.toolgate.api.policy.ArgumentRule;
import io.toolgate.api.policy.InvalidPolicyException;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.ArgumentValidator;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Rules from outside core, through the ArgumentValidator SPI. */
class ValidatorRulesTest {

    /** Allows one of a few colors; anything else, including a non-string, is denied. */
    private record Colors(Set<String> allowed) implements ArgumentValidator {
        @Override
        public String kind() {
            return "colors";
        }

        @Override
        public String fingerprint() {
            return String.join(",", new TreeSet<>(allowed));
        }

        @Override
        public Optional<String> problem(ArgValue value) {
            if (!(value instanceof TextValue(String color))) {
                return Optional.of("must be a string");
            }
            return allowed.contains(color) ? Optional.empty() : Optional.of(color + " is not an allowed color");
        }
    }

    private record Failing(Function<ArgValue, Optional<String>> answer) implements ArgumentValidator {
        @Override
        public String kind() {
            return "failing";
        }

        @Override
        public String fingerprint() {
            return "-";
        }

        @Override
        public Optional<String> problem(ArgValue value) {
            return answer.apply(value);
        }
    }

    @Test
    void aValidatorAllowsAndDeniesValues() {
        ToolGate gate = gate(policy(validated(new Colors(Set.of("red", "blue")))));

        assertThat(paint(gate, ArgValue.text("red"))).isInstanceOf(Decision.Allow.class);
        assertThat(paint(gate, ArgValue.text("green"))).isInstanceOfSatisfying(Decision.Deny.class, deny -> {
            assertThat(deny.ruleId()).isEqualTo("paint.color");
            assertThat(deny.reason()).contains("argument 'color': green is not an allowed color");
        });
        assertThat(paint(gate, ArgValue.number(7))).isInstanceOf(Decision.Deny.class);
    }

    @Test
    void aValidatorThatThrowsOrReturnsNullDenies() {
        ToolGate throwing = gate(policy(validated(new Failing(value -> {
            throw new IllegalStateException("boom: secret value");
        }))));
        ToolGate returningNull = gate(policy(validated(new Failing(value -> null))));

        assertThat(paint(throwing, ArgValue.text("red"))).isInstanceOfSatisfying(Decision.Deny.class,
                deny -> assertThat(deny.reason()).contains("failing could not check the value (IllegalStateException)")
                        .doesNotContain("secret"));
        assertThat(paint(returningNull, ArgValue.text("red"))).isInstanceOf(Decision.Deny.class);
    }

    /** A rule that overflows the stack ends the run, audited; the thread goes on checking. */
    @Test
    void aValidatorThatOverflowsTheStackEndsTheRun() {
        ToolGate gate = gate(policy(validated(new Failing(ValidatorRulesTest::recurse))));

        assertThat(paint(gate, ArgValue.text("red"))).isInstanceOfSatisfying(Decision.Terminate.class, ended -> {
            assertThat(ended.ruleId()).isEqualTo("paint.color");
            assertThat(ended.reason()).contains("ran out of stack");
        });
        assertThat(paint(gate(policy(validated(new Colors(Set.of("red"))))), ArgValue.text("red")))
                .isInstanceOf(Decision.Allow.class);
    }

    /** Errors other than a stack overflow are not decisions: they reach the caller, which ends the run. */
    @Test
    void anOutOfMemoryErrorIsNotSwallowed() {
        ToolGate gate = gate(policy(validated(new Failing(value -> {
            throw new OutOfMemoryError("simulated");
        }))));

        assertThatThrownBy(() -> paint(gate, ArgValue.text("red"))).isInstanceOf(OutOfMemoryError.class);
    }

    private static Optional<String> recurse(ArgValue value) {
        return recurse(value).or(Optional::empty);
    }

    @Test
    void severalValidatorsOfDifferentKindsShareAnArgument() {
        ToolGate gate = gate(policy(validated(new Colors(Set.of("red", "green"))),
                validated(new Failing(value -> value.equals(ArgValue.text("green")) ? Optional.of("no green") : Optional.empty()))));

        assertThat(paint(gate, ArgValue.text("red"))).isInstanceOf(Decision.Allow.class);
        assertThat(paint(gate, ArgValue.text("green"))).isInstanceOf(Decision.Deny.class);
    }

    @Test
    void kindsAreShortNamesGivenOncePerArgumentAndNeverMixedWithBuiltIns() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ArgumentRule.Validated(new Colors(Set.of()), "", "-"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ArgumentRule.Validated(new Colors(Set.of()), "a b", "-"));
        assertThatThrownBy(() -> policy(validated(new Colors(Set.of("red"))), validated(new Colors(Set.of("blue")))))
                .isInstanceOf(InvalidPolicyException.class).hasMessageContaining("colors twice");
        assertThatThrownBy(() -> policy(validated(new Colors(Set.of("red"))), hosts("example.com")))
                .isInstanceOf(InvalidPolicyException.class).hasMessageContaining("not a mix");
    }

    @Test
    void theHashFollowsTheFingerprint() {
        assertThat(policy(validated(new Colors(Set.of("red", "blue")))).hash())
                .isEqualTo(policy(validated(new Colors(Set.of("blue", "red")))).hash())
                .isNotEqualTo(policy(validated(new Colors(Set.of("red")))).hash());
    }

    private static ToolGatePolicy policy(ArgumentRule... rules) {
        return ToolGatePolicy.builder()
                .tool("paint", t -> t.effect(Effect.REVERSIBLE))
                .agent("painter", a -> a.allow("paint").rule("paint.color", rules))
                .build();
    }

    private static ToolGate gate(ToolGatePolicy policy) {
        return ToolGate.builder(policy).toolCatalog(Catalogs.declaring(policy)).noDefaultLog().build();
    }

    private static Decision paint(ToolGate gate, ArgValue color) {
        return gate.check(new ToolCall("c1", Principal.service("svc", Tenant.none()), "painter", "s1",
                ToolId.local("paint"), ObjectValue.of(Map.of("color", color)), Instant.EPOCH));
    }
}
