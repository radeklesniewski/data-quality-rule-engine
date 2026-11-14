package io.github.radeklesniewski.dq.json.record;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Splits a byte stream into lines at {@code \n}, without decoding anything. A {@code \r} before the {@code \n} stays
 * part of the line, and a last line without {@code \n} is still a line. Lines are as long as the input makes them;
 * memory is one line plus a fixed buffer. Not thread-safe.
 */
final class ByteLineReader implements Closeable {

    static final int DEFAULT_BUFFER_SIZE = 64 * 1024;

    private final InputStream in;
    private final byte[] buffer;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private int position;
    private int limit;
    private long lineNumber;

    ByteLineReader(InputStream in) {
        this(in, DEFAULT_BUFFER_SIZE);
    }

    ByteLineReader(InputStream in, int bufferSize) {
        this.in = in;
        this.buffer = new byte[bufferSize];
    }

    /**
     * Reads the next line.
     *
     * @return the line without its {@code \n}, or empty at the end of the input
     * @throws IOException if the stream fails
     */
    Optional<byte[]> readLine() throws IOException {
        line.reset();
        boolean any = false;
        while (true) {
            if (position == limit && !fill()) {
                return any ? Optional.of(endLine()) : Optional.empty();
            }
            any = true;
            int start = position;
            while (position < limit && buffer[position] != '\n') {
                position++;
            }
            line.write(buffer, start, position - start);
            if (position < limit) {
                position++; // the '\n'
                return Optional.of(endLine());
            }
        }
    }

    /** The number of the line {@link #readLine()} returned last, counting from 1. */
    long lineNumber() {
        return lineNumber;
    }

    private byte[] endLine() {
        lineNumber++;
        return line.toByteArray();
    }

    /** Refills the buffer; {@code false} at the end of the input. */
    private boolean fill() throws IOException {
        int read = in.read(buffer);
        position = 0;
        limit = Math.max(read, 0);
        return read >= 0;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
