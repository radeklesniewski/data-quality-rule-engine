package io.github.radeklesniewski.dq;

import java.util.Objects;
import java.util.Optional;

/**
 * A record couldn't be processed at all: the source couldn't read it, or it has no usable id or country. No rule
 * ran on it. The rest of the run is unaffected.
 *
 * @param recordIndex zero-based position of the record in the run's input; locates it even without an id
 * @param recordId the record id, if it could be determined
 * @param errorType fully qualified class name of the exception
 * @param message exception message, or {@code errorType} if it had none
 * @param cause the exception
 */
public record RecordError(
        long recordIndex,
        Optional<String> recordId,
        String errorType,
        String message,
        Throwable cause) implements ValidationError {

    /** Validates components. */
    public RecordError {
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(errorType, "errorType");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(cause, "cause");
    }

    /**
     * Creates an error from the exception that made the record unprocessable.
     *
     * @param recordIndex the record's position in the input
     * @param recordId the record id, if known
     * @param cause the exception
     * @return the error
     */
    public static RecordError of(long recordIndex, Optional<String> recordId, Throwable cause) {
        return new RecordError(recordIndex, recordId, cause.getClass().getName(), RuleError.messageOf(cause), cause);
    }
}
