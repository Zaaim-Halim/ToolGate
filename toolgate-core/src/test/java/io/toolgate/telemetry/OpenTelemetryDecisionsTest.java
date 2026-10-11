package io.toolgate.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.toolgate.api.ArgValue;
import io.toolgate.api.Mode;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.ToolGatePolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Decisions on the current span, through the OpenTelemetry API alone, without an SDK. */
class OpenTelemetryDecisionsTest {

    private static final Principal MARIA = Principal.user("maria", Tenant.of("acme"));

    private final RecordingSpan span = new RecordingSpan(true);

    @Test
    void anAllowedCallAnnotatesTheCurrentSpanWithoutAnEvent() {
        check(ToolGatePolicy.builder().agent("support-chat", a -> a.allow("getInvoice")).build(), "getInvoice", span);

        assertThat(span.attributes).containsAllEntriesOf(Map.of(
                OpenTelemetryDecisions.DECISION, "allow",
                OpenTelemetryDecisions.RULE, "allow-list",
                OpenTelemetryDecisions.AGENT, "support-chat",
                OpenTelemetryDecisions.TOOL, "local/getInvoice",
                OpenTelemetryDecisions.SESSION, "s1",
                OpenTelemetryDecisions.MODE, "enforce"));
        assertThat(span.attributes).doesNotContainKeys(OpenTelemetryDecisions.COMPUTED_DECISION);
        assertThat(span.events).isEmpty();
    }

    @Test
    void aDeniedCallAddsAnEventWithTheRuleButNotTheReason() {
        check(ToolGatePolicy.builder().agent("support-chat", a -> a.allow("getInvoice")).build(), "deleteEverything", span);

        assertThat(span.attributes).containsEntry(OpenTelemetryDecisions.DECISION, "deny");
        assertThat(span.events).singleElement().satisfies(event -> {
            assertThat(event.name()).isEqualTo(OpenTelemetryDecisions.DENIED);
            assertThat(event.attributes().asMap()).containsOnly(
                    Map.entry(OpenTelemetryDecisions.RULE, "allow-list"),
                    Map.entry(OpenTelemetryDecisions.ENFORCED, true));
        });
    }

    @Test
    void inMonitorModeTheSpanShowsWhatEnforcementWouldHaveRefused() {
        check(ToolGatePolicy.builder().mode(Mode.MONITOR).agent("support-chat", a -> a.allow("getInvoice")).build(),
                "deleteEverything", span);

        assertThat(span.attributes).containsAllEntriesOf(Map.of(
                OpenTelemetryDecisions.DECISION, "allow",
                OpenTelemetryDecisions.RULE, "mode.monitor",
                OpenTelemetryDecisions.COMPUTED_DECISION, "deny",
                OpenTelemetryDecisions.COMPUTED_RULE, "allow-list",
                OpenTelemetryDecisions.MODE, "monitor"));
        assertThat(span.events).singleElement().satisfies(event ->
                assertThat(event.attributes().get(OpenTelemetryDecisions.ENFORCED)).isFalse());
    }

    @Test
    void aSpanThatIsNotRecordingIsLeftAlone() {
        RecordingSpan sampledOut = new RecordingSpan(false);
        check(ToolGatePolicy.builder().agent("support-chat", a -> a.allow("getInvoice")).build(), "deleteEverything", sampledOut);

        assertThat(sampledOut.attributes).isEmpty();
        assertThat(sampledOut.events).isEmpty();
    }

    private static void check(ToolGatePolicy policy, String tool, Span current) {
        Scope scope = current.makeCurrent();
        try (ToolGate gate = ToolGate.builder(policy).noDefaultLog().decisionObserver(new OpenTelemetryDecisions()).build()) {
            gate.check(new ToolCall("c1", MARIA, "support-chat", "s1", ToolId.local(tool),
                    ObjectValue.of(Map.of("id", ArgValue.text("INV-118"))), Instant.parse("2026-10-11T10:00:00Z")));
        } finally {
            scope.close();
        }
    }

    private record Event(String name, Attributes attributes) {}

    /** Keeps what is recorded on it; an SDK's span would export the same calls. */
    private static final class RecordingSpan implements Span {

        final Map<AttributeKey<?>, Object> attributes = new ConcurrentHashMap<>();
        final List<Event> events = new CopyOnWriteArrayList<>();
        private final boolean recording;

        RecordingSpan(boolean recording) {
            this.recording = recording;
        }

        @Override
        public <T> Span setAttribute(AttributeKey<T> key, T value) {
            attributes.put(key, value);
            return this;
        }

        @Override
        public Span addEvent(String name, Attributes eventAttributes) {
            events.add(new Event(name, eventAttributes));
            return this;
        }

        @Override
        public Span addEvent(String name, Attributes eventAttributes, long timestamp, TimeUnit unit) {
            return addEvent(name, eventAttributes);
        }

        @Override
        public Span setStatus(StatusCode statusCode, String description) {
            return this;
        }

        @Override
        public Span recordException(Throwable exception, Attributes additionalAttributes) {
            return this;
        }

        @Override
        public Span updateName(String name) {
            return this;
        }

        @Override
        public void end() {}

        @Override
        public void end(long timestamp, TimeUnit unit) {}

        @Override
        public SpanContext getSpanContext() {
            return SpanContext.getInvalid();
        }

        @Override
        public boolean isRecording() {
            return recording;
        }
    }
}
