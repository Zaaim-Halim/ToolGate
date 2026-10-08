package io.toolgate.queue.jdbc;

import java.io.Serial;

/** The database failed, or holds a row ToolGate cannot read. ToolGate denies the call it was queueing. */
public final class JdbcApprovalQueueException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what failed
     * @param cause   the underlying failure
     */
    public JdbcApprovalQueueException(String message, Throwable cause) {
        super(message, cause);
    }
}
