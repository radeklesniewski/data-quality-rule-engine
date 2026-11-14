package io.github.radeklesniewski.dq.json;

import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.record.RecordReadException;
import io.github.radeklesniewski.dq.record.RecordSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.JsonNode;

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
        return new ArraySource(Json.MAPPER.createParser(Objects.requireNonNull(in, "in")));
    }

    /**
     * Reads newline-delimited JSON, one record object per line.
     *
     * @param in the input; closed when the source is closed
     * @return a lazy source
     */
    public static RecordSource lines(InputStream in) {
        return new LinesSource(Objects.requireNonNull(in, "in"));
    }

    private static final class ArraySource implements RecordSource {

        private enum State { BEFORE_ARRAY, IN_ARRAY, DONE }

        private final JsonParser parser;
        private State state = State.BEFORE_ARRAY;
        private long elementIndex;

        ArraySource(JsonParser parser) {
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
            if (element == null || !element.isObject()) {
                throw notAnObject("Array element " + index, element);
            }
            return Optional.of(JsonDataRecord.of(element));
        }

        /**
         * Anything but whitespace after the array (a second array, stray text) would otherwise be silently dropped.
         * It's reported instead, after every record of the array has been delivered.
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

    /**
     * Splits the input into lines at the byte level and lets Jackson parse each line's bytes. Decoding a line
     * ourselves (e.g. with an {@code InputStreamReader}) would replace invalid UTF-8 with U+FFFD without telling
     * anyone and would reject a byte-order mark; Jackson reports the first and accepts the second.
     */
    private static final class LinesSource implements RecordSource {
        private final InputStream in;
        private final byte[] buffer = new byte[64 * 1024];
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private int position;
        private int limit;
        private long lineNumber;

        LinesSource(InputStream in) {
            this.in = in;
        }

        @Override
        public Optional<DataRecord> next() {
            byte[] bytes;
            do {
                bytes = readLine();
                if (bytes == null) {
                    return Optional.empty();
                }
            } while (isBlank(bytes));

            JsonNode node;
            try {
                node = Json.MAPPER.readTree(bytes);
            } catch (JacksonException e) {
                throw new RecordReadException("Line " + lineNumber + " is not valid JSON", e);
            }
            if (node == null || !node.isObject()) {
                throw notAnObject("Line " + lineNumber, node);
            }
            return Optional.of(JsonDataRecord.of(node));
        }

        /** Returns the next line without its {@code \n}, or {@code null} at the end of the input. */
        private byte[] readLine() {
            line.reset();
            boolean any = false;
            while (true) {
                if (position == limit && !fill()) {
                    return any ? endLine() : null;
                }
                any = true;
                int start = position;
                while (position < limit && buffer[position] != '\n') {
                    position++;
                }
                line.write(buffer, start, position - start);
                if (position < limit) {
                    position++; // the '\n'
                    return endLine();
                }
            }
        }

        private byte[] endLine() {
            lineNumber++;
            return line.toByteArray();
        }

        private boolean fill() {
            try {
                limit = in.read(buffer);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read NDJSON input", e);
            }
            position = 0;
            if (limit < 0) {
                limit = 0;
                return false;
            }
            return true;
        }

        /** Whitespace as JSON defines it; a {@code \r} from CRLF line endings is part of it. */
        private static boolean isBlank(byte[] bytes) {
            for (byte b : bytes) {
                if (b != ' ' && b != '\t' && b != '\r') {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void close() {
            try {
                in.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** One wording for both formats, e.g. "Array element 1 is a number" and "Line 3 is an array". */
    private static RecordReadException notAnObject(String where, JsonNode node) {
        return new RecordReadException(where + " is " + describe(node) + ", expected a JSON object", null);
    }

    private static String describe(JsonNode node) {
        if (node == null) {
            return "empty";
        }
        return switch (node.getNodeType()) {
            case ARRAY -> "an array";
            case STRING -> "a string";
            case NUMBER -> "a number";
            case BOOLEAN -> "a boolean";
            case NULL -> "null";
            case MISSING -> "empty";
            default -> node.getNodeType().name().toLowerCase(Locale.ROOT);
        };
    }
}
