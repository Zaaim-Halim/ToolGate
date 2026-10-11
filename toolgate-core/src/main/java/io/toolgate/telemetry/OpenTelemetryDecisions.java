package io.toolgate.telemetry;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.toolgate.api.Decision;
import io.toolgate.spi.DecisionObserver;
import java.util.Locale;

/**
 * Records each decision on the OpenTelemetry span current when the check ends, such as Spring AI's
 * tool-call observation when tracing is bridged to OpenTelemetry: attributes {@code toolgate.decision}, {@code toolgate.rule}, {@code toolgate.agent},
 * {@code toolgate.tool}, {@code toolgate.session} and {@code toolgate.mode}, plus
 * {@code toolgate.computed.decision} and {@code toolgate.computed.rule} when monitor mode applied
 * something else. A call a fully enforced policy refuses also adds the span event
 * {@value #DENIED}, with the rule and whether the refusal was enforced.
 *
 * <p>Denial reasons are left out: they can quote the argument that was denied, and traces often
 * reach less protected places than logs. The audit log has them. When several calls are checked
 * under one span, its attributes keep the last decision, and each refusal keeps its own event.
 *
 * <pre>{@code
 * ToolGate.builder(policy).decisionObserver(new OpenTelemetryDecisions())
 * }</pre>
 */
public final class OpenTelemetryDecisions implements DecisionObserver {

    /** The span event of a refused call. */
    public static final String DENIED = "toolgate.denied";

    static final AttributeKey<String> DECISION = AttributeKey.stringKey("toolgate.decision");
    static final AttributeKey<String> RULE = AttributeKey.stringKey("toolgate.rule");
    static final AttributeKey<String> AGENT = AttributeKey.stringKey("toolgate.agent");
    static final AttributeKey<String> TOOL = AttributeKey.stringKey("toolgate.tool");
    static final AttributeKey<String> SESSION = AttributeKey.stringKey("toolgate.session");
    static final AttributeKey<String> MODE = AttributeKey.stringKey("toolgate.mode");
    static final AttributeKey<String> COMPUTED_DECISION = AttributeKey.stringKey("toolgate.computed.decision");
    static final AttributeKey<String> COMPUTED_RULE = AttributeKey.stringKey("toolgate.computed.rule");
    static final AttributeKey<Boolean> ENFORCED = AttributeKey.booleanKey("toolgate.enforced");

    /** Records on whichever span is current when a check ends. */
    public OpenTelemetryDecisions() {}

    @Override
    public void decided(Check check) {
        Span span = Span.current();
        if (!span.isRecording()) {
            return;
        }
        Decision applied = check.applied();
        Decision computed = check.computed();
        span.setAttribute(DECISION, Outcomes.name(applied));
        span.setAttribute(RULE, applied.ruleId());
        span.setAttribute(AGENT, check.call().agentId());
        span.setAttribute(TOOL, check.call().tool().toString());
        span.setAttribute(SESSION, check.call().sessionId());
        span.setAttribute(MODE, check.mode().name().toLowerCase(Locale.ROOT));
        if (!computed.equals(applied)) {
            span.setAttribute(COMPUTED_DECISION, Outcomes.name(computed));
            span.setAttribute(COMPUTED_RULE, computed.ruleId());
        }
        if (Outcomes.refuses(computed)) {
            span.addEvent(DENIED, Attributes.of(RULE, computed.ruleId(), ENFORCED, Outcomes.refuses(applied)));
        }
    }
}
