package io.toolgate.spi;

import io.toolgate.api.Decision;
import io.toolgate.api.Mode;
import io.toolgate.api.ToolCall;
import java.time.Duration;
import java.util.Objects;

/**
 * Sees each check as it ends, on the thread that asked for it, such as to annotate the tool call's
 * trace span or to time checks.
 *
 * <p>Unlike an {@link AuditSink}, an observer runs on the request path, so it must be fast and never
 * block: no I/O, no locks held across calls. An exception from an observer is counted with the dropped
 * audit events and never changes the decision. A gate in {@link Mode#OFF} checks nothing, so observers
 * are not called.
 */
@FunctionalInterface
public interface DecisionObserver {

    /**
     * Takes one finished check.
     *
     * @param check the check
     */
    void decided(Check check);

    /**
     * One finished check.
     *
     * @param call     the call that was checked
     * @param applied  the decision returned to the adapter
     * @param computed the decision a fully enforced policy makes; differs from {@code applied} in
     *                 {@link Mode#MONITOR}
     * @param mode     the policy's mode
     * @param took     how long the check took, session store included
     */
    record Check(ToolCall call, Decision applied, Decision computed, Mode mode, Duration took) {

        /** Requires every component. */
        public Check {
            Objects.requireNonNull(call, "call");
            Objects.requireNonNull(applied, "applied");
            Objects.requireNonNull(computed, "computed");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(took, "took");
        }
    }
}
