package io.github.radeklesniewski.dq;

import io.github.radeklesniewski.dq.select.RuleFilter;
import java.util.Objects;

/**
 * Per-run settings chosen by the caller.
 *
 * <p>A class with a builder rather than a record, so options can be added in later versions without breaking
 * callers. Immutable and thread-safe.
 */
public final class RunOptions {

    /** Default number of records read from the source per batch. */
    public static final int DEFAULT_BATCH_SIZE = 1_000;

    private static final RunOptions DEFAULTS = builder().build();

    private final int batchSize;
    private final RuleFilter ruleFilter;
    private final boolean emitOutOfScope;
    private final ExecutionMode executionMode;
    private final int parallelism;
    private final int maxInFlightBatches;

    private RunOptions(Builder builder) {
        this.batchSize = builder.batchSize;
        this.ruleFilter = builder.ruleFilter;
        this.emitOutOfScope = builder.emitOutOfScope;
        this.executionMode = builder.executionMode;
        this.parallelism = builder.parallelism != null
                ? builder.parallelism
                : Runtime.getRuntime().availableProcessors();
        this.maxInFlightBatches = builder.maxInFlightBatches != null
                ? builder.maxInFlightBatches
                : 2 * parallelism;
    }

    /**
     * Returns the default options: batches of {@value #DEFAULT_BATCH_SIZE}, all rules selected, no out-of-scope
     * results, sequential execution.
     *
     * @return the default options
     */
    public static RunOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Starts building options from the defaults.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder pre-filled with these options.
     *
     * @return a builder pre-filled with these options
     */
    public Builder toBuilder() {
        return new Builder()
                .batchSize(batchSize)
                .ruleFilter(ruleFilter)
                .emitOutOfScope(emitOutOfScope)
                .executionMode(executionMode)
                .parallelism(parallelism)
                .maxInFlightBatches(maxInFlightBatches);
    }

    /**
     * Returns the batch size: how many records are read from the source before they are evaluated. Together with
     * the catalog size it bounds the engine's working memory.
     *
     * @return the batch size, at least 1
     */
    public int batchSize() {
        return batchSize;
    }

    /**
     * Returns the caller's rule selection. It can only narrow: the engine additionally requires {@code RELEASED}
     * status and per-record country scope.
     *
     * @return the caller's rule selection
     */
    public RuleFilter ruleFilter() {
        return ruleFilter;
    }

    /**
     * Returns whether a selected rule outside a record's country scope produces a {@code NOT_APPLICABLE} result with
     * {@link Provenance.Basis#OUT_OF_SCOPE} provenance, instead of no outcome at all (ADR-0007). The rule's logic
     * is never executed either way.
     *
     * @return whether out-of-scope (rule, record) pairs produce a result
     */
    public boolean emitOutOfScope() {
        return emitOutOfScope;
    }

    /**
     * Returns how the run uses threads and orders its output (default {@link ExecutionMode#SEQUENTIAL}).
     *
     * @return how the run uses threads and orders its output
     */
    public ExecutionMode executionMode() {
        return executionMode;
    }

    /**
     * Returns the number of worker threads in the parallel modes (default: available processors). Ignored in
     * {@link ExecutionMode#SEQUENTIAL}.
     *
     * @return the number of worker threads, at least 1
     */
    public int parallelism() {
        return parallelism;
    }

    /**
     * Returns the maximum number of batches read but not yet emitted in the parallel modes (default
     * {@code 2 × parallelism}). Reading pauses when the limit is reached. This bounds memory to roughly
     * {@code maxInFlightBatches × batchSize} records and their outcomes. Ignored in {@link ExecutionMode#SEQUENTIAL}.
     *
     * @return the in-flight batch limit, at least 1
     */
    public int maxInFlightBatches() {
        return maxInFlightBatches;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RunOptions that
                && batchSize == that.batchSize
                && emitOutOfScope == that.emitOutOfScope
                && ruleFilter.equals(that.ruleFilter)
                && executionMode == that.executionMode
                && parallelism == that.parallelism
                && maxInFlightBatches == that.maxInFlightBatches;
    }

    @Override
    public int hashCode() {
        return Objects.hash(batchSize, ruleFilter, emitOutOfScope, executionMode, parallelism, maxInFlightBatches);
    }

    @Override
    public String toString() {
        return "RunOptions[batchSize=" + batchSize + ", ruleFilter=" + ruleFilter
                + ", emitOutOfScope=" + emitOutOfScope + ", executionMode=" + executionMode
                + ", parallelism=" + parallelism + ", maxInFlightBatches=" + maxInFlightBatches + "]";
    }

    /** Builder for {@link RunOptions}. Not thread-safe. */
    public static final class Builder {

        private int batchSize = DEFAULT_BATCH_SIZE;
        private RuleFilter ruleFilter = RuleFilter.all();
        private boolean emitOutOfScope;
        private ExecutionMode executionMode = ExecutionMode.SEQUENTIAL;
        private Integer parallelism;
        private Integer maxInFlightBatches;

        private Builder() {
        }

        /**
         * Sets the batch size.
         *
         * @param batchSize records per batch, at least 1
         * @return this builder
         * @throws IllegalArgumentException if {@code batchSize < 1}
         */
        public Builder batchSize(int batchSize) {
            this.batchSize = requirePositive("batchSize", batchSize);
            return this;
        }

        /**
         * Restricts the run to the rules {@code filter} selects (default {@link RuleFilter#all()}).
         *
         * @param filter the selection; compose several with {@link RuleFilter#and}
         * @return this builder
         */
        public Builder ruleFilter(RuleFilter filter) {
            this.ruleFilter = Objects.requireNonNull(filter, "ruleFilter");
            return this;
        }

        /**
         * Sets whether out-of-scope (rule, record) pairs produce {@code NOT_APPLICABLE} results (default {@code false}).
         *
         * @param emitOutOfScope {@code true} to emit them
         * @return this builder
         * @see RunOptions#emitOutOfScope()
         */
        public Builder emitOutOfScope(boolean emitOutOfScope) {
            this.emitOutOfScope = emitOutOfScope;
            return this;
        }

        /**
         * Sets the execution mode (default {@link ExecutionMode#SEQUENTIAL}).
         *
         * @param executionMode the mode
         * @return this builder
         */
        public Builder executionMode(ExecutionMode executionMode) {
            this.executionMode = Objects.requireNonNull(executionMode, "executionMode");
            return this;
        }

        /**
         * Sets the worker thread count for the parallel modes (default: available processors).
         *
         * @param parallelism at least 1
         * @return this builder
         */
        public Builder parallelism(int parallelism) {
            this.parallelism = requirePositive("parallelism", parallelism);
            return this;
        }

        /**
         * Sets the in-flight batch limit for the parallel modes (default {@code 2 × parallelism}).
         *
         * @param maxInFlightBatches at least 1
         * @return this builder
         */
        public Builder maxInFlightBatches(int maxInFlightBatches) {
            this.maxInFlightBatches = requirePositive("maxInFlightBatches", maxInFlightBatches);
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the immutable options
         */
        public RunOptions build() {
            return new RunOptions(this);
        }

        private static int requirePositive(String name, int value) {
            if (value < 1) {
                throw new IllegalArgumentException(name + " must be >= 1, got " + value);
            }
            return value;
        }
    }
}
