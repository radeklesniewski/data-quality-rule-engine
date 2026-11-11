package io.github.radeklesniewski.dq;

import java.io.Serial;
import java.util.Objects;

/**
 * A run stopped early because its calling thread was interrupted. The engine checks for interruption between
 * batches, and while waiting for parallel workers. The thread's interrupt flag stays set.
 *
 * <p>Outcomes emitted before cancellation were delivered normally. {@link #partialSummary()} counts exactly those.
 * Records already read but not yet emitted (in-flight parallel batches) count towards
 * {@link RunSummary#recordsRead()} only.
 */
public final class RunCancelledException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient RunSummary partialSummary;

    /**
     * Creates the exception.
     *
     * @param partialSummary totals at the moment of cancellation
     * @param cause the interruption, if it surfaced as an exception; may be {@code null}
     */
    public RunCancelledException(RunSummary partialSummary, Throwable cause) {
        super("Validation run cancelled after " + partialSummary.recordsRead() + " records", cause);
        this.partialSummary = Objects.requireNonNull(partialSummary, "partialSummary");
    }

    /**
     * Returns the totals up to the point of cancellation.
     *
     * @return the totals up to the point of cancellation
     */
    public RunSummary partialSummary() {
        return partialSummary;
    }
}
