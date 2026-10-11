package io.toolgate.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.Decision;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolId;
import io.toolgate.spi.PendingCall;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class InMemoryApprovalQueueTest {

    private static final Instant T0 = Instant.parse("2026-10-08T10:02:00Z");

    private final InMemoryApprovalQueue queue = new InMemoryApprovalQueue();

    @Test
    void storesAndReturnsACall() {
        queue.add(pending("refunds/1", T0));

        assertThat(queue.get("refunds/1")).hasValueSatisfying(call -> assertThat(call.status()).isEqualTo(ApprovalStatus.PENDING));
        assertThat(queue.get("unknown")).isEmpty();
    }

    @Test
    void addingTheSameApprovalTwiceKeepsTheFirst() {
        queue.add(pending("refunds/1", T0));
        move("refunds/1", ApprovalStatus.REJECTED);

        queue.add(pending("refunds/1", T0.plusSeconds(5)));

        assertThat(queue.get("refunds/1").orElseThrow().status()).isEqualTo(ApprovalStatus.REJECTED);
    }

    @Test
    void replacesOnlyTheVersionSeen() {
        PendingCall seen = pending("refunds/1", T0);
        queue.add(seen);

        assertThat(queue.replace(seen, seen.withStatus(ApprovalStatus.RELEASED))).isTrue();
        assertThat(queue.replace(seen, seen.withStatus(ApprovalStatus.REJECTED))).isFalse();
        assertThat(queue.get("refunds/1").orElseThrow().status()).isEqualTo(ApprovalStatus.RELEASED);
        PendingCall unknown = pending("unknown", T0);
        assertThat(queue.replace(unknown, unknown.withStatus(ApprovalStatus.RELEASED))).isFalse();
    }

    @Test
    void refusesAReplacementThatIsNotTheNextVersion() {
        PendingCall seen = pending("refunds/1", T0);
        queue.add(seen);

        assertThatIllegalArgumentException().isThrownBy(() ->
                queue.replace(seen, seen.withStatus(ApprovalStatus.RELEASED).withStatus(ApprovalStatus.EXECUTING)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                queue.replace(seen, pending("refunds/2", T0).withStatus(ApprovalStatus.RELEASED)));
    }

    /** Case F's double click, many times over: exactly one approval wins. */
    @Test
    void parallelApprovalsReleaseExactlyOnce() throws Exception {
        queue.add(pending("refunds/1", T0));
        var approvals = new ArrayList<Callable<Boolean>>();
        for (int reviewer = 0; reviewer < 50; reviewer++) {
            approvals.add(() -> move("refunds/1", ApprovalStatus.RELEASED));
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
        queue.add(pending("refunds/2", T0.plusSeconds(60)));
        queue.add(pending("refunds/1", T0));
        queue.add(pending("refunds/3", T0.plusSeconds(120)));
        move("refunds/3", ApprovalStatus.REJECTED);

        assertThat(queue.pending()).extracting(PendingCall::approvalId).containsExactly("refunds/1", "refunds/2");
    }

    @Test
    void rejectsCallsThatAreNotPendingOrExpireTooEarly() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                queue.add(pending("refunds/1", T0).withStatus(ApprovalStatus.RELEASED)));
        assertThatIllegalArgumentException().isThrownBy(() -> new PendingCall("refunds/1", call(), defer(), T0, T0,
                ApprovalStatus.PENDING));
    }

    /** Moves a pending call to a status, as a reviewer would: only from the version read, and only once. */
    private boolean move(String approvalId, ApprovalStatus status) {
        PendingCall seen = queue.get(approvalId).orElseThrow();
        return seen.status() == ApprovalStatus.PENDING && queue.replace(seen, seen.withStatus(status));
    }

    private static PendingCall pending(String approvalId, Instant at) {
        return new PendingCall(approvalId, call(), defer(), at, at.plus(Duration.ofHours(24)), ApprovalStatus.PENDING);
    }

    private static Decision.Defer defer() {
        return new Decision.Defer("issueRefund.amount", "amount over 200", "refunds:1");
    }

    private static ToolCall call() {
        return new ToolCall("1", Principal.service("refund-agent", Tenant.of("acme")), "refund-agent", "refunds",
                ToolId.local("issueRefund"), ObjectValue.empty(), T0);
    }
}
