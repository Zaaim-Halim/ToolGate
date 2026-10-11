package io.toolgate.approvals.web;

/** A request the API refuses, with the HTTP status to answer. */
final class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    private ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    static ApiException unauthenticated() {
        return new ApiException(401, "sign in to review approvals");
    }

    static ApiException forbidden(String message) {
        return new ApiException(403, message);
    }

    /** Also for calls that exist but the viewer may not see: their existence is not given away. */
    static ApiException notFound() {
        return new ApiException(404, "no such approval");
    }

    int status() {
        return status;
    }
}
