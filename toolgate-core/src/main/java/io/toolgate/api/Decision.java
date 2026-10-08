package io.toolgate.api;

import static io.toolgate.support.Checks.requireNonBlank;

import java.util.Objects;

/**
 * The single result of checking one tool call.
 *
 * <p>The hierarchy is sealed so adapters switch over it exhaustively: a new outcome is a compile
 * error in every adapter rather than a silently ignored case. A decision is always the one to
 * apply; in monitor mode a call that would have been denied comes back as {@link Allow}, and only
 * the audit trail records what would have happened.
 */
public sealed interface Decision
        permits Decision.Allow, Decision.Deny, Decision.Defer, Decision.Downgrade, Decision.Terminate {

    /** The rule that produced this decision, as named in audit events. */
    String ruleId();

    /**
     * Run the tool as called.
     *
     * @param ruleId the rule that allowed it
     */
    record Allow(String ruleId) implements Decision {
        /** Requires a rule ID. */
        public Allow {
            requireNonBlank(ruleId, "ruleId");
        }
    }

    /**
     * Do not run the tool; give the model {@link #MODEL_MESSAGE} as its result.
     *
     * @param ruleId the rule that denied it
     * @param reason why, for logs and audit only; never shown to the model
     */
    record Deny(String ruleId, String reason) implements Decision {

        /**
         * The only text the model sees. It names no rule, so a model, or an attacker steering it,
         * learns nothing about what would have been allowed.
         */
        public static final String MODEL_MESSAGE = "This action is not permitted by policy.";

        /** Requires a rule ID and a reason. */
        public Deny {
            requireNonBlank(ruleId, "ruleId");
            requireNonBlank(reason, "reason");
        }
    }

    /**
     * Do not run the tool now: queue it for review and give the model {@link #MODEL_MESSAGE}.
     *
     * <p>The approval ID comes from the queue when the adapter enqueues the call, not from here.
     *
     * @param ruleId the rule that deferred it
     * @param reason why, for logs, audit and the reviewer
     */
    record Defer(String ruleId, String reason) implements Decision {

        /** The text the model sees while the call waits for review. */
        public static final String MODEL_MESSAGE = "This action is pending review.";

        /** Requires a rule ID and a reason. */
        public Defer {
            requireNonBlank(ruleId, "ruleId");
            requireNonBlank(reason, "reason");
        }
    }

    /**
     * Run a safer tool instead, such as a draft instead of a send.
     *
     * <p>The safe call has already passed its own check, so the adapter runs it without checking
     * again.
     *
     * @param ruleId   the rule that downgraded it
     * @param safeTool the tool to run instead
     * @param safeArgs the arguments for the safe tool
     */
    record Downgrade(String ruleId, ToolId safeTool, ObjectValue safeArgs) implements Decision {
        /** Requires every component. */
        public Downgrade {
            requireNonBlank(ruleId, "ruleId");
            Objects.requireNonNull(safeTool, "safeTool");
            Objects.requireNonNull(safeArgs, "safeArgs");
        }
    }

    /**
     * Do not run the tool, and end the whole agent run, for example because a budget is used up.
     *
     * <p>Each adapter ends the run in the way its framework allows, usually by throwing from the
     * point where the framework will not turn the exception into text for the model.
     *
     * @param ruleId the rule that ended the run
     * @param reason why, for logs and audit
     */
    record Terminate(String ruleId, String reason) implements Decision {
        /** Requires a rule ID and a reason. */
        public Terminate {
            requireNonBlank(ruleId, "ruleId");
            requireNonBlank(reason, "reason");
        }
    }
}
