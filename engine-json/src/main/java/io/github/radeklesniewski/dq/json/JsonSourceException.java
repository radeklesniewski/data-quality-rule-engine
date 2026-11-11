package io.github.radeklesniewski.dq.json;

import java.io.Serial;

/**
 * JSON input is broken in a way that prevents reading any further records, e.g. a syntax error inside a JSON array.
 * It aborts the run. A single bad record is reported as a {@code RecordError} instead.
 */
public final class JsonSourceException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what went wrong and where
     * @param cause the parser exception, or {@code null}
     */
    public JsonSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
