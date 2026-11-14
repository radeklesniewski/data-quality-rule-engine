package io.github.radeklesniewski.dq.json;

import io.github.radeklesniewski.dq.FieldRead;
import io.github.radeklesniewski.dq.Outcome;
import io.github.radeklesniewski.dq.Provenance;
import io.github.radeklesniewski.dq.RecordError;
import io.github.radeklesniewski.dq.Result;
import io.github.radeklesniewski.dq.RuleError;
import io.github.radeklesniewski.dq.RunSummary;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JsonGenerator;

/**
 * The JSON shape of outcomes and run summaries (ADR-0010), independent of how they're framed in the output. Every
 * outcome has a {@code "type"}: {@code "result"}, {@code "ruleError"} or {@code "recordError"}. A field read is
 * {@code {"path": ..., "value": ...}}, or {@code {"path": ..., "absent": true}} for a missing field. Exceptions are
 * written as type and message, never with a stack trace.
 */
final class OutcomeJson {

    private OutcomeJson() {
    }

    /** Writes one outcome as a JSON object. */
    static void write(JsonGenerator json, Outcome outcome) {
        switch (outcome) {
            case Result result -> writeResult(json, result);
            case RuleError error -> writeRuleError(json, error);
            case RecordError error -> writeRecordError(json, error);
        }
    }

    /** Writes the summary's properties into an object the caller has opened. */
    static void writeSummaryFields(JsonGenerator json, RunSummary summary) {
        json.writeNumberProperty("rulesSelected", summary.rulesSelected());
        json.writeNumberProperty("recordsRead", summary.recordsRead());
        json.writeNumberProperty("batches", summary.batches());
        json.writeNumberProperty("resultCount", summary.resultCount());
        json.writeNumberProperty("errorCount", summary.errorCount());
        json.writeNumberProperty("ruleErrorCount", summary.ruleErrorCount());
        json.writeNumberProperty("recordErrorCount", summary.recordErrorCount());
        writeCounts(json, "resultsByDecision", summary.resultsByDecision());
        writeCounts(json, "resultsBySeverity", summary.resultsBySeverity());
    }

    private static void writeResult(JsonGenerator json, Result result) {
        writeOutcomeStart(json, "result", result.recordIndex());
        json.writeStringProperty("recordId", result.recordId());
        json.writeStringProperty("ruleId", result.ruleId());
        json.writeStringProperty("decision", result.decision().name());
        json.writeStringProperty("severity", result.severity().name());
        writeProvenance(json, result.provenance());
        json.writeEndObject();
    }

    private static void writeProvenance(JsonGenerator json, Provenance provenance) {
        json.writeObjectPropertyStart("provenance");
        json.writeStringProperty("basis", provenance.basis().name());
        writeFieldsRead(json, provenance.fieldsRead());
        if (provenance.computedValue().isPresent()) {
            json.writeStringProperty("computedValue", provenance.computedValue().get());
            json.writeBooleanProperty("defaultDecision", provenance.defaultDecision());
        }
        json.writeEndObject();
    }

    private static void writeRuleError(JsonGenerator json, RuleError error) {
        writeOutcomeStart(json, "ruleError", error.recordIndex());
        json.writeStringProperty("recordId", error.recordId());
        json.writeStringProperty("ruleId", error.ruleId());
        json.writeStringProperty("errorType", error.errorType());
        json.writeStringProperty("message", error.message());
        writeFieldsRead(json, error.fieldsRead());
        json.writeEndObject();
    }

    private static void writeRecordError(JsonGenerator json, RecordError error) {
        writeOutcomeStart(json, "recordError", error.recordIndex());
        if (error.recordId().isPresent()) {
            json.writeStringProperty("recordId", error.recordId().get());
        }
        json.writeStringProperty("errorType", error.errorType());
        json.writeStringProperty("message", error.message());
        json.writeEndObject();
    }

    /** Opens an outcome object with the properties every outcome starts with. The caller closes it. */
    private static void writeOutcomeStart(JsonGenerator json, String type, long recordIndex) {
        json.writeStartObject();
        json.writeStringProperty("type", type);
        json.writeNumberProperty("recordIndex", recordIndex);
    }

    private static void writeFieldsRead(JsonGenerator json, List<FieldRead> fieldsRead) {
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

    private static void writeCounts(JsonGenerator json, String name, Map<? extends Enum<?>, Long> counts) {
        json.writeObjectPropertyStart(name);
        counts.forEach((key, count) -> json.writeNumberProperty(key.name(), count));
        json.writeEndObject();
    }
}
