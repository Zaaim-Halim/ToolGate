package io.toolgate.sql;

import static io.toolgate.sql.SqlRules.readOnlySql;
import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.ArgValue;
import io.toolgate.api.Decision;
import io.toolgate.api.Effect;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolDefinition;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.ToolCatalog;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The rule inside a gate: the design document's support agent cannot delete through runQuery. */
class SqlGateTest {

    @Test
    void theDesignDocumentsDeleteIsDenied() {
        var policy = ToolGatePolicy.builder()
                .tool("runQuery", t -> t.effect(Effect.READ))
                .agent("support", a -> a.allow("runQuery").rule("runQuery.sql", readOnlySql()))
                .build();
        var catalog = ToolCatalog.inMemory().put(ToolDefinition.of(ToolId.local("runQuery"), "Run a read-only query",
                "{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"}}}"));
        try (ToolGate gate = ToolGate.builder(policy).toolCatalog(catalog).auditSink(event -> { }).build()) {
            assertThat(query(gate, "c1", "SELECT status FROM orders WHERE id = 42")).isInstanceOf(Decision.Allow.class);
            assertThat(query(gate, "c2", "DELETE FROM orders WHERE 1=1"))
                    .isInstanceOfSatisfying(Decision.Deny.class, deny -> {
                        assertThat(deny.ruleId()).isEqualTo("runQuery.sql");
                        assertThat(deny.reason()).contains("DELETE");
                    });
        }
    }

    private static Decision query(ToolGate gate, String callId, String sql) {
        return gate.check(new ToolCall(callId, Principal.service("support", Tenant.none()), "support", "s1",
                ToolId.local("runQuery"), ObjectValue.of(Map.of("sql", ArgValue.text(sql))), Instant.EPOCH));
    }
}
