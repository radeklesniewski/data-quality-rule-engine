package io.github.radeklesniewski.dq.example.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A request body copied to a temporary file before validation, so clients that upload everything before reading the
 * response can't deadlock against a server that writes while it reads (ADR-0003). The file is owner-only and deleted
 * by {@link #close()}. Memory stays bounded: the copy goes through a fixed-size buffer.
 */
final class SpooledBody implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(SpooledBody.class.getName());
    private static final int BUFFER_SIZE = 64 * 1024;

    private final Path file;

    private SpooledBody(Path file) {
        this.file = file;
    }

    /**
     * Copies the whole request body to a new temporary file in {@code directory}.
     *
     * @throws HttpError {@code 413} if the body exceeds {@code limit} bytes (copying stops early), or {@code 500} if
     *     the file can't be created or written (e.g. a full disk)
     * @throws UncheckedIOException if reading the request fails: the client went away
     */
    static SpooledBody store(InputStream request, Path directory, long limit) {
        Path file;
        try {
            file = Files.createTempFile(directory, "dq-request-", ".body");
        } catch (IOException e) {
            throw HttpError.serverError("Could not store the request body", e);
        }
        SpooledBody body = new SpooledBody(file);
        boolean stored = false;
        try {
            if (!copy(request, file, limit)) {
                throw HttpError.payloadTooLarge("Request body exceeds " + limit + " bytes");
            }
            stored = true;
            return body;
        } catch (IOException e) { // writing the file, not reading the client: see copy()
            throw HttpError.serverError("Could not store the request body", e);
        } finally {
            if (!stored) {
                body.close();
            }
        }
    }

    /**
     * Opens the stored body for reading.
     *
     * @throws IOException if the file can't be read: a server-side failure
     */
    InputStream open() throws IOException {
        return Files.newInputStream(file);
    }

    /** Deletes the file. A failure is logged, not thrown: the response is what matters at this point. */
    @Override
    public void close() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not delete temporary request body {0}: {1}", file, e.getMessage());
        }
    }

    /**
     * Copies {@code request} to {@code target}. The two kinds of I/O failure mean different things, so they're thrown
     * differently: failing to read the request means the client went away ({@link UncheckedIOException}); failing to
     * write {@code target} is a server-side problem ({@link IOException}). The request stream is closed with the
     * exchange.
     *
     * @return {@code false} if the body exceeds {@code limit} bytes
     */
    private static boolean copy(InputStream request, Path target, long limit) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        try (OutputStream out = Files.newOutputStream(target)) {
            for (int read = readRequest(request, buffer); read != -1; read = readRequest(request, buffer)) {
                total += read;
                if (total > limit) {
                    return false;
                }
                out.write(buffer, 0, read);
            }
        }
        return true;
    }

    private static int readRequest(InputStream request, byte[] buffer) {
        try {
            return request.read(buffer);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // the client went away; see copy()
        }
    }
}
