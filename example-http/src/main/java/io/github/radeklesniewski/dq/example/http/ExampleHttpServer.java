package io.github.radeklesniewski.dq.example.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.radeklesniewski.dq.RunSummary;
import io.github.radeklesniewski.dq.ValidationEngine;
import io.github.radeklesniewski.dq.json.catalog.JsonRuleCatalogs;
import io.github.radeklesniewski.dq.json.outcome.JsonOutcomeWriter;
import io.github.radeklesniewski.dq.json.record.JsonRecordSources;
import io.github.radeklesniewski.dq.json.record.JsonSourceException;
import io.github.radeklesniewski.dq.record.RecordSource;
import io.github.radeklesniewski.dq.rule.Rule;
import io.github.radeklesniewski.dq.rule.RuleCatalog;
import io.github.radeklesniewski.dq.select.RuleFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.json.JsonMapper;

/**
 * Example host: a small HTTP API that validates a batch of records with the library and streams the outcomes back.
 *
 * <ul>
 *   <li>{@code POST /validate}: the body is a JSON array of records ({@code Content-Type: application/json}) or NDJSON
 *       ({@code application/x-ndjson}). The response is {@code {"outcomes":[...],"summary":{...}}}, or NDJSON with
 *       {@code Accept: application/x-ndjson} or {@code ?format=ndjson}. Query parameters select rules and
 *       execution; see {@link RunRequest}.</li>
 *   <li>{@code GET /rules}: the catalog's rules, optionally filtered with the same parameters.</li>
 *   <li>{@code GET /health}: liveness.</li>
 * </ul>
 *
 * <p><strong>Request handling.</strong> The request body is first copied, in a streaming way, to a temporary file
 * (owner-only permissions, deleted when the request ends). A body larger than the configured limit is rejected with
 * {@code 413} before any validation. The engine then reads records lazily from that file, and the response is
 * written while it runs. Memory therefore doesn't grow with the batch size, and the server works with
 * <em>any</em> HTTP client. Clients that upload the whole body before reading the response (most of them) would
 * otherwise deadlock against a server that writes while it is still reading.
 *
 * <p>The 200 status is sent before the records have been parsed. If the body turns out to be malformed half-way, the
 * response ends with an {@code "error"} and has no {@code "summary"}. Clients must treat a response without a summary
 * as incomplete.
 *
 * <p>Server-side failures that happen before streaming starts (an unreadable or invalid catalog, a temporary file
 * that can't be written) are logged and answered with {@code 500}. Only I/O failures on the connection itself are
 * treated as the client having gone away.
 *
 * <p>Run: {@code ./gradlew :example-http:run --args="--port 8080 --catalog path/to/catalog.json --max-body-mb 1024"}.
 * All arguments are optional. The bundled {@code example-catalog.json} and a 1 GiB body limit are the defaults.
 */
