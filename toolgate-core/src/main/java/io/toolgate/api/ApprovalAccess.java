package io.toolgate.api;

/**
 * Why a principal may see a deferred call, strongest first: each viewer gets the first that applies.
 * What each may decide is in {@link ApprovalItem#mayDecide()}.
 */
public enum ApprovalAccess {

    /** Has one of the policy's super admin roles: every agent, every tenant; never their own call. */
    SUPER_ADMIN,
    /** Has one of the agent's reviewer roles, in the call's tenant; never their own call. */
    REVIEWER,
    /** Has one of the policy's auditor roles: sees every call, decides none. */
    AUDITOR,
    /** Is the call's own principal: sees it, and decides it only to confirm it in an interactive agent. */
    REQUESTER
}
