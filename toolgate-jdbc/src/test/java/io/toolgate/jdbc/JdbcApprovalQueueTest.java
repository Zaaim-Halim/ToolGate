package io.toolgate.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.ArgValue;
import io.toolgate.api.Decision;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolId;
import io.toolgate.spi.PendingCall;
import io.toolgate.spi.ValueCodec;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** The same contract as the in-memory queue, against a real database. */
class JdbcApprovalQueueTest {

    private static final Instant T0 = Instant.parse("2026-10-08T10:02:00.123456Z");

    private final H2 database = H2.withQueueTable();
    private final JdbcApprovalQueue queue = new JdbcApprovalQueue(database);

    @Test
    void readsBackExactlyWhatWasQueued() {
        PendingCall original = pending("refunds:c2", T0, Principal.user("maria", Tenant.of("acme"), "support", "finance"),
                ToolId.mcp("payments", "issue_refund"), ObjectValue.of(Map.of(
                        "amount", ArgValue.number(new BigDecimal("480.50")),
                        "note", ArgValue.text("M\u00fcller \"urgent\"\n\u1f600"),
                        "lines", ArgValue.list(List.of(ArgValue.object(Map.of("sku", ArgValue.text("A-1"))), ArgValue.nullValue())),
                        "express", ArgValue.bool(true))));

        queue.add(original);

        assertThat(queue.get("refunds:c2")).contains(original);
    }

    @Test
    void keepsAPrincipalWithoutTenantOrRoles() {
        PendingCall original = pending("jobs:c1", T0, Principal.service("refund-agent", Tenant.none()),
                ToolId.local("issueRefund"), ObjectValue.empty());

        queue.add(original);

        assertThat(queue.get("jobs:c1")).contains(original);
    }

    @Test
    void addingTheSameApprovalTwiceKeepsTheFirst() {
        queue.add(refund("refunds:c2", T0));
        queue.transition("refunds:c2", ApprovalStatus.PENDING, ApprovalStatus.REJECTED);

        queue.add(refund("refunds:c2", T0.plusSeconds(5)));

        assertThat(queue.get("refunds:c2").orElseThrow().status()).isEqualTo(ApprovalStatus.REJECTED);
    }

    @Test
    void transitionOnlyFromTheStatusSeen() {
        queue.add(refund("refunds:c2", T0));

        assertThat(queue.transition("refunds:c2", ApprovalStatus.PENDING, ApprovalStatus.RELEASED)).isTrue();
        assertThat(queue.transition("refunds:c2", ApprovalStatus.PENDING, ApprovalStatus.RELEASED)).isFalse();
        assertThat(queue.transition("unknown", ApprovalStatus.PENDING, ApprovalStatus.RELEASED)).isFalse();
        assertThat(queue.get("unknown")).isEmpty();
    }

    /** Fifty reviewers on fifty connections approve one call: the database releases it once. */
    @Test
    void parallelApprovalsReleaseExactlyOnce() throws Exception {
        queue.add(refund("refunds:c2", T0));
        var approvals = new ArrayList<Callable<Boolean>>();
        for (int reviewer = 0; reviewer < 50; reviewer++) {
            approvals.add(() -> queue.transition("refunds:c2", ApprovalStatus.PENDING, ApprovalStatus.RELEASED));
        }

        int released = 0;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Boolean> outcome : executor.invokeAll(approvals)) {
                released += outcome.get() ? 1 : 0;
            }
        }

        assertThat(released).isEqualTo(1);
    }

    @Test
    void pendingListsOnlyUndecidedCallsOldestFirst() {
        queue.add(refund("refunds:c3", T0.plusSeconds(60)));
        queue.add(refund("refunds:c2", T0));
        queue.add(refund("refunds:c4", T0.plusSeconds(120)));
        queue.transition("refunds:c4", ApprovalStatus.PENDING, ApprovalStatus.REJECTED);

        assertThat(queue.pending()).extracting(PendingCall::approvalId).containsExactly("refunds:c2", "refunds:c3");
    }

    /** An ID too long for its column is not silently dropped: the failure reaches ToolGate, which denies the call. */
    @Test
    void anIdTooLongForItsColumnFailsLoudly() {
        assertThatThrownBy(() -> queue.add(refund("x".repeat(600), T0))).isInstanceOf(JdbcStoreException.class);
    }

    @Test
    void tableNamesMustBePlainIdentifiers() {
        assertThatIllegalArgumentException().isThrownBy(() -> new JdbcApprovalQueue(database, "q; DROP TABLE users", ValueCodec.unencrypted()));
        assertThatIllegalArgumentException().isThrownBy(() -> new JdbcApprovalQueue(database, "1queue", ValueCodec.unencrypted()));
        assertThatIllegalArgumentException().isThrownBy(() -> new JdbcApprovalQueue(database, "", ValueCodec.unencrypted()));
    }

    @Test
    void shipsDdlForEachDatabase() {
        for (JdbcApprovalQueue.Dialect dialect : JdbcApprovalQueue.Dialect.values()) {
            assertThat(dialect.ddl()).contains("CREATE TABLE toolgate_pending_call").contains("CREATE TABLE toolgate_session");
            // Migration scripts often split on ';', so comments must not contain one.
            assertThat(dialect.ddl().lines().filter(line -> line.startsWith("--"))).noneMatch(line -> line.contains(";"));
        }
    }

    private static PendingCall refund(String approvalId, Instant at) {
        return pending(approvalId, at, Principal.service("refund-agent", Tenant.of("acme")), ToolId.local("issueRefund"),
                ObjectValue.of(Map.of("amount", ArgValue.number(480))));
    }

    private static PendingCall pending(String approvalId, Instant at, Principal principal, ToolId tool, ObjectValue args) {
        var call = new ToolCall(approvalId.substring(approvalId.indexOf(':') + 1), principal, "refund-agent",
                approvalId.substring(0, Math.max(approvalId.indexOf(':'), 1)), tool, args, at);
        return new PendingCall(approvalId, call, new Decision.Defer("issueRefund.amount", "480 is above 200", approvalId),
                at, at.plus(Duration.ofHours(24)), ApprovalStatus.PENDING);
    }
}
