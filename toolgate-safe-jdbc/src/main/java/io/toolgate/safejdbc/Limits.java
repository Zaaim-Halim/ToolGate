package io.toolgate.safejdbc;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounds on one query: how long it may run and how much of its result comes back. They bound
 * what reaches the model, not what the driver loads into memory.
 *
 * <pre>{@code
 * Limits.defaults().maxRows(20).timeout(Duration.ofSeconds(2))
 * }</pre>
 *
 * @param maxRows       rows returned at most; more are left out and the result is marked truncated
 * @param timeout       how long the database may run the query; JDBC counts whole seconds, so it is
 *                      rounded up
 * @param maxCellLength characters of text kept per value; longer text is cut and the result is
 *                      marked truncated
 */
public record Limits(int maxRows, Duration timeout, int maxCellLength) {

    private static final int DEFAULT_MAX_ROWS = 100;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final int DEFAULT_MAX_CELL_LENGTH = 1_000;

    /**
     * Checks the bounds.
     *
     * @throws IllegalArgumentException if a bound is not positive
     */
    public Limits {
        Objects.requireNonNull(timeout, "timeout");
        if (maxRows < 1) {
            throw new IllegalArgumentException("maxRows must be at least 1, was " + maxRows);
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive, was " + timeout);
        }
        if (maxCellLength < 1) {
            throw new IllegalArgumentException("maxCellLength must be at least 1, was " + maxCellLength);
        }
    }

    /**
     * 100 rows, 5 seconds, 1,000 characters per value.
     *
     * @return the default limits
     */
    public static Limits defaults() {
        return new Limits(DEFAULT_MAX_ROWS, DEFAULT_TIMEOUT, DEFAULT_MAX_CELL_LENGTH);
    }

    /**
     * These limits with another row limit.
     *
     * @param rows rows returned at most
     * @return the new limits
     */
    public Limits maxRows(int rows) {
        return new Limits(rows, timeout, maxCellLength);
    }

    /**
     * These limits with another timeout.
     *
     * @param duration how long the database may run the query
     * @return the new limits
     */
    public Limits timeout(Duration duration) {
        return new Limits(maxRows, duration, maxCellLength);
    }

    /**
     * These limits with another cell length.
     *
     * @param characters characters of text kept per value
     * @return the new limits
     */
    public Limits maxCellLength(int characters) {
        return new Limits(maxRows, timeout, characters);
    }

    int timeoutSeconds() {
        long seconds = timeout.toSeconds() + (timeout.toNanosPart() > 0 ? 1 : 0);
        return (int) Math.min(seconds, Integer.MAX_VALUE);
    }
}
