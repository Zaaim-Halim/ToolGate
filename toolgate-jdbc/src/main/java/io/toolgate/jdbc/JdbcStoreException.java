package io.toolgate.jdbc;

import java.io.Serial;

/** The database failed, or holds a row ToolGate cannot read. ToolGate fails closed around it. */
public final class JdbcStoreException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what failed
     * @param cause   the underlying failure
     */
    public JdbcStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
