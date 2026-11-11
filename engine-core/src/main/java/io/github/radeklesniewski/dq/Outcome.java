package io.github.radeklesniewski.dq;

/**
 * Anything a validation run emits to its {@link OutcomeSink}: a {@link Result} or a {@link ValidationError}.
 *
 * <p>Sealed, so hosts can handle every case exhaustively:
 * <pre>{@code
 * switch (outcome) {
 *     case Result r -> store(r);
 *     case RuleError e -> log(e);
 *     case RecordError e -> quarantine(e);
 * }
 * }</pre>
 */
public sealed interface Outcome permits Result, ValidationError {

    /**
     * Returns the zero-based position in the run's input of the record this outcome is about.
     *
     * @return the zero-based position in the run's input of the record this outcome is about
     */
    long recordIndex();
}
