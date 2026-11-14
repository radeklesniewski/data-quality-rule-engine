package io.github.radeklesniewski.dq.example.http;

/**
 * A request that can't be served, thrown before any response has been sent. It carries the status and a message that
 * is safe to show the client. Server-side failures ({@code 5xx}) keep their cause for the log, and the client is only
 * told to look there.
 */
final class HttpError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    private HttpError(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    static HttpError badRequest(String message) {
        return new HttpError(400, message, null);
    }

    static HttpError payloadTooLarge(String message) {
        return new HttpError(413, message, null);
    }

    static HttpError unsupportedMediaType(String message) {
        return new HttpError(415, message, null);
    }

    /** A failure on the server's side; {@code message} is logged with {@code cause}. */
    static HttpError serverError(String message, Throwable cause) {
        return new HttpError(500, message, cause);
    }

    int status() {
        return status;
    }

    boolean isServerError() {
        return status >= 500;
    }

    /** What the client sees: the message, pointing to the server log for server-side failures. */
    String clientMessage() {
        return isServerError() ? getMessage() + "; see server log" : getMessage();
    }
}
