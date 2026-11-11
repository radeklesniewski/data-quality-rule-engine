package io.github.radeklesniewski.dq;

/**
 * How a run spreads work over threads, and what that means for output order (ADR-0006).
 *
 * <p>In every mode the sink is called from one thread at a time, the summary counts are identical, and memory stays
 * bounded. In the parallel modes it's bounded by {@link RunOptions#maxInFlightBatches()} × batch size.
 */
public enum ExecutionMode {

    /** Everything on the calling thread. Outcomes in input record order, then catalog order. The default. */
    SEQUENTIAL,

    /**
     * Batches are evaluated on {@link RunOptions#parallelism()} worker threads. Outcomes are emitted in exactly the
     * same order as {@link #SEQUENTIAL}. A slow batch delays the emission of later finished ones.
     */
    PARALLEL_ORDERED,

    /**
     * Batches are evaluated on {@link RunOptions#parallelism()} worker threads and emitted as soon as each finishes.
     * Order holds within a batch but not across batches. Use {@link Outcome#recordIndex()} to re-sort or correlate.
     */
    PARALLEL_UNORDERED
}
