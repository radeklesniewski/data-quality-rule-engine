package io.github.radeklesniewski.dq.json;

import io.github.radeklesniewski.dq.FieldRead;
import io.github.radeklesniewski.dq.Outcome;
import io.github.radeklesniewski.dq.OutcomeSink;
import io.github.radeklesniewski.dq.Provenance;
import io.github.radeklesniewski.dq.RecordError;
import io.github.radeklesniewski.dq.Result;
import io.github.radeklesniewski.dq.RuleError;
import io.github.radeklesniewski.dq.RunSummary;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JsonGenerator;

/**
 * Writes outcomes as JSON as they arrive. It's an {@link OutcomeSink}, so memory stays constant however large
 * the run.
 *
 * <ul>
 *   <li>{@link #document(OutputStream)}: one JSON document,
 *       {@code {"outcomes": [ ... ], "summary": { ... }}}. Suits HTTP responses.</li>
 *   <li>{@link #lines(OutputStream)}: NDJSON, one outcome per line, then a final {@code {"type":"summary",...}}
 *       line. Suits files and pipes.</li>
 * </ul>
 *
 * <p>Usage: pass the writer as the sink, then call {@link #finish(RunSummary)} with the run's summary. The writer
 * owns the stream and closes it in {@link #close()}. Closing without {@code finish} (e.g. after a failed run) still
 * produces well-formed JSON, but without a summary, which tells a consumer the run didn't complete.
 *
 * <p>Each outcome has a {@code "type"}: {@code "result"}, {@code "ruleError"} or {@code "recordError"}. A field read
 * is written as {@code {"path": ..., "value": ...}}, or {@code {"path": ..., "absent": true}} for a missing field.
 * Exception stack traces are never written, only the type and message. Not thread-safe, like any sink.
 */
public final class JsonOutcomeWriter implements OutcomeSink, AutoCloseable {

    private final JsonGenerator json;
    private final Layout layout;
    private boolean finished;
    private boolean closed;

    private JsonOutcomeWriter(OutputStream out, Layout layout) {
        this.json = Json.MAPPER.createGenerator(Objects.requireNonNull(out, "out"));
        this.layout = layout;
        layout.begin(json);
    }

    /**
     * Writes one JSON document: {@code {"outcomes": [...], "summary": {...}}}.
     *
     * @param out the target; closed by {@link #close()}
     * @return the writer
     */
    public static JsonOutcomeWriter document(OutputStream out) {
        return new JsonOutcomeWriter(out, Layout.DOCUMENT);
    }

    /**
     * Writes NDJSON: one outcome per line, then a summary line.
     *
     * @param out the target; closed by {@link #close()}
     * @return the writer
     */
    public static JsonOutcomeWriter lines(OutputStream out) {
        return new JsonOutcomeWriter(out, Layout.LINES);
    }

    /**
     * Writes one outcome.
     *
     * @throws IllegalStateException if the writer is already finished, aborted or closed
     */
    @Override
    public void accept(Outcome outcome) {
        ensureWritable();
        switch (outcome) {
            case Result result -> writeResult(result);
            case RuleError error -> writeRuleError(error);
            case RecordError error -> writeRecordError(error);
        }
    }

    /**
     * Writes the run summary and completes the JSON. No more outcomes may be written afterwards.
     *
     * @param summary the run's summary
     * @throws IllegalStateException if the writer is already finished, aborted or closed
     */
    public void finish(RunSummary summary) {
        Objects.requireNonNull(summary, "summary");
        ensureWritable();
        finished = true;
        layout.end(json, () -> writeSummaryFields(summary));
        json.flush();
    }

    /**
     * Records that the run failed, and completes the JSON <em>without</em> a summary. The document gets an
     * {@code "error"} property; NDJSON gets a final {@code {"type":"error","message":...}} line. Use it when output
     * was already partly sent (e.g. an HTTP response with status 200) and the input then turned out to be broken.
     *
     * @param reason a message for the consumer; must not contain record data
     * @throws IllegalStateException if the writer is already finished, aborted or closed
     */
    public void abort(String reason) {
        Objects.requireNonNull(reason, "reason");
        ensureWritable();
        finished = true;
        layout.fail(json, reason);
        json.flush();
    }

