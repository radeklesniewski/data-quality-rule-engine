package io.github.radeklesniewski.dq;

import io.github.radeklesniewski.dq.internal.FieldPaths;
import io.github.radeklesniewski.dq.internal.RecordKeys;
import io.github.radeklesniewski.dq.internal.SequentialRun;
import io.github.radeklesniewski.dq.record.RecordSource;
import io.github.radeklesniewski.dq.rule.RuleCatalog;
import java.util.Objects;

/**
 * Validates records against a rule catalog. This is the library's entry point.
 *
 * <pre>{@code
 * ValidationEngine engine = ValidationEngine.create();
 * try (RecordSource records = openSource()) {
 *     RunSummary summary = engine.run(records, catalog, RunOptions.defaults(), outcome -> write(outcome));
 * }
 * }</pre>
 *
 * <p><strong>Guarantees for every run:</strong>
 * <ul>
 *   <li>Only {@code RELEASED} rules run, and only on records within their country scope. Out-of-scope pairs
 *       produce no outcome (ADR-0007).</li>
 *   <li>Each outcome goes to the sink as soon as it is produced. The engine retains no results, so working memory
 *       is bounded by the batch size and the catalog size, not by the input size.</li>
 *   <li>Failures are isolated. A failing (rule, record) pair becomes a {@link RuleError}. An unreadable or
 *       unidentifiable record becomes a {@link RecordError}. The run carries on either way.</li>
 *   <li>Output order is deterministic: input record order, then catalog order.</li>
 * </ul>
 *
 * <p>The run aborts with an exception only on caller-side problems: an invalid catalog (checked before any record is
 * read), a sink that throws, a source that fails other than with {@code RecordReadException}, or a fatal JVM error.
 *
 * <p>Immutable and thread-safe. One instance can serve any number of concurrent runs.
 */
public final class ValidationEngine {

    /** Default name of the record field holding the record id. */
    public static final String DEFAULT_ID_FIELD = "id";
    /** Default name of the record field holding the record's country code. */
    public static final String DEFAULT_COUNTRY_FIELD = "country";

    private final RecordKeys recordKeys;

    private ValidationEngine(Builder builder) {
        this.recordKeys = new RecordKeys(builder.idField, builder.countryField);
    }

    /**
     * Returns an engine with default settings (id in {@code "id"}, country in {@code "country"}).
     *
     * @return an engine with default settings
     */
    public static ValidationEngine create() {
        return builder().build();
    }

    /**
     * Starts configuring an engine.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Runs a validation with {@link RunOptions#defaults()}.
     *
     * @param records the input; read lazily, not closed by the engine
     * @param catalog the rules; snapshotted once at the start of the run
     * @param sink receives every outcome as it is produced
     * @return totals for the run
     * @see #run(RecordSource, RuleCatalog, RunOptions, OutcomeSink)
     */
    public RunSummary run(RecordSource records, RuleCatalog catalog, OutcomeSink sink) {
        return run(records, catalog, RunOptions.defaults(), sink);
    }

    /**
     * Runs a validation: every applicable released rule on every record, each outcome streamed to {@code sink}.
     *
     * @param records the input; read lazily, not closed by the engine
     * @param catalog the rules; snapshotted once at the start of the run
     * @param options per-run settings
     * @param sink receives every outcome as it is produced, from one thread at a time
     * @return totals for the run
     * @throws IllegalArgumentException if the catalog is invalid (e.g. duplicate rule ids); nothing has been read yet
     * @throws RuntimeException whatever {@code sink} or a broken {@code records} source throws
     */
    public RunSummary run(RecordSource records, RuleCatalog catalog, RunOptions options, OutcomeSink sink) {
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");
        return new SequentialRun(catalog, options, recordKeys, sink).execute(records);
    }

    @Override
    public String toString() {
        return "ValidationEngine[idField=" + recordKeys.idField() + ", countryField=" + recordKeys.countryField() + "]";
    }

    /** Builder for {@link ValidationEngine}. Not thread-safe. */
    public static final class Builder {

        private String idField = DEFAULT_ID_FIELD;
        private String countryField = DEFAULT_COUNTRY_FIELD;

        private Builder() {
        }

        /**
         * Sets the field holding each record's id: a non-blank string or a number. Default {@code "id"}.
         *
         * @param path a dot-separated field path
         * @return this builder
         */
        public Builder idField(String path) {
            this.idField = FieldPaths.requireValid(path);
            return this;
        }

        /**
         * Sets the field holding each record's country code, used for country-scoped rules. Default {@code "country"}.
         *
         * @param path a dot-separated field path
         * @return this builder
         */
        public Builder countryField(String path) {
            this.countryField = FieldPaths.requireValid(path);
            return this;
        }

        /**
         * Builds the engine.
         *
         * @return the immutable engine
         */
        public ValidationEngine build() {
            return new ValidationEngine(this);
        }
    }
}
