package io.toolgate.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.toolgate.spi.DecisionObserver;
import java.util.Objects;

/**
 * Times checks in Micrometer: timer {@value #CHECK_DURATION}, tagged with the {@code outcome} applied
 * and the {@code agent}. The time includes reading and writing session state, so a slow session store
 * shows here.
 *
 * <pre>{@code
 * ToolGate.builder(policy).decisionObserver(new MicrometerCheckTimer(registry))
 * }</pre>
 */
public final class MicrometerCheckTimer implements DecisionObserver {

    /** The timer's name. */
    public static final String CHECK_DURATION = "toolgate.check.duration";

    private final MeterRegistry registry;

    /**
     * Times into a registry.
     *
     * @param registry the registry
     */
    @SuppressWarnings("exports") // optional, so not re-exported: a caller has Micrometer already
    public MicrometerCheckTimer(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void decided(Check check) {
        // The agent ID comes from the application, never from the model, so it is safe as a tag.
        Timer.builder(CHECK_DURATION)
                .description("Time ToolGate took to decide a tool call")
                .tag("outcome", Outcomes.name(check.applied()))
                .tag("agent", check.call().agentId())
                .register(registry)
                .record(check.took());
    }
}
