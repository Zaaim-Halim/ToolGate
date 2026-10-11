package io.toolgate.springai;

import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.ArgValue;
import io.toolgate.api.Decision;
import io.toolgate.api.Mode;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.jdbc.JdbcApprovalQueue;
import io.toolgate.jdbc.JdbcAuditSink;
import io.toolgate.jdbc.ToolGateQueries;
import io.toolgate.spi.ApprovalQueue;
import io.toolgate.spi.AuditEvent;
import io.toolgate.spi.AuditSink;
import io.toolgate.spi.PendingCall;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The opt-in purge of ToolGate's JDBC tables. */
class JdbcRetentionTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final Principal JOB = Principal.service("refund-agent", Tenant.of("acme"));

    private final DataSource database = h2();
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ToolGateAutoConfiguration.class))
            .withBean(ToolGatePolicy.class, () -> ToolGatePolicy.builder()
                    .agent("refund-agent", a -> a.allow("issueRefund")).build())
            .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC))
            // Declared as the SPI types, as applications usually do.
            .withBean("auditRows", AuditSink.class, () -> new JdbcAuditSink(database))
            .withBean(ApprovalQueue.class, () -> new JdbcApprovalQueue(database));

    @Test
    void nothingIsDeletedUnlessTheApplicationSetsARetention() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ToolGateJdbcRetention.class));
    }

    @Test
    void deletesOldAuditRowsAndFinishedApprovalsOnly() {
        runner.withPropertyValues("toolgate.jdbc.retention=400d").run(context -> {
            var sink = (JdbcAuditSink) context.getBean("auditRows");
            var queue = (JdbcApprovalQueue) context.getBean(ApprovalQueue.class);
            sink.emit(decided(NOW.minus(Duration.ofDays(500)), "old"));
            sink.emit(decided(NOW.minus(Duration.ofDays(5)), "recent"));
            queue.add(call("old-pending", NOW.minus(Duration.ofDays(500))));
            PendingCall over = call("old-rejected", NOW.minus(Duration.ofDays(500)));
            queue.add(over);
            queue.replace(over, over.withStatus(ApprovalStatus.REJECTED));

            context.getBean(ToolGateJdbcRetention.class).runOnce();

            assertThat(new ToolGateQueries(database).audit(ToolGateQueries.AuditQuery.latest().kinds("DECIDED")))
                    .extracting(ToolGateQueries.AuditRow::sessionId).containsExactly("recent");
            assertThat(queue.get("old-rejected")).isEmpty();
            assertThat(queue.get("old-pending")).isPresent();
        });
    }

    @Test
    void withoutToolGateJdbcTheSettingIsIgnored() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolGateAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(JdbcAuditSink.class))
                .withBean(ToolGatePolicy.class, () -> ToolGatePolicy.builder()
                        .agent("refund-agent", a -> a.allow("issueRefund")).build())
                .withPropertyValues("toolgate.jdbc.retention=400d")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ToolGateJdbcRetention.class));
    }

    @Test
    void aRetentionThatIsNotPositiveStopsTheApplication() {
        runner.withPropertyValues("toolgate.jdbc.retention=0s").run(context ->
                assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("toolgate.jdbc.retention must be a positive duration"));
    }

    private static AuditEvent.Decided decided(Instant at, String sessionId) {
        return new AuditEvent.Decided(at, "c-" + sessionId, sessionId, "refund-agent", JOB, ToolId.local("issueRefund"),
                Map.of(), new Decision.Allow("allow-list"), new Decision.Allow("allow-list"), Mode.ENFORCE, 1,
                Integer.MAX_VALUE, Map.of(), "hash");
    }

    private static PendingCall call(String approvalId, Instant at) {
        var call = new ToolCall(approvalId, JOB, "refund-agent", "s-" + approvalId, ToolId.local("issueRefund"),
                ObjectValue.of(Map.of("amount", ArgValue.number(480))), at);
        return new PendingCall(approvalId, call, new Decision.Defer("issueRefund.amount", "above 200", approvalId), at,
                at.plus(Duration.ofDays(1000)), ApprovalStatus.PENDING);
    }

    private static DataSource h2() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:retention-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
            for (String ddl : JdbcApprovalQueue.Dialect.H2.ddl().split(";")) {
                String sql = ddl.lines().filter(line -> !line.startsWith("--")).reduce("", (a, b) -> a + "\n" + b).strip();
                if (!sql.isEmpty()) {
                    statement.execute(sql);
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        return source;
    }
}
