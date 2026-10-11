package io.toolgate.api;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Which queued calls a history screen asks for, newest first.
 *
 * @param statuses the statuses to include; not empty
 * @param since    only calls deferred at or after this time
 * @param limit    at most this many calls, from 1 to {@value #MAX_LIMIT}
 */
public record ApprovalQuery(Set<ApprovalStatus> statuses, Instant since, int limit) {

    /** The most calls one query returns, so a screen never loads a whole queue. */
    public static final int MAX_LIMIT = 500;

    /** Requires statuses, a time and a limit in range, and copies the statuses. */
    public ApprovalQuery {
        Objects.requireNonNull(since, "since");
        if (statuses.isEmpty()) {
            throw new IllegalArgumentException("a query needs at least one status");
        }
        statuses = Set.copyOf(EnumSet.copyOf(statuses));
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be from 1 to " + MAX_LIMIT);
        }
    }

    /**
     * Every decided call deferred since a time: everything but {@link ApprovalStatus#PENDING}.
     *
     * @param since only calls deferred at or after this time
     * @param limit at most this many calls
     * @return the query
     */
    public static ApprovalQuery decidedSince(Instant since, int limit) {
        return new ApprovalQuery(EnumSet.complementOf(EnumSet.of(ApprovalStatus.PENDING)), since, limit);
    }
}
