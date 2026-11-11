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

    private RunOptions(Builder builder) {
        this.batchSize = builder.batchSize;
        this.ruleFilter = builder.ruleFilter;
        this.emitOutOfScope = builder.emitOutOfScope;
    }

    /**
     * Returns the default options: batches of {@value #DEFAULT_BATCH_SIZE}, all rules selected, no out-of-scope results.
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
        return new Builder().batchSize(batchSize).ruleFilter(ruleFilter).emitOutOfScope(emitOutOfScope);
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

    @Override
    public boolean equals(Object other) {
        return other instanceof RunOptions that
                && batchSize == that.batchSize
                && emitOutOfScope == that.emitOutOfScope
                && ruleFilter.equals(that.ruleFilter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(batchSize, ruleFilter, emitOutOfScope);
    }

    @Override
    public String toString() {
        return "RunOptions[batchSize=" + batchSize + ", ruleFilter=" + ruleFilter
                + ", emitOutOfScope=" + emitOutOfScope + "]";
    }

    /** Builder for {@link RunOptions}. Not thread-safe. */
    public static final class Builder {

        private int batchSize = DEFAULT_BATCH_SIZE;
        private RuleFilter ruleFilter = RuleFilter.all();
        private boolean emitOutOfScope;

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
            if (batchSize < 1) {
                throw new IllegalArgumentException("batchSize must be >= 1, got " + batchSize);
            }
            this.batchSize = batchSize;
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
         * Builds the options.
         *
         * @return the immutable options
         */
        public RunOptions build() {
            return new RunOptions(this);
        }
    }
}
