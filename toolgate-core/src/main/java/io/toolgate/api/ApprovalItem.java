package io.toolgate.api;

import io.toolgate.spi.PendingCall;
import java.util.Objects;

/**
 * A deferred call as one viewer sees it, from {@link ToolGate#pending(Principal)}.
 *
 * @param call      the call, with its real arguments
 * @param access    why the viewer sees it
 * @param mayDecide whether {@link ToolGate#approve} and {@link ToolGate#reject} would accept this viewer
 */
public record ApprovalItem(PendingCall call, ApprovalAccess access, boolean mayDecide) {

    /** Requires every component. */
    public ApprovalItem {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(access, "access");
    }
}
