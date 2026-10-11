package io.toolgate.langchain4j;

import java.io.Serial;

/**
 * ToolGate ended the run: a budget ran out, the call had no principal or session, or a tool that was
 * not registered through ToolGate was asked for. Thrown from ToolGate's {@code beforeToolExecution}
 * hook, where LangChain4j passes it to the caller instead of turning it into text for the model.
 */
public final class ToolGateViolationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The rule that ended the run. */
    private final String ruleId;

    /**
     * Creates the exception.
     *
     * @param ruleId the rule that ended the run
     * @param reason why, for the application's logs; never shown to the model
     */
    public ToolGateViolationException(String ruleId, String reason) {
        super(ruleId + ": " + reason);
        this.ruleId = ruleId;
    }

    /**
     * The rule that ended the run.
     *
     * @return the rule ID, as in audit events
     */
    public String ruleId() {
        return ruleId;
    }
}
