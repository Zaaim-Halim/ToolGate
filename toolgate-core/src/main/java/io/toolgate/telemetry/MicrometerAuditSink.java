package io.toolgate.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.AuditEvent;
import io.toolgate.spi.AuditSink;
import java.util.Objects;

/**
 * Counts decisions in Micrometer: counter {@value #DECISIONS}, tagged with
 * <ul>
 *   <li>{@code outcome} and {@code rule}: the decision applied and the rule behind it;</li>
 *   <li>{@code computed} and {@code computedRule}: what a fully enforced policy decides, which differs
 *       in monitor mode, where {@code outcome} is always {@code allow};</li>
 *   <li>{@code agent} and {@code tool}.</li>
 * </ul>
 *
 * <p>Tags must stay few, and a model can call tools by any name it makes up, so a tool the agent's
 * policy does not list is tagged {@value #UNLISTED}. Agent IDs come from the application, never from
 * the model, and are tagged as they are. Other audit events are not counted.
 *
 * <pre>{@code
 * ToolGate.builder(policy).auditSink(new MicrometerAuditSink(registry, policy))
 * }</pre>
 */
public final class MicrometerAuditSink implements AuditSink {

    /** The counter's name. */
    public static final String DECISIONS = "toolgate.decisions";
    /** The {@code tool} tag of a tool the agent's policy does not list. */
    public static final String UNLISTED = "unlisted";

    private final MeterRegistry registry;
    private final ToolGatePolicy policy;

    /**
     * Counts into a registry.
     *
     * @param registry the registry
     * @param policy   the gate's policy, which lists the tools tagged by name
     */
    @SuppressWarnings("exports") // optional, so not re-exported: a caller has Micrometer already
    public MicrometerAuditSink(MeterRegistry registry, ToolGatePolicy policy) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public void emit(AuditEvent event) {
        if (event instanceof AuditEvent.Decided decided) {
            Counter.builder(DECISIONS)
                    .description("Tool calls ToolGate decided")
                    .tag("outcome", Outcomes.name(decided.applied()))
                    .tag("rule", decided.applied().ruleId())
                    .tag("computed", Outcomes.name(decided.computed()))
                    .tag("computedRule", decided.computed().ruleId())
                    .tag("agent", decided.agentId())
                    .tag("tool", toolTag(decided.agentId(), decided.tool()))
                    .register(registry)
                    .increment();
        }
    }

    private String toolTag(String agentId, ToolId tool) {
        boolean listed = policy.agent(agentId).map(agent -> agent.allowedTools().contains(tool)).orElse(false);
        return listed ? tool.toString() : UNLISTED;
    }
}
