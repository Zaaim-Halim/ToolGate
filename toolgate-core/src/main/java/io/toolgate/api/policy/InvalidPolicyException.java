package io.toolgate.api.policy;

import java.io.Serial;

/** A policy failed validation. Thrown when it is built, so a broken policy stops startup. */
public final class InvalidPolicyException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is wrong, naming the offending part of the policy
     */
    public InvalidPolicyException(String message) {
        super(message);
    }
}
