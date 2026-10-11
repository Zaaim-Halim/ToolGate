package io.toolgate.api;

import static io.toolgate.support.Checks.requireNonBlank;

import java.util.Objects;

/** The outcome of approving a deferred call. */
public sealed interface Approval
        permits Approval.Released, Approval.AwaitingConfirmation, Approval.Refused, Approval.NotPermitted,
        Approval.AlreadyDecided, Approval.Expired {

    /**
     * The call passed its re-check and is released, once. The application runs the tool now, passing
     * the idempotency key to anything that must not happen twice, then calls
     * {@link ToolGate#recordResult} with this call.
     *
     * @param call           the call to run, with the principal as it is now
     * @param idempotencyKey unique to this call, for exactly-once effects in the tool or its API
     */
    record Released(ToolCall call, String idempotencyKey) implements Approval {
        /** Requires both components. */
        public Released {
            Objects.requireNonNull(call, "call");
            requireNonBlank(idempotencyKey, "idempotencyKey");
        }
    }

    /**
     * The approval is recorded, and the call needs another before it is released: the policy asks a
     * second person to confirm a super admin's approval. The call stays pending.
     *
     * @param reason who must still approve
     */
    record AwaitingConfirmation(String reason) implements Approval {
        /** Requires the reason. */
        public AwaitingConfirmation {
            requireNonBlank(reason, "reason");
        }
    }

    /**
     * The re-check failed, because the policy, the principal's roles or the session changed since
     * the call was deferred. Final: the agent must make the call again to get a new review.
     *
     * @param reason what changed
     */
    record Refused(String reason) implements Approval {
        /** Requires the reason. */
        public Refused {
            requireNonBlank(reason, "reason");
        }
    }

    /**
     * This reviewer may not approve this call, such as a principal approving its own call in a
     * headless agent. The call stays pending for another reviewer.
     *
     * @param reason why
     */
    record NotPermitted(String reason) implements Approval {
        /** Requires the reason. */
        public NotPermitted {
            requireNonBlank(reason, "reason");
        }
    }

    /**
     * The call was already decided, for example by a second click on approve. Nothing is released.
     *
     * @param status how it was decided
     */
    record AlreadyDecided(ApprovalStatus status) implements Approval {
        /** Requires the status. */
        public AlreadyDecided {
            Objects.requireNonNull(status, "status");
        }
    }

    /** The call waited past its expiry; nothing is released. */
    record Expired() implements Approval {}
}
