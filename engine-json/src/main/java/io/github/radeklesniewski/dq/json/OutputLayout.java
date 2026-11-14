package io.github.radeklesniewski.dq.json;

import io.github.radeklesniewski.dq.RunSummary;
import tools.jackson.core.JsonGenerator;

/**
 * How {@link JsonOutcomeWriter}'s output is framed: where the outcomes go and how the output ends, with a summary or
 * with a failure marker. Each layout's framing lives here, in one place; the outcomes themselves are
 * {@link OutcomeJson}'s job.
 */
enum OutputLayout {

    /** {@code {"outcomes": [...], "summary": {...}}}, or {@code "error"} instead of {@code "summary"}. */
    DOCUMENT {
        @Override
        void begin(JsonGenerator json) {
            json.writeStartObject();
            json.writeArrayPropertyStart("outcomes");
        }

        @Override
        void end(JsonGenerator json, RunSummary summary) {
            json.writeEndArray();
            json.writeObjectPropertyStart("summary");
            OutcomeJson.writeSummaryFields(json, summary);
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

    /** NDJSON: one outcome per line, then a {@code "summary"} or {@code "error"} line. */
    LINES {
        @Override
        void begin(JsonGenerator json) {
            // Nothing frames NDJSON.
        }

        @Override
        void end(JsonGenerator json, RunSummary summary) {
            json.writeStartObject();
            json.writeStringProperty("type", "summary");
            OutcomeJson.writeSummaryFields(json, summary);
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

    /** Writes what comes before the first outcome. */
    abstract void begin(JsonGenerator json);

    /** Writes the summary and closes the framing. */
    abstract void end(JsonGenerator json, RunSummary summary);

    /** Writes the failure marker and closes the framing. */
    abstract void fail(JsonGenerator json, String reason);
}
