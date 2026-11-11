package io.github.radeklesniewski.dq.json;

import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.record.RecordReadException;
import io.github.radeklesniewski.dq.record.RecordSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;

/**
 * Streaming {@link RecordSource}s over JSON input. Records are parsed one at a time, so memory doesn't depend on
 * input size.
 *
 * <p>Two formats are supported:
 * <ul>
 *   <li>{@link #array(InputStream)}: a single JSON array of objects, e.g. an HTTP request body. An element that
 *       isn't an object is skipped and reported as unreadable. A <em>syntax</em> error can't be recovered from
 *       (the parser loses its place), so it aborts the run with {@link JsonSourceException}.</li>
 *   <li>{@link #lines(InputStream)}: newline-delimited JSON (NDJSON), one object per line, typical for batch files.
 *       Each line is parsed on its own, so even a syntax error only makes that one line unreadable.
 *       Blank lines are ignored.</li>
 * </ul>
 *
 * <p>The returned sources own the stream and close it in {@link RecordSource#close()}. Input is read as UTF-8.
 * Error messages give positions, never field values.
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
        return new ArraySource(Json.MAPPER.createParser(Objects.requireNonNull(in, "in")));
    }

    /**
     * Reads newline-delimited JSON, one record object per line.
     *
     * @param in the input; closed when the source is closed
     * @return a lazy source
     */
    public static RecordSource lines(InputStream in) {
        Objects.requireNonNull(in, "in");
        return new LinesSource(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
    }

    private static final class ArraySource implements RecordSource {
        private final JsonParser parser;
        private boolean started;
        private boolean finished;
        private long position;

        ArraySource(JsonParser parser) {
            this.parser = parser;
        }

        @Override
        public Optional<DataRecord> next() {
            if (finished) {
                return Optional.empty();
            }
            try {
                if (!started) {
                    started = true;
                    JsonToken first = parser.nextToken();
                    if (first == null) {
                        finished = true;
                        return Optional.empty(); // empty input: no records
                    }
                    if (first != JsonToken.START_ARRAY) {
                        throw new JsonSourceException("Expected a JSON array of records but found " + first
                                + " at " + location(), null);
                    }
                }
                JsonToken token = parser.nextToken();
                if (token == JsonToken.END_ARRAY) {
                    finished = true;
                    return Optional.empty();
                }
                if (token == null) {
                    throw new JsonSourceException("Input ended inside the records array at " + location(), null);
                }
                long element = position++;
                if (token == JsonToken.START_OBJECT) {
                    JsonNode node = Json.ELEMENT_READER.readTree(parser);
                    return Optional.of(JsonDataRecord.of(node));
                }
                parser.skipChildren();
                throw new RecordReadException(
                        "Array element " + element + " is " + describe(token) + ", expected a JSON object", null);
            } catch (JacksonException e) {
                finished = true;
                throw new JsonSourceException("Malformed JSON at " + location() + ": records can't be read further",
                        e);
            }
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

    private static final class LinesSource implements RecordSource {
        private final BufferedReader reader;
        private long lineNumber;

        LinesSource(BufferedReader reader) {
            this.reader = reader;
        }

        @Override
        public Optional<DataRecord> next() {
            String line;
            do {
                try {
                    line = reader.readLine();
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to read NDJSON input", e);
                }
                if (line == null) {
                    return Optional.empty();
                }
                lineNumber++;
            } while (line.isBlank());

            JsonNode node;
            try {
                node = Json.MAPPER.readTree(line);
            } catch (JacksonException e) {
                throw new RecordReadException("Line " + lineNumber + " is not valid JSON", e);
            }
            if (node == null || !node.isObject()) {
                throw new RecordReadException("Line " + lineNumber + " is "
                        + (node == null ? "empty" : node.getNodeType()) + ", expected a JSON object", null);
            }
            return Optional.of(JsonDataRecord.of(node));
        }

        @Override
        public void close() {
            try {
                reader.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static String describe(JsonToken token) {
        return switch (token) {
            case START_ARRAY -> "an array";
            case VALUE_STRING -> "a string";
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> "a number";
            case VALUE_TRUE, VALUE_FALSE -> "a boolean";
            case VALUE_NULL -> "null";
            default -> token.name();
        };
    }
}
