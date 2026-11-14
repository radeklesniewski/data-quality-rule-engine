package io.github.radeklesniewski.dq.json.record;

import io.github.radeklesniewski.dq.json.internal.Json;
import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.record.RecordSource;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.JsonNode;

/**
 * One JSON array of record objects, read one element at a time; see {@link JsonRecordSources#array}. A syntax error
 * or content after the closing {@code ]} ends the source with {@link JsonSourceException}; a stream failure is an
 * {@link java.io.UncheckedIOException}. Either way the source is finished afterwards.
 */
final class JsonArraySource implements RecordSource {

    private enum State { BEFORE_ARRAY, IN_ARRAY, DONE }

    private final JsonParser parser;
    private State state = State.BEFORE_ARRAY;
    private long elementIndex;

    JsonArraySource(JsonParser parser) {
        this.parser = parser;
    }

    @Override
    public Optional<DataRecord> next() {
        try {
            return switch (state) {
                case BEFORE_ARRAY -> openArray() ? nextElement() : Optional.empty();
                case IN_ARRAY -> nextElement();
                case DONE -> Optional.empty();
            };
        } catch (JacksonIOException e) {
            state = State.DONE;
            throw Json.ioFailure("Failed to read JSON input", e);
        } catch (JacksonException e) {
            throw fail("Malformed JSON at " + location() + ": records can't be read further", e);
        }
    }

    /** Reads the opening bracket. Returns {@code false} for empty input, which has no records. */
    private boolean openArray() {
        JsonToken first = parser.nextToken();
        if (first == null) {
            state = State.DONE;
            return false;
        }
        if (first != JsonToken.START_ARRAY) {
            throw fail("Expected a JSON array of records but found " + first + " at " + location(), null);
        }
        state = State.IN_ARRAY;
        return true;
    }

    private Optional<DataRecord> nextElement() {
        JsonToken token = parser.nextToken();
        if (token == JsonToken.END_ARRAY) {
            requireEndOfInput();
            return Optional.empty();
        }
        if (token == null) {
            throw fail("Input ended inside the records array at " + location(), null);
        }
        long index = elementIndex++;
        JsonNode element = Json.ELEMENT_READER.readTree(parser); // also consumes a non-object element
        return Optional.of(RecordNodes.toRecord(element, "Array element", index));
    }

    /**
     * Anything but whitespace after the array (a second array, stray text) would otherwise be silently dropped. It's
     * reported instead, after every record of the array has been delivered.
     */
    private void requireEndOfInput() {
        state = State.DONE;
        if (parser.nextToken() != null) {
            throw fail("Unexpected content after the records array at " + location()
                    + ": send exactly one JSON array (or use NDJSON for multiple chunks)", null);
        }
    }

    private JsonSourceException fail(String message, Throwable cause) {
        state = State.DONE;
        return new JsonSourceException(message, cause);
    }

    private String location() {
        var location = parser.currentLocation();
        return "line " + location.getLineNr() + ", column " + location.getColumnNr();
    }

    @Override
    public void close() {
        parser.close();
    }
}
