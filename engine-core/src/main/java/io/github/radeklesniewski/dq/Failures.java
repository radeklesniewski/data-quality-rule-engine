package io.github.radeklesniewski.dq;

/**
 * Describes an isolated failure for {@link RuleError} and {@link RecordError}. Runs at the fault-isolation boundary,
 * so it must never throw itself: a failure inside it would escape isolation and abort the whole run.
 */
final class Failures {

    private Failures() {
    }

    /** The fully qualified class name of {@code cause}. */
    static String type(Throwable cause) {
        return cause.getClass().getName();
    }

    /**
     * The message of {@code cause}, or its type if it has none. A {@code getMessage()} that throws (third-party rule
     * code can do anything) is reported in the message instead of propagating.
     */
    static String message(Throwable cause) {
        String message;
        try {
            message = cause.getMessage();
        } catch (Exception | StackOverflowError | LinkageError broken) { // same boundary as rule evaluation
            return type(cause) + " (its getMessage() threw " + type(broken) + ")";
        }
        return message == null || message.isBlank() ? type(cause) : message;
    }
}
