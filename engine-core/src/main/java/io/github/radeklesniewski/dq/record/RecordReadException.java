package io.github.radeklesniewski.dq.record;

import java.io.Serial;

/**
 * Thrown by a {@link RecordSource} when one record can't be read but the source can continue with the next one.
 * The engine turns it into a {@code RecordError} and keeps going.
 */
public class RecordReadException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what was wrong with the record (avoid including field values: they may be personal data)
     * @param cause the underlying failure, or {@code null}
     */
    public RecordReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
