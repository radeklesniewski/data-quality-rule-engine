package io.github.radeklesniewski.dq;

import java.util.List;
import java.util.Objects;

/**
 * One rule failed on one record: its logic threw, returned {@code null}, or read a field that was missing or had the
 * wrong type. The record's other rules, and the rest of the run, are unaffected.
 *
 * @param ruleId the failing rule
 * @param recordId the record being evaluated
 * @param recordIndex zero-based position of the record in the run's input
 * @param fieldsRead fields the rule read before failing; the last one is often the culprit
 * @param errorType fully qualified class name of the exception
 * @param message exception message, or {@code errorType} if it had none
 * @param cause the exception
 */
public record RuleError(
        String ruleId,
        String recordId,
        long recordIndex,
        List<FieldRead> fieldsRead,
        String errorType,
        String message,
        Throwable cause) implements ValidationError {

    /** Validates components and copies {@code fieldsRead}. */
    public RuleError {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(recordId, "recordId");
        fieldsRead = List.copyOf(Objects.requireNonNull(fieldsRead, "fieldsRead"));
        Objects.requireNonNull(errorType, "errorType");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(cause, "cause");
    }

    /**
     * Creates an error from the exception thrown while evaluating {@code ruleId} on a record.
     *
     * @param ruleId the failing rule
     * @param recordId the record id
     * @param recordIndex the record's position in the input
     * @param fieldsRead fields read before the failure
     * @param cause the exception
     * @return the error
     */
    public static RuleError of(String ruleId, String recordId, long recordIndex, List<FieldRead> fieldsRead,
            Throwable cause) {
        return new RuleError(ruleId, recordId, recordIndex, fieldsRead,
                cause.getClass().getName(), messageOf(cause), cause);
    }

    static String messageOf(Throwable cause) {
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getName() : message;
    }
}
