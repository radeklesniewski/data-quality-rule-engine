package io.github.radeklesniewski.dq;

/**
 * A failure isolated by the engine (F3). The run carried on and the failure is reported as data.
 *
 * <ul>
 *   <li>{@link RuleError}: one rule failed on one record. The record's other rules still ran.</li>
 *   <li>{@link RecordError}: a record couldn't be read or identified. None of its rules ran.</li>
 * </ul>
 */
public sealed interface ValidationError extends Outcome permits RuleError, RecordError {

    /**
     * Returns the fully qualified class name of the underlying exception.
     *
     * @return the fully qualified class name of the underlying exception
     */
    String errorType();

    /**
     * Returns a human-readable description of the failure.
     *
     * @return the exception message, or the type if the exception had none
     */
    String message();

    /**
     * Returns the underlying exception, for stack traces in host logs.
     *
     * @return the underlying exception
     */
    Throwable cause();
}
