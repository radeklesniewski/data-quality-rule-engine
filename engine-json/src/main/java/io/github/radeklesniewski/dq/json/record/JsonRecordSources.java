package io.github.radeklesniewski.dq.json.record;

import io.github.radeklesniewski.dq.json.internal.Json;
import io.github.radeklesniewski.dq.record.RecordSource;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * Streaming {@link RecordSource}s over JSON input. Records are parsed one at a time, so memory doesn't depend on
 * input size.
 *
 * <p>Two formats are supported:
 * <ul>
 *   <li>{@link #array(InputStream)}: a single JSON array of objects, e.g. an HTTP request body. An element that
 *       isn't an object is skipped and reported as unreadable. A <em>syntax</em> error can't be recovered from
 *       (the parser loses its place), so it aborts the run with {@link JsonSourceException}. Content after the
 *       closing {@code ]} (a second array, stray text) is also a {@link JsonSourceException}, raised once all
 *       records of the array have been returned. It's never silently ignored.</li>
 *   <li>{@link #lines(InputStream)}: newline-delimited JSON (NDJSON), one object per line, typical for batch files.
 *       Each line is parsed on its own, so even a syntax error or invalid UTF-8 only makes that one line
 *       unreadable. Blank lines are ignored.</li>
 * </ul>
 *
 * <p>Input is UTF-8; a leading byte-order mark is accepted in both formats. Invalid UTF-8 is an error, never
 * silently replaced, so rules never see altered values. A failure of the underlying stream is an
 * {@link UncheckedIOException}, not a syntax error. The returned sources own the stream and close it in
 * {@link RecordSource#close()}. Error messages give positions, never field values.
 */
public final class JsonRecordSources {

    private JsonRecordSources() {
    }

    /**
     * Reads a JSON array of record objects.
     *
     * @param in the input; closed when the source is closed
     * @return a lazy source
     */
    public static RecordSource array(InputStream in) {
        return new JsonArraySource(Json.MAPPER.createParser(Objects.requireNonNull(in, "in")));
    }

    /**
     * Reads newline-delimited JSON, one record object per line.
     *
     * @param in the input; closed when the source is closed
     * @return a lazy source
     */
    public static RecordSource lines(InputStream in) {
        return new NdjsonSource(Objects.requireNonNull(in, "in"));
    }
}
