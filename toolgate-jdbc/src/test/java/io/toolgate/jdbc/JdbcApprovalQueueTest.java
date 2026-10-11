package io.toolgate.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.toolgate.api.ApprovalAccess;
import io.toolgate.api.ApprovalDecision;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.ArgValue;
import io.toolgate.api.Decision;
import io.toolgate.api.Execution;
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
        move("refunds:c2", ApprovalStatus.REJECTED);

        queue.add(refund("refunds:c2", T0.plusSeconds(5)));

        assertThat(queue.get("refunds:c2").orElseThrow().status()).isEqualTo(ApprovalStatus.REJECTED);
    }

    @Test
    void replacesOnlyTheVersionSeen() {
        PendingCall seen = refund("refunds:c2", T0);
        queue.add(seen);

        assertThat(queue.replace(seen, seen.withStatus(ApprovalStatus.RELEASED))).isTrue();
        assertThat(queue.replace(seen, seen.withStatus(ApprovalStatus.REJECTED))).isFalse();
        assertThat(queue.get("refunds:c2").orElseThrow().status()).isEqualTo(ApprovalStatus.RELEASED);
        PendingCall unknown = refund("unknown", T0);
        assertThat(queue.replace(unknown, unknown.withStatus(ApprovalStatus.RELEASED))).isFalse();
        assertThat(queue.get("unknown")).isEmpty();
    }

    /** Decisions, comments and the executor's run survive the database, comments and outcomes sealed. */
    @Test
    void keepsDecisionsAndTheRunSealed() {
        var sealing = new TaggingCodec();
        var sealed = new JdbcApprovalQueue(database, sealing);
        PendingCall queued = refund("refunds:c2", T0);
        sealed.add(queued);
        var first = new ApprovalDecision(ApprovalDecision.Kind.APPROVE, "root", ApprovalAccess.SUPER_ADMIN, T0.plusSeconds(60),
                "finance is away");
        var second = new ApprovalDecision(ApprovalDecision.Kind.APPROVE, "lena", ApprovalAccess.REVIEWER, T0.plusSeconds(120), null);
        PendingCall awaiting = queued.withDecision(first, ApprovalStatus.PENDING);
        PendingCall released = awaiting.withDecision(second, ApprovalStatus.RELEASED);
        var run = new Execution("node-1", T0.plusSeconds(130), T0.plusSeconds(190), null, null);
        PendingCall running = released.withExecution(run, ApprovalStatus.EXECUTING);
        PendingCall executed = running.withExecution(run.finished(T0.plusSeconds(140), "returned \"R-1\""), ApprovalStatus.EXECUTED);

        assertThat(sealed.replace(queued, awaiting)).isTrue();
        assertThat(sealed.replace(awaiting, released)).isTrue();
        assertThat(sealed.replace(released, running)).isTrue();
        assertThat(sealed.replace(running, executed)).isTrue();

        assertThat(sealed.get("refunds:c2")).contains(executed);
        assertThat(sealing.sealed).contains("finance is away", "returned \"R-1\"");
    }

    @Test
    void keepsTheExpiryWarning() {
        PendingCall queued = refund("refunds:c2", T0);
        queue.add(queued);

        assertThat(queue.replace(queued, queued.withExpiryWarned())).isTrue();

        assertThat(queue.get("refunds:c2").orElseThrow()).extracting(PendingCall::expiryWarned, PendingCall::version)
                .containsExactly(true, 1L);
        assertThat(queued.expiryWarned()).isFalse();
    }

    @Test
    void countsDecisionsByAccessInAWindow() {
        PendingCall first = refund("refunds:c1", T0);
        PendingCall second = refund("refunds:c2", T0);
        queue.add(first);
        queue.add(second);
        queue.replace(first, first.withDecision(new ApprovalDecision(ApprovalDecision.Kind.REJECT, "root",
                ApprovalAccess.SUPER_ADMIN, T0.plusSeconds(10), "cleanup"), ApprovalStatus.REJECTED));
        queue.replace(second, second.withDecision(new ApprovalDecision(ApprovalDecision.Kind.APPROVE, "root",
                ApprovalAccess.SUPER_ADMIN, T0.plusSeconds(20), "finance is away"), ApprovalStatus.PENDING));
        PendingCall recorded = queue.get("refunds:c2").orElseThrow();
        queue.replace(recorded, recorded.withDecision(new ApprovalDecision(ApprovalDecision.Kind.APPROVE, "lena",
                ApprovalAccess.REVIEWER, T0.plusSeconds(30), null), ApprovalStatus.RELEASED));

        assertThat(queue.countDecisions(ApprovalAccess.SUPER_ADMIN, T0, T0.plusSeconds(60))).hasValue(2);
        // After is exclusive, upTo inclusive, as the burst window needs.
        assertThat(queue.countDecisions(ApprovalAccess.SUPER_ADMIN, T0.plusSeconds(10), T0.plusSeconds(20))).hasValue(1);
        assertThat(queue.countDecisions(ApprovalAccess.REVIEWER, T0, T0.plusSeconds(60))).hasValue(1);
    }

    @Test
    void refusesAReplacementThatRewritesHistory() {
        PendingCall queued = refund("refunds:c2", T0);
        queue.add(queued);
        var approve = new ApprovalDecision(ApprovalDecision.Kind.APPROVE, "lena", ApprovalAccess.REVIEWER, T0, null);
        PendingCall approved = queued.withDecision(approve, ApprovalStatus.RELEASED);
        queue.replace(queued, approved);
        var other = new ApprovalDecision(ApprovalDecision.Kind.REJECT, "omar", ApprovalAccess.REVIEWER, T0, "no");

        assertThatIllegalArgumentException().isThrownBy(() -> queue.replace(approved,
                new PendingCall(approved.approvalId(), approved.call(), approved.deferral(), approved.deferredAt(),
                        approved.expiresAt(), ApprovalStatus.REJECTED, approved.version() + 1, List.of(other), null)));
    }

    @Test
    void missingTablesFailWithTheFix() {
        var empty = new H2();
        var unready = new JdbcApprovalQueue(empty);

        assertThatThrownBy(unready::verifySchema).isInstanceOf(JdbcStoreException.class)
                .hasMessageContaining("Dialect.ddl()");
        assertThatThrownBy(() -> unready.get("refunds:c2")).isInstanceOf(JdbcStoreException.class);
    }

    /** Fifty reviewers on fifty connections approve one call: the database releases it once. */
    @Test
    void parallelApprovalsReleaseExactlyOnce() throws Exception {
        queue.add(refund("refunds:c2", T0));
        var approvals = new ArrayList<Callable<Boolean>>();
        for (int reviewer = 0; reviewer < 50; reviewer++) {
            String reviewerId = "reviewer-" + reviewer;
            approvals.add(() -> {
                PendingCall seen = queue.get("refunds:c2").orElseThrow();
                var decision = new ApprovalDecision(ApprovalDecision.Kind.APPROVE, reviewerId, ApprovalAccess.REVIEWER, T0, null);
                return seen.status() == ApprovalStatus.PENDING
                        && queue.replace(seen, seen.withDecision(decision, ApprovalStatus.RELEASED));
            });
        }

        int released = 0;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Boolean> outcome : executor.invokeAll(approvals)) {
                released += outcome.get() ? 1 : 0;
            }
        }

        assertThat(released).isEqualTo(1);
        assertThat(queue.get("refunds:c2").orElseThrow().decisions()).hasSize(1);
    }

    @Test
    void pendingListsOnlyUndecidedCallsOldestFirst() {
        queue.add(refund("refunds:c3", T0.plusSeconds(60)));
        queue.add(refund("refunds:c2", T0));
        queue.add(refund("refunds:c4", T0.plusSeconds(120)));
        move("refunds:c4", ApprovalStatus.REJECTED);

        assertThat(queue.pending()).extracting(PendingCall::approvalId).containsExactly("refunds:c2", "refunds:c3");
    }

    @Test
    void findsAPageOfCallsByStatusAndTimeNewestFirst() {
        for (int call = 1; call <= 5; call++) {
            queue.add(refund("refunds:c" + call, T0.plusSeconds(call * 60L)));
        }
        move("refunds:c1", ApprovalStatus.REJECTED);
        move("refunds:c2", ApprovalStatus.EXPIRED);
        move("refunds:c4", ApprovalStatus.REJECTED);
        var decided = java.util.EnumSet.of(ApprovalStatus.REJECTED, ApprovalStatus.EXPIRED);

        assertThat(queue.find(decided, T0, 10, 0)).extracting(PendingCall::approvalId)
                .containsExactly("refunds:c4", "refunds:c2", "refunds:c1");
        assertThat(queue.find(decided, T0, 2, 1)).extracting(PendingCall::approvalId)
                .containsExactly("refunds:c2", "refunds:c1");
        assertThat(queue.find(decided, T0.plusSeconds(120), 10, 0)).extracting(PendingCall::approvalId)
                .containsExactly("refunds:c4", "refunds:c2");
        assertThat(queue.find(java.util.Set.of(ApprovalStatus.PENDING), T0, 10, 0)).extracting(PendingCall::approvalId)
                .containsExactly("refunds:c5", "refunds:c3");
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

    private void move(String approvalId, ApprovalStatus status) {
        PendingCall seen = queue.get(approvalId).orElseThrow();
        assertThat(queue.replace(seen, seen.withStatus(status))).isTrue();
    }

    /** Marks what it seals, so a test can see which values were sealed. */
    private static final class TaggingCodec implements ValueCodec {

        final List<String> sealed = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public String seal(String plaintext, String context) {
            sealed.add(plaintext);
            return "sealed:" + context + ":" + plaintext;
        }

        @Override
        public String open(String value, String context) {
            String prefix = "sealed:" + context + ":";
            if (!value.startsWith(prefix)) {
                throw new IllegalArgumentException("sealed for another row");
            }
            return value.substring(prefix.length());
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
