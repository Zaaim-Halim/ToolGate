package io.toolgate.api;

import static io.toolgate.support.Checks.requireNonBlank;

import java.time.Instant;
import java.util.Objects;

/**
 * An executor's run of a released call.
 *
 * @param executor   who claimed it, such as an instance name
 * @param claimedAt  when
 * @param leaseUntil when the claim runs out; with no outcome by then, the call becomes
 *                   {@link ApprovalStatus#UNKNOWN}
 * @param finishedAt when the outcome was recorded; null while running
 * @param summary    a short description of the outcome; null while running
 */
public record Execution(String executor, Instant claimedAt, Instant leaseUntil, Instant finishedAt, String summary) {

    /** Requires the claim, a lease after it, and an outcome either complete or absent. */
    public Execution {
        requireNonBlank(executor, "executor");
        Objects.requireNonNull(claimedAt, "claimedAt");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (!leaseUntil.isAfter(claimedAt)) {
            throw new IllegalArgumentException("a lease must end after its claim");
        }
        if ((finishedAt == null) != (summary == null)) {
            throw new IllegalArgumentException("an outcome has both a time and a summary, or neither");
        }
    }

    /**
     * This run, finished.
     *
     * @param at      when
     * @param outcome a short description of the outcome
     * @return the finished run
     */
    public Execution finished(Instant at, String outcome) {
        return new Execution(executor, claimedAt, leaseUntil, Objects.requireNonNull(at, "at"),
                requireNonBlank(outcome, "summary"));
    }
}
