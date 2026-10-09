package io.toolgate.api;

import static io.toolgate.api.policy.ArgumentRules.atMost;
import static io.toolgate.api.policy.ArgumentRules.sameTenant;
import static io.toolgate.api.policy.ResultRules.matchesTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.OwnershipResolver;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A model can send a number like 1e999999999. BigDecimal holds it in a few bytes, but its plain
 * text is a billion characters: turning it into text must never be what a check does with it.
 */
class HugeNumberTest {

    private static final ArgValue HUGE = ArgValue.number(new BigDecimal("1e999999999"));
    private static final ArgValue TINY = ArgValue.number(new BigDecimal("1e-999999999"));
    private static final Duration QUICKLY = Duration.ofSeconds(5);
    private static final Principal MARIA = Principal.user("maria", Tenant.of("acme"));

    private final ToolGatePolicy policy = ToolGatePolicy.builder()
            .agent("agent", a -> a.allow("refund", "getInvoice", "search")
                    .rule("refund.amount", atMost("200"))
                    .rule("getInvoice.id", sameTenant("invoice"))
                    .result("search", "items[*].tenantId", matchesTenant()))
            .build();
    private final ToolGate gate = ToolGate.builder(policy).toolCatalog(Catalogs.declaring(policy)).auditSink(event -> { })
            .ownershipResolver(new OwnershipResolver() {
                @Override
                public Optional<String> tenantOf(String resourceType, String id) {
                    return Optional.of("acme");
                }
            })
            .build();

    @Test
    void numericLimitDeniesHugeAndTinyNumbersQuickly() {
        assertThat(assertTimeoutPreemptively(QUICKLY, () -> check("refund", "amount", HUGE)))
                .isInstanceOf(Decision.Deny.class);
        assertThat(assertTimeoutPreemptively(QUICKLY, () -> check("refund", "amount", ArgValue.number(new BigDecimal("-1e999999999")))))
                .isInstanceOf(Decision.Allow.class);
        assertThat(assertTimeoutPreemptively(QUICKLY, () -> check("refund", "amount", TINY)))
                .isInstanceOf(Decision.Allow.class);
    }

    @Test
    void ownershipDeniesAHugeNumericIdQuickly() {
        assertThat(assertTimeoutPreemptively(QUICKLY, () -> check("getInvoice", "id", HUGE)))
                .isInstanceOf(Decision.Deny.class);
        assertThat(assertTimeoutPreemptively(QUICKLY, () -> check("getInvoice", "id", TINY)))
                .isInstanceOf(Decision.Deny.class);
    }

    @Test
    void resultRuleWithholdsAHugeNumberQuickly() {
        ArgValue output = ArgValue.object(Map.of("items", ArgValue.list(List.of(
                ArgValue.object(Map.of("tenantId", HUGE))))));

        assertThat(assertTimeoutPreemptively(QUICKLY, () -> gate.recordResult(call("search", "q", ArgValue.text("x")),
                new ToolResult.Succeeded(output)))).isInstanceOf(ResultDecision.Withhold.class);
    }

    private Decision check(String tool, String argument, ArgValue value) {
        return gate.check(call(tool, argument, value));
    }

    private static ToolCall call(String tool, String argument, ArgValue value) {
        return new ToolCall("c-" + tool + System.nanoTime(), MARIA, "agent", "s-" + System.nanoTime(), ToolId.local(tool),
                ObjectValue.of(Map.of(argument, value)), Instant.EPOCH);
    }
}
