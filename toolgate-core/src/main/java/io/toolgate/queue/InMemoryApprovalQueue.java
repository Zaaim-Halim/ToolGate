package io.toolgate.queue;

import io.toolgate.spi.ApprovalQueue;
import io.toolgate.spi.ApprovalStatus;
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
    public boolean transition(String approvalId, ApprovalStatus from, ApprovalStatus to) {
        var changed = new AtomicBoolean();
        calls.computeIfPresent(approvalId, (id, current) -> {
            if (current.status() != from) {
                return current;
            }
            changed.set(true);
            return current.withStatus(to);
        });
        return changed.get();
    }

    @Override
    public List<PendingCall> pending() {
        return calls.values().stream()
                .filter(call -> call.status() == ApprovalStatus.PENDING)
                .sorted(Comparator.comparing(PendingCall::deferredAt).thenComparing(PendingCall::approvalId))
                .toList();
    }
}
