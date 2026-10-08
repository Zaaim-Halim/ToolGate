package io.toolgate.api;

import static io.toolgate.support.Checks.requireNonBlank;

/**
 * What the adapter does with a tool's output after {@link ToolGate#recordResult}: hand it to the
 * model, or replace it.
 */
public sealed interface ResultDecision permits ResultDecision.Release, ResultDecision.Withhold {

    /** Hand the output to the model. */
    Release RELEASE = new Release();

    /** Hand the output to the model. */
    record Release() implements ResultDecision {}

    /**
     * Give the model {@link #MODEL_MESSAGE} instead of the output.
     *
     * @param ruleId the result rule the output broke
     * @param reason why, for logs and audit; may quote values from the output
     */
    record Withhold(String ruleId, String reason) implements ResultDecision {

        /** The only text the model sees in place of the output. */
        public static final String MODEL_MESSAGE = "This result is not available.";

        /** Requires a rule ID and a reason. */
        public Withhold {
            requireNonBlank(ruleId, "ruleId");
            requireNonBlank(reason, "reason");
        }
    }
}
