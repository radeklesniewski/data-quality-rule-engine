package io.github.radeklesniewski.dq;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Receives every {@link Outcome} of a run as soon as it is produced (F5). This is how results leave the engine:
 * nothing is accumulated in the engine.
 *
 * <p>The engine calls a sink from one thread at a time, in every execution mode, so implementations don't need
 * to be thread-safe. A sink that throws aborts the run: the engine can't deliver output, so it can't continue.
 * The exception propagates from {@link ValidationEngine#run}.
 */
@FunctionalInterface
public interface OutcomeSink {

    /**
     * Accepts one outcome.
     *
     * @param outcome a result or an error
     */
    void accept(Outcome outcome);

    /**
     * Routes results and errors to separate consumers.
     *
     * @param results receives every {@link Result}
     * @param errors receives every {@link ValidationError}
     * @return the sink
     */
    static OutcomeSink of(Consumer<? super Result> results, Consumer<? super ValidationError> errors) {
        Objects.requireNonNull(results, "results");
        Objects.requireNonNull(errors, "errors");
        return outcome -> {
            switch (outcome) {
                case Result result -> results.accept(result);
                case ValidationError error -> errors.accept(error);
            }
        };
    }
}
