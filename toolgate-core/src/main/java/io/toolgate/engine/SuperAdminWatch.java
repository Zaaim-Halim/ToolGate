package io.toolgate.engine;

import io.toolgate.api.ApprovalAccess;
import io.toolgate.spi.ApprovalQueue;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Counts super admin decisions in a sliding window and says when they become a burst: a stolen super
 * admin account tends to act fast.
 *
 * <p>With a queue that counts decisions across instances ({@link ApprovalQueue#countDecisions}), the count
 * covers every instance sharing the queue, so a burst spread over a cluster is seen. Each instance raises
 * at most one burst per window, so a burst over several instances may be raised once by each that saw it:
 * deduplicate in the SIEM if needed. A burst is never missed, since the count is read after the decision
 * is stored. Otherwise each gate counts the decisions it made itself, at most one burst per window.
 */
public final class SuperAdminWatch {

    private static final System.Logger LOG = System.getLogger(SuperAdminWatch.class.getName());

    /** A watch that never reports a burst. */
    public static final SuperAdminWatch OFF = new SuperAdminWatch(Integer.MAX_VALUE, Duration.ofSeconds(1));

    private final int normal;
    private final Duration window;
    private final Deque<Instant> recent = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private Instant lastBurst;

    /**
     * Watches for bursts.
     *
     * @param normal how many decisions are normal within the window; one more is a burst
     * @param window the window
     */
    public SuperAdminWatch(int normal, Duration window) {
        this.normal = normal;
        this.window = Objects.requireNonNull(window, "window");
    }

    /** @return the window decisions are counted in */
    Duration window() {
        return window;
    }

    /**
     * Counts one super admin decision, already stored in {@code queue}.
     *
     * @param at    when it was made
     * @param queue the gate's queue, asked for the decisions every instance made in the window
     * @return how many decisions the window holds, if this one makes a new burst
     */
    OptionalInt decided(Instant at, ApprovalQueue queue) {
        if (this == OFF) {
            return OptionalInt.empty();
        }
        lock.lock();
        try {
            Instant windowStart = at.minus(window);
            int local = remember(at, windowStart);
            boolean quiet = lastBurst == null || !lastBurst.isAfter(windowStart);
            OptionalLong shared = shared(at, windowStart, queue);
            long decisions = shared.isPresent() ? Math.max(shared.getAsLong(), local) : local;
            if (decisions > normal && quiet) {
                lastBurst = at;
                return OptionalInt.of((int) Math.min(decisions, Integer.MAX_VALUE));
            }
            return OptionalInt.empty();
        } finally {
            lock.unlock();
        }
    }

    /** Adds the decision to this gate's own count; returns how many the window holds. */
    private int remember(Instant at, Instant windowStart) {
        recent.addLast(at);
        while (!recent.getFirst().isAfter(windowStart)) {
            recent.removeFirst();
        }
        return recent.size();
    }

    /** Decisions in the window up to this one, on every instance; empty if the queue cannot count them. */
    private static OptionalLong shared(Instant at, Instant windowStart, ApprovalQueue queue) {
        try {
            return queue.countDecisions(ApprovalAccess.SUPER_ADMIN, windowStart, at);
        } catch (RuntimeException failure) {
            // The decision is already stored; a failed count must not fail it. This gate's own count stands in.
            LOG.log(System.Logger.Level.WARNING, "could not count super admin decisions across instances;"
                    + " counting this instance's only", failure);
            return OptionalLong.empty();
        }
    }
}
