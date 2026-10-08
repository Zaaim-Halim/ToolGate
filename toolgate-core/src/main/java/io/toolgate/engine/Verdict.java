package io.toolgate.engine;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.ToolId;
import java.util.Objects;

/** What one evaluator concluded about one call. The engine combines verdicts into a decision. */
public sealed interface Verdict
        permits Verdict.Pass, Verdict.Deny, Verdict.Defer, Verdict.Downgrade, Verdict.Terminate {

    /** The evaluator has no objection. */
    Pass PASS = new Pass();

    /** No objection. */
    record Pass() implements Verdict {}

    /**
     * The call must not run.
     *
     * @param reason why, for the audit trail
     * @param origin whether a downgrade may soften it
     */
    record Deny(String reason, Origin origin) implements Verdict {
        public Deny {
            requireNonBlank(reason, "reason");
            Objects.requireNonNull(origin, "origin");
        }
    }

    /**
     * The call must wait for review.
     *
     * @param reason why, for the audit trail and the reviewer
     * @param origin whether a downgrade may soften it
     */
    record Defer(String reason, Origin origin) implements Verdict {
        public Defer {
            requireNonBlank(reason, "reason");
            Objects.requireNonNull(origin, "origin");
        }
    }

    /**
     * A safe tool should run instead.
     *
     * @param safeTool the tool to run, with the call's own arguments
     */
    record Downgrade(ToolId safeTool) implements Verdict {
        public Downgrade {
            Objects.requireNonNull(safeTool, "safeTool");
        }
    }

    /**
     * The run must end.
     *
     * @param reason why, for the audit trail
     */
    record Terminate(String reason) implements Verdict {
        public Terminate {
            requireNonBlank(reason, "reason");
        }
    }
}