public final class ExampleHttpServer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ExampleHttpServer.class.getName());
    private static final String JSON = "application/json";
    private static final String NDJSON = "application/x-ndjson";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Default limit for a request body: 1 GiB, roughly 10 million partner records. */
    public static final long DEFAULT_MAX_BODY_BYTES = 1024L * 1024 * 1024;
    private static final int DEFAULT_PORT = 8080;

    private final HttpServer server;
    private final ExecutorService executor;
    private final RuleCatalog catalog;
    private final long maxBodyBytes;
    private final Path spoolDir;
    private final ValidationEngine engine = ValidationEngine.create();

    private ExampleHttpServer(InetSocketAddress address, RuleCatalog catalog, long maxBodyBytes, Path spoolDir)
            throws IOException {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        if (maxBodyBytes < 1) {
            throw new IllegalArgumentException("maxBodyBytes must be >= 1, got " + maxBodyBytes);
        }
        this.maxBodyBytes = maxBodyBytes;
        this.spoolDir = Objects.requireNonNull(spoolDir, "spoolDir");
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.server = HttpServer.create(address, 0);
        server.setExecutor(executor);
        server.createContext("/validate", this::validate);
        server.createContext("/rules", this::rules);
        server.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"UP\"}"));
        server.start();
    }

    /**
     * Starts a server.
     *
     * @param address where to listen; port 0 picks a free port
     * @param catalog the rules to validate with
     * @return the running server
     * @throws IOException if the address can't be bound
     */
    public static ExampleHttpServer start(InetSocketAddress address, RuleCatalog catalog) throws IOException {
        return start(address, catalog, DEFAULT_MAX_BODY_BYTES);
    }

    /**
     * Starts a server with a custom request-body limit.
     *
     * @param address where to listen; port 0 picks a free port
     * @param catalog the rules to validate with
     * @param maxBodyBytes larger request bodies are rejected with {@code 413}
     * @return the running server
     * @throws IOException if the address can't be bound
     */
    public static ExampleHttpServer start(InetSocketAddress address, RuleCatalog catalog, long maxBodyBytes)
            throws IOException {
        return start(address, catalog, maxBodyBytes, Path.of(System.getProperty("java.io.tmpdir")));
    }

    /** Starts a server that spools request bodies into {@code spoolDir}. Package-private: a seam for tests. */
    static ExampleHttpServer start(InetSocketAddress address, RuleCatalog catalog, long maxBodyBytes, Path spoolDir)
            throws IOException {
        return new ExampleHttpServer(address, catalog, maxBodyBytes, spoolDir);
    }

    /**
     * Loads the bundled example catalog.
     *
     * @return the catalog bundled with this example
     */
    public static RuleCatalog bundledCatalog() {
        try (InputStream in = ExampleHttpServer.class.getResourceAsStream("/example-catalog.json")) {
            return JsonRuleCatalogs.load(Objects.requireNonNull(in, "example-catalog.json missing"),
                    ExampleRuleLogic.REGISTRY);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns the bound port.
     *
     * @return the bound port
     */
    public int port() {
        return server.getAddress().getPort();
    }

    /** Stops accepting requests, gives running ones a moment to finish, and stops. */
    @Override
    public void close() {
        server.stop(1);
        executor.close();
    }

    /**
     * Entry point: {@code [--port N] [--catalog file.json] [--max-body-mb N]}.
     *
     * @param args command-line arguments
     * @throws IOException if the port can't be bound
     */
    static void main(String[] args) throws IOException {
        int port = DEFAULT_PORT;
        RuleCatalog catalog = null;
        long maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                case "--catalog" ->
                        catalog = JsonRuleCatalogs.watching(Path.of(args[i + 1]), ExampleRuleLogic.REGISTRY);
                case "--max-body-mb" -> maxBodyBytes = Long.parseLong(args[i + 1]) * 1024 * 1024;
                default -> throw new IllegalArgumentException("Unknown argument " + args[i]);
            }
        }
        // Deliberately not try-with-resources: main returns at once while the HttpServer dispatcher thread (non-daemon)
        // keeps the JVM alive, so the server must outlive this method. The shutdown hook must call close() itself.
        // A hook that only wakes main up isn't enough, because the JVM halts as soon as the hooks finish.
        ExampleHttpServer server = start(new InetSocketAddress(port),
                catalog != null ? catalog : bundledCatalog(), maxBodyBytes);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        LOG.log(Level.INFO, "Listening on http://localhost:{0}/validate", String.valueOf(server.port()));
    }

    // ------------------------------------------------------------------ POST /validate

    private void validate(HttpExchange exchange) {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                respondError(exchange, 405, "Use POST with a JSON array of records");
                return;
            }
            RunRequest request;
            try {
                request = RunRequest.parse(exchange.getRequestURI().getRawQuery());
            } catch (RunRequest.BadRequest e) {
                respondError(exchange, 400, e.getMessage());
                return;
            }
            Optional<RuleCatalog> snapshot = snapshotCatalog(exchange); // a 500 before streaming starts
            if (snapshot.isEmpty()) {
                return;
            }
            String contentType = mediaType(exchange.getRequestHeaders().getFirst("Content-Type"), JSON);
            if (!contentType.equals(JSON) && !contentType.equals(NDJSON)) {
                respondError(exchange, 415, "Content-Type must be " + JSON + " or " + NDJSON);
                return;
            }
            boolean ndjsonOut = NDJSON.equals(mediaType(exchange.getRequestHeaders().getFirst("Accept"), JSON))
                    || "ndjson".equals(RunRequest.params(exchange.getRequestURI().getRawQuery()).get("format"));

            // Read the whole upload before writing anything, so half-duplex clients can't deadlock (see class doc).
            Path spool;
            try {
                spool = Files.createTempFile(spoolDir, "dq-request-", ".body");
            } catch (IOException e) {
                respondServerError(exchange, "Could not store the request body", e);
                return;
            }
            try {
                boolean withinLimit;
                try {
                    withinLimit = spool(exchange.getRequestBody(), spool, maxBodyBytes);
                } catch (IOException e) { // the temp file, not the client: see spool()
                    respondServerError(exchange, "Could not store the request body", e);
                    return;
                }
                if (!withinLimit) {
                    respondError(exchange, 413, "Request body exceeds " + maxBodyBytes + " bytes");
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", ndjsonOut ? NDJSON : JSON);
                exchange.sendResponseHeaders(200, 0); // chunked: stream as we go
                long start = System.nanoTime();
                InputStream body = Files.newInputStream(spool);
                OutputStream out = exchange.getResponseBody();
                try (RecordSource records = contentType.equals(NDJSON)
                                ? JsonRecordSources.lines(body) : JsonRecordSources.array(body);
                        JsonOutcomeWriter writer = ndjsonOut ? JsonOutcomeWriter.lines(out)
                                : JsonOutcomeWriter.document(out)) {
                    try {
                        RunSummary summary = engine.run(records, snapshot.get(), request.options(), writer);
                        writer.finish(summary);
                        LOG.log(Level.INFO, "Validated {0} records: {1} results, {2} errors in {3} ms",
                                summary.recordsRead(), summary.resultCount(), summary.errorCount(),
                                (System.nanoTime() - start) / 1_000_000);
                    } catch (JsonSourceException e) {
                        LOG.log(Level.WARNING, "Request body is malformed: {0}", e.getMessage());
                        abortQuietly(writer, e.getMessage());
                    } catch (RuntimeException e) {
                        LOG.log(Level.ERROR, "Validation failed", e);
                        abortQuietly(writer, "Validation failed; see server log");
                    }
                }
            } finally {
                deleteSpool(spool);
            }
        } catch (UncheckedIOException | IOException e) {
            LOG.log(Level.DEBUG, "Client went away: {0}", e.getMessage()); // nothing left to tell the client
        }
    }

    /**
     * Copies the request body to {@code target} in fixed-size chunks. The request body itself is closed with the
     * exchange.
     *
     * <p>The two kinds of I/O failure mean different things, so they're thrown differently: failing to read the
     * request means the client went away ({@link UncheckedIOException}); failing to write {@code target} is a
     * server-side problem such as a full disk ({@link IOException}).
     *
     * @return {@code false} if the body exceeds {@code limit} bytes; copying then stops early
     */
    private static boolean spool(InputStream body, Path target, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        try (OutputStream out = Files.newOutputStream(target)) {
            for (int read = readRequest(body, buffer); read != -1; read = readRequest(body, buffer)) {
                total += read;
                if (total > limit) {
                    return false;
                }
                out.write(buffer, 0, read);
            }
        }
        return true;
    }

    private static int readRequest(InputStream body, byte[] buffer) {
        try {
            return body.read(buffer);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // the client went away; see spool()
        }
    }

    private static void deleteSpool(Path spool) {
        try {
            Files.deleteIfExists(spool);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not delete temporary request body {0}: {1}", spool, e.getMessage());
        }
    }

    private static void abortQuietly(JsonOutcomeWriter writer, String reason) {
        try {
            writer.abort(reason);
        } catch (RuntimeException ignored) {
            // The output itself is broken (e.g. client disconnected); the cause was already logged.
        }
    }

    // ------------------------------------------------------------------ GET /rules

    private void rules(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                respondError(exchange, 405, "Use GET");
                return;
            }
            RuleFilter filter;
            try {
                filter = RunRequest.filter(RunRequest.params(exchange.getRequestURI().getRawQuery()));
            } catch (RunRequest.BadRequest e) {
                respondError(exchange, 400, e.getMessage());
                return;
            }
            Optional<RuleCatalog> snapshot = snapshotCatalog(exchange);
            if (snapshot.isEmpty()) {
                return;
            }
            List<Rule> rules = filter.select(snapshot.get().rules());
            exchange.getResponseHeaders().set("Content-Type", JSON);
            exchange.sendResponseHeaders(200, 0);
            try (JsonGenerator json = MAPPER.createGenerator(exchange.getResponseBody())) {
                json.writeStartArray();
                for (Rule rule : rules) {
                    json.writeStartObject();
                    json.writeStringProperty("id", rule.id());
                    json.writeStringProperty("label", rule.label());
                    json.writeStringProperty("status", rule.status().name());
                    json.writeStringProperty("severity", rule.severity().name());
                    json.writeStringProperty("countryScope", rule.countryScope().toString());
                    json.writeArrayPropertyStart("categories");
                    rule.categories().forEach(json::writeString);
                    json.writeEndArray();
                    json.writeEndObject();
                }
                json.writeEndArray();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Takes a snapshot of the catalog for one request. The catalog is host code (here possibly a watched file that
     * can become unreadable or invalid), so any failure is the server's: it's logged and answered with {@code 500},
     * never mistaken for a client disconnect.
     *
     * @return the snapshot, or empty if a {@code 500} has been sent
     */
    private Optional<RuleCatalog> snapshotCatalog(HttpExchange exchange) throws IOException {
        try {
            return Optional.of(RuleCatalog.of(catalog.rules()));
        } catch (RuntimeException e) {
            respondServerError(exchange, "Rule catalog can't be loaded", e);
            return Optional.empty();
        }
    }

    /** Logs a server-side failure with its cause and answers {@code 500} without exposing the details. */
    private static void respondServerError(HttpExchange exchange, String message, Exception cause)
            throws IOException {
        LOG.log(Level.ERROR, message, cause);
        respondError(exchange, 500, message + "; see server log");
    }

    private static String mediaType(String header, String fallback) {
        if (header == null || header.isBlank() || header.startsWith("*/*")) {
            return fallback;
        }
        return header.split(";")[0].trim().toLowerCase(Locale.ROOT);
    }

    private static void respondError(HttpExchange exchange, int status, String message) throws IOException {
        respond(exchange, status, MAPPER.writeValueAsString(Map.of("error", message)));
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", JSON);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
