package io.github.radeklesniewski.dq.json;

import io.github.radeklesniewski.dq.Outcome;
import io.github.radeklesniewski.dq.OutcomeSink;
import io.github.radeklesniewski.dq.RunSummary;
import java.io.OutputStream;
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
    private final OutputLayout layout;
    private boolean finished;
    private boolean closed;

    private JsonOutcomeWriter(OutputStream out, OutputLayout layout) {
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
        return new JsonOutcomeWriter(out, OutputLayout.DOCUMENT);
    }

    /**
     * Writes NDJSON: one outcome per line, then a summary line.
     *
     * @param out the target; closed by {@link #close()}
     * @return the writer
     */
    public static JsonOutcomeWriter lines(OutputStream out) {
        return new JsonOutcomeWriter(out, OutputLayout.LINES);
    }

    /**
     * Writes one outcome.
     *
     * @throws IllegalStateException if the writer is already finished, aborted or closed
     */
    @Override
    public void accept(Outcome outcome) {
        ensureWritable();
        OutcomeJson.write(json, outcome);
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
        layout.end(json, summary);
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
}
