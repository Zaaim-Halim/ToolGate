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
        queue.transition("refunds/1", ApprovalStatus.PENDING, ApprovalStatus.REJECTED);

        queue.add(pending("refunds/1", T0.plusSeconds(5)));

        assertThat(queue.get("refunds/1").orElseThrow().status()).isEqualTo(ApprovalStatus.REJECTED);
    }

    @Test
    void transitionOnlyFromTheStatusSeen() {
        queue.add(pending("refunds/1", T0));

        assertThat(queue.transition("refunds/1", ApprovalStatus.PENDING, ApprovalStatus.RELEASED)).isTrue();
        assertThat(queue.transition("refunds/1", ApprovalStatus.PENDING, ApprovalStatus.RELEASED)).isFalse();
        assertThat(queue.transition("unknown", ApprovalStatus.PENDING, ApprovalStatus.RELEASED)).isFalse();
    }

    /** Case F's double click, many times over: exactly one approval wins. */
    @Test
    void parallelApprovalsReleaseExactlyOnce() throws Exception {
        queue.add(pending("refunds/1", T0));
        var approvals = new ArrayList<Callable<Boolean>>();
        for (int reviewer = 0; reviewer < 50; reviewer++) {
            approvals.add(() -> queue.transition("refunds/1", ApprovalStatus.PENDING, ApprovalStatus.RELEASED));
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
        queue.transition("refunds/3", ApprovalStatus.PENDING, ApprovalStatus.REJECTED);

        assertThat(queue.pending()).extracting(PendingCall::approvalId).containsExactly("refunds/1", "refunds/2");
    }

    @Test
    void rejectsCallsThatAreNotPendingOrExpireTooEarly() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                queue.add(pending("refunds/1", T0).withStatus(ApprovalStatus.RELEASED)));
        assertThatIllegalArgumentException().isThrownBy(() -> new PendingCall("refunds/1", call(), defer(), T0, T0,
                ApprovalStatus.PENDING));
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
