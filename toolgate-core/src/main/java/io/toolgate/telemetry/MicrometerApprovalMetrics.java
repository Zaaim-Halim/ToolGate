package io.toolgate.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.spi.ApprovalQueue;
import io.toolgate.spi.AuditEvent;
import io.toolgate.spi.AuditSink;
import io.toolgate.spi.PendingCall;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Approval metrics in Micrometer, so a dashboard shows whether reviewers keep up and an alert fires when
 * calls wait too long or runs end with an unknown outcome:
 * <ul>
 *   <li>gauge {@value #PENDING}, tagged {@code agent}: calls waiting for a decision;</li>
 *   <li>gauge {@value #OLDEST}: seconds the oldest waiting call has waited, zero when none waits;</li>
 *   <li>gauge {@value #UNKNOWN}: calls whose run has an unknown outcome, waiting to be resolved;</li>
 *   <li>counter {@value #DECIDED}, tagged {@code status} and {@code agent}: what became of deferred calls,
 *       from {@code released} and {@code rejected} to {@code executed} and {@code unknown};</li>
 *   <li>timer {@value #LATENCY}, tagged {@code status}: from deferral to release or rejection.</li>
 * </ul>
 *
 * <p>The gauges read the queue every {@code refresh} on a background thread, never when the registry is
 * scraped, so a scrape never waits on the database; they keep their last values when a read fails. The
 * counter and the timer come from audit events, so add this object to the gate as an audit sink too:
 *
 * <pre>{@code
 * var metrics = new MicrometerApprovalMetrics(registry, queue, Duration.ofSeconds(30));
 * ToolGate.builder(policy).approvalQueue(queue).auditSink(metrics)
 * }</pre>
 *
 * <p>The queue is read unfiltered, as an operator would. Only agent IDs, which come from the application,
 * become tags. {@link #close()} stops the background thread.
 */
public final class MicrometerApprovalMetrics implements AuditSink, AutoCloseable {

    /** The gauge of waiting calls. */
    public static final String PENDING = "toolgate.approvals.pending";
    /** The gauge of the oldest waiting call's age, in seconds. */
    public static final String OLDEST = "toolgate.approvals.oldest";
    /** The gauge of calls with an unknown outcome. */
    public static final String UNKNOWN = "toolgate.approvals.unknown";
    /** The counter of what became of deferred calls. */
    public static final String DECIDED = "toolgate.approvals.decided";
    /** The timer from deferral to a decision. */
    public static final String LATENCY = "toolgate.approvals.latency";

    private static final System.Logger LOG = System.getLogger(MicrometerApprovalMetrics.class.getName());

    private final MeterRegistry registry;
    private final ApprovalQueue queue;
    private final Clock clock;
    private final MultiGauge pending;
    private final AtomicLong oldestSeconds = new AtomicLong();
    private final AtomicLong unknown = new AtomicLong();
    // Agents seen waiting once keep a row at zero, so a dashboard shows zero rather than a gap.
    private final Set<String> agentsSeen = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler;

    /**
     * Registers the metrics and starts reading the queue.
     *
     * @param registry the registry
     * @param queue    the gate's approval queue
     * @param refresh  how often the gauges read the queue, such as 30 seconds
     * @throws IllegalArgumentException if {@code refresh} is not positive
     */
    @SuppressWarnings("exports") // optional, so not re-exported: a caller has Micrometer already
    public MicrometerApprovalMetrics(MeterRegistry registry, ApprovalQueue queue, Duration refresh) {
        this(registry, queue, refresh, Clock.systemUTC());
    }

    MicrometerApprovalMetrics(MeterRegistry registry, ApprovalQueue queue, Duration refresh, Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (refresh.isNegative() || refresh.isZero()) {
            throw new IllegalArgumentException("refresh must be positive, was " + refresh);
        }
        pending = MultiGauge.builder(PENDING).description("Deferred calls waiting for a decision").register(registry);
        Gauge.builder(OLDEST, oldestSeconds, AtomicLong::get).baseUnit("seconds")
                .description("How long the oldest waiting call has waited").register(registry);
        Gauge.builder(UNKNOWN, unknown, AtomicLong::get)
                .description("Approved calls whose run has an unknown outcome, waiting to be resolved").register(registry);
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("toolgate-approval-metrics")
                .daemon().factory());
        scheduler.scheduleWithFixedDelay(this::refreshQuietly, 0, refresh.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Reads the queue now and updates the gauges; the background thread does this every refresh. */
    public void refresh() {
        Instant now = clock.instant();
        List<PendingCall> waiting = queue.withStatus(ApprovalStatus.PENDING);
        var byAgent = new TreeMap<String, Long>();
        Instant oldest = null;
        for (PendingCall call : waiting) {
            byAgent.merge(call.call().agentId(), 1L, Long::sum);
            if (oldest == null || call.deferredAt().isBefore(oldest)) {
                oldest = call.deferredAt();
            }
        }
        agentsSeen.addAll(byAgent.keySet());
        var rows = new ArrayList<MultiGauge.Row<?>>();
        for (String agent : agentsSeen) {
            rows.add(MultiGauge.Row.of(Tags.of("agent", agent), byAgent.getOrDefault(agent, 0L)));
        }
        pending.register(rows, true);
        oldestSeconds.set(oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).toSeconds()));
        unknown.set(queue.withStatus(ApprovalStatus.UNKNOWN).size());
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (RuntimeException failure) {
            // A store outage must not kill the schedule: keep the last values and try again next time.
            LOG.log(System.Logger.Level.WARNING, "could not read the approval queue for metrics; keeping the last values",
                    failure);
        }
    }

    @Override
    public void emit(AuditEvent event) {
        if (!(event instanceof AuditEvent.ApprovalDecided decided)) {
            return;
        }
        String status = decided.status().name().toLowerCase(Locale.ROOT);
        Counter.builder(DECIDED).description("What became of deferred calls")
                .tag("status", status).tag("agent", decided.agentId())
                .register(registry).increment();
        if (decided.status() == ApprovalStatus.RELEASED || decided.status() == ApprovalStatus.REJECTED) {
            queue.get(decided.approvalId()).ifPresent(call -> Timer.builder(LATENCY)
                    .description("Time from deferral to a release or rejection")
                    .tag("status", status)
                    .register(registry)
                    .record(maxZero(Duration.between(call.deferredAt(), decided.time()))));
        }
    }

    /** Stops reading the queue; the meters keep their last values. */
    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private static Duration maxZero(Duration duration) {
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    /** For tests: the waiting calls by agent the last refresh saw. */
    Map<String, Double> pendingByAgent() {
        var values = new TreeMap<String, Double>();
        registry.find(PENDING).gauges().forEach(gauge -> values.put(gauge.getId().getTag("agent"), gauge.value()));
        return values;
    }
}
