package io.toolgate.springai;

import io.toolgate.jdbc.JdbcApprovalQueue;
import io.toolgate.jdbc.JdbcAuditSink;
import io.toolgate.jdbc.JdbcSessionStore;
import io.toolgate.spi.ApprovalQueue;
import io.toolgate.spi.AuditSink;
import io.toolgate.spi.SessionStore;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.context.SmartLifecycle;

/**
 * Deletes old rows from ToolGate's JDBC tables on a schedule, when the application sets
 * {@code toolgate.jdbc.retention}: audit rows recorded before the cutoff, approvals that are over and were
 * deferred before it, and sessions idle for longer than {@code toolgate.jdbc.session-retention}, which
 * defaults to the same period. Calls still waiting, approved, running or with an unknown outcome are never
 * deleted. Every instance may run it; a row deleted twice is deleted once.
 */
public final class ToolGateJdbcRetention implements SmartLifecycle {

    private static final System.Logger LOG = System.getLogger(ToolGateJdbcRetention.class.getName());
    /** Gives the application time to start before the first purge, which may be long after an outage. */
    private static final Duration FIRST_RUN_DELAY = Duration.ofMinutes(1);

    private final Supplier<List<AuditSink>> sinks;
    private final Supplier<List<ApprovalQueue>> queues;
    private final Supplier<List<SessionStore>> sessions;
    private final Duration retention;
    private final Duration sessionRetention;
    private final Duration interval;
    private final Clock clock;
    private ScheduledExecutorService scheduler;

    ToolGateJdbcRetention(Supplier<List<AuditSink>> sinks, Supplier<List<ApprovalQueue>> queues,
                          Supplier<List<SessionStore>> sessions, ToolGateProperties.Jdbc settings, Clock clock) {
        this.sinks = Objects.requireNonNull(sinks, "sinks");
        this.queues = Objects.requireNonNull(queues, "queues");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.retention = positive(settings.retention(), "toolgate.jdbc.retention");
        this.sessionRetention = positive(settings.sessionRetention() == null ? settings.retention()
                : settings.sessionRetention(), "toolgate.jdbc.session-retention");
        this.interval = positive(settings.purgeInterval(), "toolgate.jdbc.purge-interval");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** One purge of every JDBC store the application has. Also what the schedule calls. */
    void runOnce() {
        var cutoff = clock.instant().minus(retention);
        int audit = 0;
        int calls = 0;
        int idle = 0;
        try {
            for (AuditSink sink : sinks.get()) {
                if (sink instanceof JdbcAuditSink jdbc) {
                    audit += jdbc.purgeBefore(cutoff);
                }
            }
            for (ApprovalQueue queue : queues.get()) {
                if (queue instanceof JdbcApprovalQueue jdbc) {
                    calls += jdbc.purgeBefore(cutoff);
                }
            }
            for (SessionStore store : sessions.get()) {
                if (store instanceof JdbcSessionStore jdbc) {
                    idle += jdbc.purgeIdle(sessionRetention);
                }
            }
            LOG.log(System.Logger.Level.INFO, "ToolGate retention: deleted {0} audit rows, {1} finished approvals and"
                    + " {2} idle sessions", audit, calls, idle);
        } catch (RuntimeException failure) {
            // The next round tries again; what was deleted so far stays deleted.
            LOG.log(System.Logger.Level.WARNING, "ToolGate retention round failed", failure);
        }
    }

    @Override
    public synchronized void start() {
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("toolgate-retention")
                    .daemon(true).factory());
            scheduler.scheduleWithFixedDelay(this::runOnce, FIRST_RUN_DELAY.toMillis(), interval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return scheduler != null;
    }

    private static Duration positive(Duration duration, String property) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(property + " must be a positive duration, was " + duration);
        }
        return duration;
    }
}