    /**
     * Completes any open JSON structure and closes the stream. Idempotent. Without {@link #finish} the output has no
     * summary, which tells a consumer the run didn't complete.
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            json.close(); // AUTO_CLOSE_CONTENT closes open arrays and objects, see Json.MAPPER
        }
    }

    private void ensureWritable() {
        if (closed) {
            throw new IllegalStateException("Writer already closed");
        }
        if (finished) {
            throw new IllegalStateException("Writer already finished");
        }
    }

    private void writeResult(Result result) {
        Provenance provenance = result.provenance();
        writeOutcomeStart("result", result.recordIndex());
        json.writeStringProperty("recordId", result.recordId());
        json.writeStringProperty("ruleId", result.ruleId());
        json.writeStringProperty("decision", result.decision().name());
        json.writeStringProperty("severity", result.severity().name());
        json.writeObjectPropertyStart("provenance");
        json.writeStringProperty("basis", provenance.basis().name());
        writeFieldsRead(provenance.fieldsRead());
        if (provenance.computedValue().isPresent()) {
            json.writeStringProperty("computedValue", provenance.computedValue().get());
            json.writeBooleanProperty("defaultDecision", provenance.defaultDecision());
        }
        json.writeEndObject();
        json.writeEndObject();
    }

    private void writeRuleError(RuleError error) {
        writeOutcomeStart("ruleError", error.recordIndex());
        json.writeStringProperty("recordId", error.recordId());
        json.writeStringProperty("ruleId", error.ruleId());
        json.writeStringProperty("errorType", error.errorType());
        json.writeStringProperty("message", error.message());
        writeFieldsRead(error.fieldsRead());
        json.writeEndObject();
    }

    private void writeRecordError(RecordError error) {
        writeOutcomeStart("recordError", error.recordIndex());
        if (error.recordId().isPresent()) {
            json.writeStringProperty("recordId", error.recordId().get());
        }
        json.writeStringProperty("errorType", error.errorType());
        json.writeStringProperty("message", error.message());
        json.writeEndObject();
    }

    /** Opens an outcome object with the properties every outcome starts with. The caller closes it. */
    private void writeOutcomeStart(String type, long recordIndex) {
        json.writeStartObject();
        json.writeStringProperty("type", type);
        json.writeNumberProperty("recordIndex", recordIndex);
    }

    private void writeFieldsRead(List<FieldRead> fieldsRead) {
        json.writeArrayPropertyStart("fieldsRead");
        for (FieldRead read : fieldsRead) {
            json.writeStartObject();
            json.writeStringProperty("path", read.path());
            if (read.value().isPresent()) {
                json.writePOJOProperty("value", read.value().get());
            } else {
                json.writeBooleanProperty("absent", true);
            }
            json.writeEndObject();
        }
        json.writeEndArray();
    }

    private void writeSummaryFields(RunSummary summary) {
        json.writeNumberProperty("rulesSelected", summary.rulesSelected());
        json.writeNumberProperty("recordsRead", summary.recordsRead());
        json.writeNumberProperty("batches", summary.batches());
        json.writeNumberProperty("resultCount", summary.resultCount());
        json.writeNumberProperty("errorCount", summary.errorCount());
        json.writeNumberProperty("ruleErrorCount", summary.ruleErrorCount());
        json.writeNumberProperty("recordErrorCount", summary.recordErrorCount());
        writeCounts("resultsByDecision", summary.resultsByDecision());
        writeCounts("resultsBySeverity", summary.resultsBySeverity());
    }

    private void writeCounts(String name, Map<? extends Enum<?>, Long> counts) {
        json.writeObjectPropertyStart(name);
        counts.forEach((key, count) -> json.writeNumberProperty(key.name(), count));
        json.writeEndObject();
    }

    /** Where outcomes go and how the output ends. Each layout's framing lives here, in one place. */
    private enum Layout {

        /** {@code {"outcomes": [...], "summary": {...}}}, or {@code "error"} instead of {@code "summary"}. */
        DOCUMENT {
            @Override
            void begin(JsonGenerator json) {
                json.writeStartObject();
                json.writeArrayPropertyStart("outcomes");
            }

            @Override
            void end(JsonGenerator json, Runnable summaryFields) {
                json.writeEndArray();
                json.writeObjectPropertyStart("summary");
                summaryFields.run();
                json.writeEndObject();
                json.writeEndObject();
            }

            @Override
            void fail(JsonGenerator json, String reason) {
                json.writeEndArray();
                json.writeStringProperty("error", reason);
                json.writeEndObject();
            }
        },

        /** One outcome per line, then a {@code "summary"} or {@code "error"} line. */
        LINES {
            @Override
            void begin(JsonGenerator json) {
                // Nothing frames NDJSON.
            }

            @Override
            void end(JsonGenerator json, Runnable summaryFields) {
                json.writeStartObject();
                json.writeStringProperty("type", "summary");
                summaryFields.run();
                json.writeEndObject();
            }

            @Override
            void fail(JsonGenerator json, String reason) {
                json.writeStartObject();
                json.writeStringProperty("type", "error");
                json.writeStringProperty("message", reason);
                json.writeEndObject();
            }
        };

        abstract void begin(JsonGenerator json);

        /** Writes the summary and closes the output's framing. */
        abstract void end(JsonGenerator json, Runnable summaryFields);

        /** Writes the failure marker and closes the output's framing. */
        abstract void fail(JsonGenerator json, String reason);
    }
}
