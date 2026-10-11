package io.toolgate.queue;

import io.toolgate.api.ApprovalStatus;
import io.toolgate.spi.ApprovalQueue;
import io.toolgate.spi.PendingCall;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps deferred calls in memory. Correct for a single instance, and lost on restart; a review
 * queue that must survive restarts needs a persistent store.
 */
public final class InMemoryApprovalQueue implements ApprovalQueue {

    private final ConcurrentMap<String, PendingCall> calls = new ConcurrentHashMap<>();

    @Override
    public void add(PendingCall call) {
        if (call.status() != ApprovalStatus.PENDING) {
            throw new IllegalArgumentException("a call enters the queue pending, not " + call.status());
        }
        calls.putIfAbsent(call.approvalId(), call);
    }

    @Override
    public Optional<PendingCall> get(String approvalId) {
        return Optional.ofNullable(calls.get(approvalId));
    }

    @Override
    public boolean replace(PendingCall expected, PendingCall next) {
        ApprovalQueue.requireNextVersion(expected, next);
        var changed = new AtomicBoolean();
        calls.computeIfPresent(expected.approvalId(), (id, current) -> {
            if (current.version() != expected.version()) {
                return current;
            }
            changed.set(true);
            return next;
        });
        return changed.get();
    }

    @Override
    public List<PendingCall> withStatus(ApprovalStatus status) {
        return calls.values().stream()
                .filter(call -> call.status() == status)
                .sorted(Comparator.comparing(PendingCall::deferredAt).thenComparing(PendingCall::approvalId))
                .toList();
    }
}
