package io.toolgate.springai;

import io.toolgate.api.ToolGate;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;

/**
 * Keeps the approval queue moving on a schedule: sweeps it, so expired calls expire and lost runs become
 * {@code UNKNOWN}, then runs the released calls this instance can run, such as calls approved from
 * another process. On by default; {@code toolgate.approvals.executor.enabled=false} turns it off.
 */
public final class ToolGateApprovalExecutor implements SmartLifecycle {

    private static final System.Logger LOG = System.getLogger(ToolGateApprovalExecutor.class.getName());

    private final ToolGate gate;
    private final ToolGateApprovals approvals;
    private final Duration interval;
    private ScheduledExecutorService scheduler;

    ToolGateApprovalExecutor(ToolGate gate, ToolGateApprovals approvals, Duration interval) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("toolgate.approvals.executor.interval must be positive");
        }
        this.interval = interval;
    }

    /** One round: sweep, then run what was approved. Also what the schedule calls. */
    void runOnce() {
        try {
            gate.sweep();
            approvals.runApproved();
        } catch (RuntimeException failure) {
            // The next round tries again; a call claimed in this one is recorded or becomes UNKNOWN.
            LOG.log(System.Logger.Level.WARNING, "approval executor round failed", failure);
        }
    }

    @Override
    public synchronized void start() {
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("toolgate-approvals")
                    .daemon(true).factory());
            scheduler.scheduleWithFixedDelay(this::runOnce, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return scheduler != null;
    }
}
