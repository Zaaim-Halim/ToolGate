package io.toolgate.api;

import static io.toolgate.support.Checks.requireNonBlank;

import java.time.Instant;
import java.util.Objects;

/**
 * One person's decision on a deferred call, kept with the call so a review screen can show who
 * decided what, when and why.
 *
 * @param kind    what they decided
 * @param by      the principal's ID
 * @param access  the access they decided under, such as {@link ApprovalAccess#SUPER_ADMIN}
 * @param at      when
 * @param comment their justification or reason; null when an approval gave none
 */
public record ApprovalDecision(Kind kind, String by, ApprovalAccess access, Instant at, String comment) {

    /** What a person can decide. */
    public enum Kind {
        /** Approve, alone or as one of the approvals the call needs. */
        APPROVE,
        /** Reject; the call never runs. */
        REJECT,
        /** Withdraw before it was decided. */
        CANCEL
    }

    /** Requires every component but the comment, which must not be blank when given. */
    public ApprovalDecision {
        Objects.requireNonNull(kind, "kind");
        requireNonBlank(by, "by");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(at, "at");
        if (comment != null) {
            requireNonBlank(comment, "comment");
        }
    }
}
