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
    private final boolean document;
    private boolean finished;
    private boolean closed;

    private JsonOutcomeWriter(OutputStream out, boolean document) {
        this.json = Json.MAPPER.createGenerator(Objects.requireNonNull(out, "out"));
        this.document = document;
        if (document) {
            json.writeStartObject();
            json.writeArrayPropertyStart("outcomes");
        }
    }

    /**
     * Writes one JSON document: {@code {"outcomes": [...], "summary": {...}}}.
     *
     * @param out the target; closed by {@link #close()}
     * @return the writer
     */
    public static JsonOutcomeWriter document(OutputStream out) {
        return new JsonOutcomeWriter(out, true);
    }

    /**
     * Writes NDJSON: one outcome per line, then a summary line.
     *
     * @param out the target; closed by {@link #close()}
     * @return the writer
     */
    public static JsonOutcomeWriter lines(OutputStream out) {
        return new JsonOutcomeWriter(out, false);
    }

    @Override
    public void accept(Outcome outcome) {
        if (finished) {
            throw new IllegalStateException("Writer already finished");
        }
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
     */
    public void finish(RunSummary summary) {
        Objects.requireNonNull(summary, "summary");
        if (finished) {
            throw new IllegalStateException("Writer already finished");
        }
        finished = true;
        if (document) {
            json.writeEndArray();
            json.writeName("summary");
            writeSummary(summary, false);
            json.writeEndObject();
        } else {
            writeSummary(summary, true);
        }
        json.flush();
    }

    /** Completes any open JSON structure and closes the stream. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (document && !finished) {
            json.writeEndArray();
            json.writeEndObject();
        }
        json.close();
    }

    private void writeResult(Result result) {
        Provenance provenance = result.provenance();
        json.writeStartObject();
        json.writeStringProperty("type", "result");
        json.writeNumberProperty("recordIndex", result.recordIndex());
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
        json.writeStartObject();
        json.writeStringProperty("type", "ruleError");
        json.writeNumberProperty("recordIndex", error.recordIndex());
        json.writeStringProperty("recordId", error.recordId());
        json.writeStringProperty("ruleId", error.ruleId());
        json.writeStringProperty("errorType", error.errorType());
        json.writeStringProperty("message", error.message());
        writeFieldsRead(error.fieldsRead());
        json.writeEndObject();
    }

    private void writeRecordError(RecordError error) {
        json.writeStartObject();
        json.writeStringProperty("type", "recordError");
        json.writeNumberProperty("recordIndex", error.recordIndex());
        if (error.recordId().isPresent()) {
            json.writeStringProperty("recordId", error.recordId().get());
        }
        json.writeStringProperty("errorType", error.errorType());
        json.writeStringProperty("message", error.message());
        json.writeEndObject();
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

    private void writeSummary(RunSummary summary, boolean typed) {
        json.writeStartObject();
        if (typed) {
            json.writeStringProperty("type", "summary");
        }
        json.writeNumberProperty("rulesSelected", summary.rulesSelected());
        json.writeNumberProperty("recordsRead", summary.recordsRead());
        json.writeNumberProperty("batches", summary.batches());
        json.writeNumberProperty("resultCount", summary.resultCount());
        json.writeNumberProperty("errorCount", summary.errorCount());
        json.writeNumberProperty("ruleErrorCount", summary.ruleErrorCount());
        json.writeNumberProperty("recordErrorCount", summary.recordErrorCount());
        writeCounts("resultsByDecision", summary.resultsByDecision());
        writeCounts("resultsBySeverity", summary.resultsBySeverity());
        json.writeEndObject();
    }

    private void writeCounts(String name, Map<? extends Enum<?>, Long> counts) {
        json.writeObjectPropertyStart(name);
        counts.forEach((key, count) -> json.writeNumberProperty(key.name(), count));
        json.writeEndObject();
    }
}
