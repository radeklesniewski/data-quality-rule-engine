package io.github.radeklesniewski.dq;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An {@link OutcomeSink} that keeps every outcome in memory.
 *
 * <p><strong>Memory grows with the input.</strong> Use it for tests and small, bounded batches (such as one HTTP
 * request) only. For large runs, write outcomes to their destination as they arrive. Not thread-safe, which is
 * fine because the engine calls sinks from one thread at a time.
 */
public final class CollectingSink implements OutcomeSink {

    private final List<Outcome> outcomes = new ArrayList<>();
    private final List<Result> results = new ArrayList<>();
    private final List<ValidationError> errors = new ArrayList<>();

    /** Creates an empty sink. */
    public CollectingSink() {
    }

    @Override
    public void accept(Outcome outcome) {
        outcomes.add(outcome);
        switch (outcome) {
            case Result result -> results.add(result);
            case ValidationError error -> errors.add(error);
        }
    }

    /**
     * Returns every outcome in emission order.
     *
     * @return unmodifiable live view of every outcome, in emission order
     */
    public List<Outcome> outcomes() {
        return Collections.unmodifiableList(outcomes);
    }

    /**
     * Returns the results only.
     *
     * @return unmodifiable live view of the results, in emission order
     */
    public List<Result> results() {
        return Collections.unmodifiableList(results);
    }

    /**
     * Returns the errors only.
     *
     * @return unmodifiable live view of the errors, in emission order
     */
    public List<ValidationError> errors() {
        return Collections.unmodifiableList(errors);
    }
}
