package io.toolgate.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Counts super admin decisions in a sliding window and says when they become a burst: a stolen super
 * admin account tends to act fast. At most one burst per window.
 */
public final class SuperAdminWatch {

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
     * Counts one super admin decision.
     *
     * @param at when it was made
     * @return how many decisions the window holds, if this one makes a new burst
     */
    OptionalInt decided(Instant at) {
        if (this == OFF) {
            return OptionalInt.empty();
        }
        lock.lock();
        try {
            recent.addLast(at);
            Instant windowStart = at.minus(window);
            while (!recent.getFirst().isAfter(windowStart)) {
                recent.removeFirst();
            }
            if (recent.size() > normal && (lastBurst == null || !lastBurst.isAfter(windowStart))) {
                lastBurst = at;
                return OptionalInt.of(recent.size());
            }
            return OptionalInt.empty();
        } finally {
            lock.unlock();
        }
    }
}
