package io.toolgate.approvals.web;

import io.toolgate.api.Principal;

/**
 * The principal a deferred call was made for, as it is now, so a role revoked since the deferral counts
 * when the call is approved: ToolGate re-checks the call with it.
 *
 * <p>The default returns the principal as recorded when the call was deferred, which cannot know of a
 * revoked role. An application that can look its users up declares a bean.
 */
@FunctionalInterface
public interface ToolGateRequesterDirectory {

    /**
     * The principal as it is now.
     *
     * @param asDeferred the principal as recorded with the call
     * @return the same principal, with its current roles; its ID must not change
     */
    Principal current(Principal asDeferred);
}
