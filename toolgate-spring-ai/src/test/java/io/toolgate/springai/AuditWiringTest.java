package io.toolgate.springai;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.toolgate.api.ArgValue;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.AuditEvent;
import io.toolgate.spi.AuditSink;
import io.toolgate.spi.DecisionObserver;
import io.toolgate.telemetry.MicrometerAuditSink;
import io.toolgate.telemetry.MicrometerCheckTimer;
import io.toolgate.telemetry.OpenTelemetryDecisions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Which audit sinks and observers the auto-configuration gives the gate, and what stays off by default. */
class AuditWiringTest {

    // The JDK's System.Logger writes through java.util.logging here, whose console handler keeps the
    // stderr it started with, so the log is read from its own logger rather than from captured output.
    private static final Logger AUDIT_LOG = Logger.getLogger("toolgate.audit");

    private final List<String> logged = new CopyOnWriteArrayList<>();
    private final Handler recording = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            logged.add(logRecord.getMessage());
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };

    @BeforeEach
    void recordTheLog() {
        AUDIT_LOG.addHandler(recording);
    }

    @AfterEach
    void stopRecording() {
        AUDIT_LOG.removeHandler(recording);
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ToolGateAutoConfiguration.class))
            .withBean(ToolGatePolicy.class, () -> ToolGatePolicy.builder()
                    .agent("support-chat", a -> a.allow("getInvoice")).build());

    @Test
    void everyAuditSinkAndObserverBeanIsUsedAndTelemetryStaysOffByDefault() {
        runner.withUserConfiguration(TwoSinksAndAnObserver.class).run(context -> {
            ToolGate gate = context.getBean(ToolGate.class);
            gate.check(call("c1"));
            gate.close();

            TwoSinksAndAnObserver beans = context.getBean(TwoSinksAndAnObserver.class);
            assertThat(beans.first).hasSize(2).isEqualTo(beans.second);
            assertThat(beans.checks).hasSize(1);
            assertThat(context).doesNotHaveBean(MicrometerAuditSink.class)
                    .doesNotHaveBean(MicrometerCheckTimer.class)
                    .doesNotHaveBean(OpenTelemetryDecisions.class);
        });
    }

    @Test
    void theLogStaysOnWhenTheApplicationAddsASink() {
        runner.withUserConfiguration(TwoSinksAndAnObserver.class).run(context -> {
            ToolGate gate = context.getBean(ToolGate.class);
            gate.check(call("c-log-kept"));
            gate.close();
        });

        assertThat(logged).anySatisfy(line -> assertThat(line).contains("\"callId\":\"c-log-kept\""));
    }

    @Test
    void theLogCanBeTurnedOff() {
        runner.withUserConfiguration(TwoSinksAndAnObserver.class).withPropertyValues("toolgate.audit.log.enabled=false")
                .run(context -> {
                    ToolGate gate = context.getBean(ToolGate.class);
                    gate.check(call("c-log-off"));
                    gate.close();
                    assertThat(context.getBean(TwoSinksAndAnObserver.class).first).hasSize(2);
                });

        assertThat(logged).noneSatisfy(line -> assertThat(line).contains("c-log-off"));
    }

    /** Turned on, but the libraries are missing: the application starts, without the integrations. */
    @Test
    void withoutMicrometerOrOpenTelemetryOnTheClasspathTheApplicationStillStarts() {
        runner.withClassLoader(new FilteredClassLoader(MeterRegistry.class, Span.class))
                .withPropertyValues("toolgate.audit.micrometer.enabled=true", "toolgate.audit.opentelemetry.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ToolGate.class)
                            .doesNotHaveBean(MicrometerAuditSink.class)
                            .doesNotHaveBean(OpenTelemetryDecisions.class);
                    assertThat(context.getBean(ToolGate.class).check(call("c1"))).isNotNull();
                });
    }

    @Test
    void micrometerTurnedOnCountsTimesAndGaugesDroppedEvents() {
        runner.withPropertyValues("toolgate.audit.micrometer.enabled=true")
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    // Spring Boot's metrics auto-configuration binds MeterBinder beans; this test has none.
                    context.getBeansOfType(MeterBinder.class).values().forEach(binder -> binder.bindTo(registry));
                    ToolGate gate = context.getBean(ToolGate.class);
                    gate.check(call("c1"));
                    gate.close();

                    assertThat(registry.get(MicrometerAuditSink.DECISIONS).tags("outcome", "allow", "tool", "local/getInvoice")
                            .counter().count()).isEqualTo(1);
                    assertThat(registry.get(MicrometerCheckTimer.CHECK_DURATION).timer().count()).isEqualTo(1);
                    assertThat(registry.get("toolgate.audit.dropped").functionCounter().count()).isZero();
                });
    }

    @Test
    void micrometerTurnedOnWithoutARegistryStopsTheApplicationWithAReason() {
        runner.withPropertyValues("toolgate.audit.micrometer.enabled=true").run(context ->
                assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("toolgate.audit.micrometer.enabled=true needs a MeterRegistry bean"));
    }

    @Test
    void openTelemetryTurnedOnAddsTheSpanObserver() {
        runner.withPropertyValues("toolgate.audit.opentelemetry.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(OpenTelemetryDecisions.class));
    }

    private static ToolCall call(String callId) {
        return new ToolCall(callId, Principal.user("maria", Tenant.of("acme")), "support-chat", "s1",
                ToolId.local("getInvoice"), ObjectValue.of(Map.of("id", ArgValue.text("INV-118"))), Instant.now());
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoSinksAndAnObserver {

        final List<AuditEvent> first = new CopyOnWriteArrayList<>();
        final List<AuditEvent> second = new CopyOnWriteArrayList<>();
        final List<DecisionObserver.Check> checks = new CopyOnWriteArrayList<>();

        @Bean
        AuditSink firstSink() {
            return first::add;
        }

        @Bean
        AuditSink secondSink() {
            return second::add;
        }

        @Bean
        DecisionObserver observer() {
            return checks::add;
        }
    }
}
