package io.toolgate.springai;

import java.io.Serial;

/**
 * Ends an agent run: ToolGate decided to terminate it, such as when a budget is used up, or a tool
 * call reached ToolGate without the context it needs. Thrown from the tool call, it propagates out of
 * the chat call, or as the error of a streaming response, and no further tool or model call is made.
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
