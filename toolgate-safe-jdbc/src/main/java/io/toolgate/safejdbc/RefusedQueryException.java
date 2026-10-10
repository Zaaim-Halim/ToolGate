package io.toolgate.safejdbc;

import java.io.Serial;
import java.sql.SQLException;

/**
 * SafeJdbc refused to run a query: it failed the pre-check, the database cannot be made read-only,
 * or a column has a type SafeJdbc does not return. Nothing was run, or nothing was returned.
 */
public final class RefusedQueryException extends SQLException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param reason why the query was refused, safe to show to the model
     */
    public RefusedQueryException(String reason) {
        super(reason);
    }
}
