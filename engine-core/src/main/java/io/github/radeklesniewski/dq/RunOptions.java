package io.github.radeklesniewski.dq;

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

    private RunOptions(Builder builder) {
        this.batchSize = builder.batchSize;
    }

    /**
     * Returns the default options.
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
        return new Builder().batchSize(batchSize);
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

    @Override
    public boolean equals(Object other) {
        return other instanceof RunOptions that && batchSize == that.batchSize;
    }

    @Override
    public int hashCode() {
        return Objects.hash(batchSize);
    }

    @Override
    public String toString() {
        return "RunOptions[batchSize=" + batchSize + "]";
    }

    /** Builder for {@link RunOptions}. Not thread-safe. */
    public static final class Builder {

        private int batchSize = DEFAULT_BATCH_SIZE;

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
         * Builds the options.
         *
         * @return the immutable options
         */
        public RunOptions build() {
            return new RunOptions(this);
        }
    }
}
