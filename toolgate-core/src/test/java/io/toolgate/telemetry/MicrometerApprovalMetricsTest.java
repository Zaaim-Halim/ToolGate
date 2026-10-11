package io.toolgate.telemetry;

import static io.toolgate.api.policy.ArgumentRules.atMost;
import static io.toolgate.api.policy.OnViolation.DEFER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.ArgValue;
import io.toolgate.api.Decision;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolDefinition;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.ApprovalQueue;
import io.toolgate.spi.PendingCall;
import io.toolgate.spi.ToolCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Approval gauges, counter and latency timer, on a real gate. */
class MicrometerApprovalMetricsTest {

    private static final Principal JOB = Principal.service("refund-agent", Tenant.of("acme"));
    private static final Principal LENA = Principal.user("lena", Tenant.of("acme"), "finance");
    private static final Instant T0 = Instant.parse("2026-10-11T10:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ApprovalQueue queue = ApprovalQueue.inMemory();
    private final MutableClock clock = new MutableClock(T0);

    @Test
    void showsWhatWaitsHowLongAndHowFastReviewersDecide() {
        try (var metrics = new MicrometerApprovalMetrics(registry, queue, Duration.ofHours(1), clock)) {
            // Closed by hand below, to deliver the decision's audit event before reading the counter.
            ToolGate gate = gate(metrics);
            String first = defer(gate, "c1");
            clock.advance(Duration.ofMinutes(10));
            defer(gate, "c2");
            clock.advance(Duration.ofMinutes(5));

            metrics.refresh();
            assertThat(metrics.pendingByAgent()).containsExactlyEntriesOf(Map.of("refund-agent", 2.0));
            assertThat(registry.get(MicrometerApprovalMetrics.OLDEST).gauge().value()).isEqualTo(900.0);

            gate.approve(first, LENA, JOB);
            gate.close();
            metrics.refresh();

            assertThat(metrics.pendingByAgent()).containsExactlyEntriesOf(Map.of("refund-agent", 1.0));
            assertThat(registry.get(MicrometerApprovalMetrics.DECIDED).tags("status", "released", "agent", "refund-agent")
                    .counter().count()).isEqualTo(1);
            var latency = registry.get(MicrometerApprovalMetrics.LATENCY).tags("status", "released").timer();
            assertThat(latency.count()).isEqualTo(1);
            assertThat(latency.totalTime(TimeUnit.MINUTES)).isEqualTo(15.0);
        }
    }

    /** An agent with nothing left waiting shows zero, not a gap in the dashboard. */
    @Test
    void anAgentThatStopsWaitingShowsZero() {
        try (var metrics = new MicrometerApprovalMetrics(registry, queue, Duration.ofHours(1), clock);
             ToolGate gate = gate(metrics)) {
            String approvalId = defer(gate, "c1");
            metrics.refresh();
            gate.reject(approvalId, LENA, "not this order");
            metrics.refresh();

            assertThat(metrics.pendingByAgent()).containsExactlyEntriesOf(Map.of("refund-agent", 0.0));
            assertThat(registry.get(MicrometerApprovalMetrics.OLDEST).gauge().value()).isZero();
        }
    }

    @Test
    void countsUnknownOutcomes() {
        try (var metrics = new MicrometerApprovalMetrics(registry, queue, Duration.ofHours(1), clock);
             ToolGate gate = gate(metrics)) {
            String approvalId = defer(gate, "c1");
            PendingCall waiting = queue.get(approvalId).orElseThrow();
            queue.replace(waiting, waiting.withStatus(ApprovalStatus.UNKNOWN));

            metrics.refresh();

            assertThat(registry.get(MicrometerApprovalMetrics.UNKNOWN).gauge().value()).isEqualTo(1.0);
        }
    }

    @Test
    void keepsTheLastValuesWhenTheQueueCannotBeRead() throws InterruptedException {
        var failing = new FailingQueue(queue);
        try (var metrics = new MicrometerApprovalMetrics(registry, failing, Duration.ofMillis(20), clock);
             ToolGate gate = gate(metrics)) {
            defer(gate, "c1");
            metrics.refresh();
            failing.down = true;
            Thread.sleep(100);

            assertThat(metrics.pendingByAgent()).containsExactlyEntriesOf(Map.of("refund-agent", 1.0));
        }
    }

    @Test
    void refusesARefreshThatIsNotPositive() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MicrometerApprovalMetrics(registry, queue, Duration.ZERO).close());
    }

    private ToolGate gate(MicrometerApprovalMetrics metrics) {
        var catalog = ToolCatalog.inMemory().put(ToolDefinition.of(ToolId.local("issueRefund"), "Refund an order",
                "{\"type\":\"object\",\"properties\":{\"amount\":{}}}"));
        var policy = ToolGatePolicy.builder()
                .agent("refund-agent", a -> a.allow("issueRefund").reviewers("finance")
                        .rule("issueRefund.amount", atMost("1000"), atMost("200").otherwise(DEFER)))
                .build();
        return ToolGate.builder(policy).toolCatalog(catalog).clock(clock).approvalQueue(queue).auditSink(metrics)
                .noDefaultLog().build();
    }

    private String defer(ToolGate gate, String callId) {
        var call = new ToolCall(callId, JOB, "refund-agent", "refunds", ToolId.local("issueRefund"),
                ObjectValue.of(Map.of("amount", ArgValue.number(480))), clock.instant());
        return ((Decision.Defer) gate.check(call)).approvalId();
    }

    private static final class FailingQueue implements ApprovalQueue {

        private final ApprovalQueue delegate;
        volatile boolean down;

        FailingQueue(ApprovalQueue delegate) {
            this.delegate = delegate;
        }

        @Override
        public void add(PendingCall call) {
            delegate.add(call);
        }

        @Override
        public Optional<PendingCall> get(String approvalId) {
            return delegate.get(approvalId);
        }

        @Override
        public boolean replace(PendingCall expected, PendingCall next) {
            return delegate.replace(expected, next);
        }

        @Override
        public List<PendingCall> withStatus(ApprovalStatus status) {
            if (down) {
                throw new IllegalStateException("database down");
            }
            return delegate.withStatus(status);
        }

        @Override
        public List<PendingCall> find(Set<ApprovalStatus> statuses, Instant deferredSince, int limit, int offset) {
            return delegate.find(statuses, deferredSince, limit, offset);
        }
    }

    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant start) {
            now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
