package io.github.radeklesniewski.dq.json;

import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.record.RecordReadException;
import io.github.radeklesniewski.dq.record.RecordSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Newline-delimited JSON, one record object per line; see {@link JsonRecordSources#lines}. Each line's bytes go to
 * Jackson undecoded: decoding them first (e.g. with an {@code InputStreamReader}) would replace invalid UTF-8 with
 * U+FFFD without telling anyone and would reject a byte-order mark. Jackson reports the first and accepts the second.
 */
final class NdjsonSource implements RecordSource {

    private final ByteLineReader lines;

    NdjsonSource(InputStream in) {
        this.lines = new ByteLineReader(in);
    }

    @Override
    public Optional<DataRecord> next() {
        Optional<byte[]> line;
        do {
            line = readLine();
        } while (line.isPresent() && isBlank(line.get()));
        return line.map(this::parse);
    }

    private DataRecord parse(byte[] line) {
        JsonNode node;
        try {
            node = Json.MAPPER.readTree(line);
        } catch (JacksonException e) {
            throw new RecordReadException("Line " + lines.lineNumber() + " is not valid JSON", e);
        }
        return RecordNodes.toRecord(node, "Line", lines.lineNumber());
    }

    private Optional<byte[]> readLine() {
        try {
            return lines.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read NDJSON input", e);
        }
    }

    /** Whitespace as JSON defines it; a {@code \r} from CRLF line endings is part of it. */
    private static boolean isBlank(byte[] line) {
        for (byte b : line) {
            if (b != ' ' && b != '\t' && b != '\r') {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        try {
            lines.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
