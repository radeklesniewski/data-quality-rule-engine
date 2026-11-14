package io.github.radeklesniewski.dq.example.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.radeklesniewski.dq.RunSummary;
import io.github.radeklesniewski.dq.ValidationEngine;
import io.github.radeklesniewski.dq.json.catalog.JsonRuleCatalogs;
import io.github.radeklesniewski.dq.json.outcome.JsonOutcomeWriter;
import io.github.radeklesniewski.dq.json.record.JsonSourceException;
import io.github.radeklesniewski.dq.record.RecordSource;
import io.github.radeklesniewski.dq.rule.Rule;
import io.github.radeklesniewski.dq.rule.RuleCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * <p>Every endpoint answers only its exact path ({@code 404} otherwise) and its method ({@code 405} with
 * {@code Allow} otherwise). Problems found before streaming starts are a proper error response: {@code 400}
 * (parameters), {@code 413} (body too large), {@code 415} (content type), {@code 500} (an unreadable or invalid
 * catalog, a temporary file that can't be written; logged with the cause). Only I/O failures on the connection itself
 * count as the client having gone away, and are logged at {@code DEBUG}.
 *
 * <p>Run: {@code ./gradlew :example-http:run --args="--port 8080 --catalog path/to/catalog.json --max-body-mb 1024"}.
 * All arguments are optional. The bundled {@code example-catalog.json} and a 1 GiB body limit are the defaults.
 */
public final class ExampleHttpServer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ExampleHttpServer.class.getName());
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Default limit for a request body: 1 GiB, roughly 10 million partner records. */
    public static final long DEFAULT_MAX_BODY_BYTES = 1024L * 1024 * 1024;

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
        this.server = HttpServer.create(address, 0); // bind first: if it fails, there's no executor to leak
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        route("/validate", "POST", this::validate);
        route("/rules", "GET", this::rules);
        route("/health", "GET", exchange -> respond(exchange, 200, "{\"status\":\"UP\"}"));
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
     * Entry point: {@code [--port N] [--catalog file.json] [--max-body-mb N]}; see {@link ServerArguments}.
     *
     * @param args command-line arguments
     * @throws IOException if the port can't be bound
     */
    static void main(String[] args) throws IOException {
        ServerArguments arguments = ServerArguments.parse(args);
        RuleCatalog catalog = arguments.catalog()
                .map(file -> JsonRuleCatalogs.watching(file, ExampleRuleLogic.REGISTRY))
                .orElseGet(ExampleHttpServer::bundledCatalog);
        // Deliberately not try-with-resources: main returns at once while the HttpServer dispatcher thread (non-daemon)
        // keeps the JVM alive, so the server must outlive this method. The shutdown hook must call close() itself.
        // A hook that only wakes main up isn't enough, because the JVM halts as soon as the hooks finish.
        ExampleHttpServer server = start(new InetSocketAddress(arguments.port()), catalog, arguments.maxBodyBytes());
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        LOG.log(Level.INFO, () -> "Listening on http://localhost:" + server.port() + "/validate");
    }

    // ------------------------------------------------------------------ request handling, shared by every endpoint

    /** One endpoint's work, run after the path and method were checked. Throws {@link HttpError} to refuse. */
    @FunctionalInterface
    private interface Endpoint {
        void serve(HttpExchange exchange) throws IOException;
    }

    private void route(String path, String method, Endpoint endpoint) {
        server.createContext(path, exchange -> handle(exchange, path, method, endpoint));
    }

    /**
     * Runs {@code endpoint} for its exact path and method, and turns every way a request can end into the right
     * response or log line. The JDK server matches contexts by prefix, hence the exact path check.
     */
    private static void handle(HttpExchange exchange, String path, String method, Endpoint endpoint) {
        try (exchange) {
            if (!path.equals(exchange.getRequestURI().getPath())) {
                respondError(exchange, 404, "Not found");
                return;
            }
            if (!method.equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", method);
                respondError(exchange, 405, "Use " + method);
                return;
            }
            try {
                endpoint.serve(exchange);
            } catch (HttpError e) {
                if (e.isServerError()) {
                    LOG.log(Level.ERROR, e.getMessage(), e.getCause());
                }
                respondError(exchange, e.status(), e.clientMessage());
            }
        } catch (UncheckedIOException | IOException e) {
            LOG.log(Level.DEBUG, "Client went away: {0}", e.getMessage()); // nothing left to tell the client
        }
    }

    // ------------------------------------------------------------------ POST /validate

    /** Checks the request, then the catalog, then stores the body; only then does the response start. */
    private void validate(HttpExchange exchange) throws IOException {
        RunRequest request = RunRequest.parse(exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders());
        RuleCatalog snapshot = snapshotCatalog();
        try (SpooledBody body = SpooledBody.store(exchange.getRequestBody(), spoolDir, maxBodyBytes)) {
            streamOutcomes(exchange, request, snapshot, body);
        }
    }

    /**
     * Sends {@code 200} and streams the run's outcomes. From here on the status can't change, so a failure ends the
     * response with an {@code "error"} and no {@code "summary"} (see the class documentation).
     */
    private void streamOutcomes(HttpExchange exchange, RunRequest request, RuleCatalog snapshot, SpooledBody body)
            throws IOException {
        try (RecordSource records = openRecords(request, body)) {
            exchange.getResponseHeaders().set("Content-Type", request.output().mediaType());
            exchange.sendResponseHeaders(200, 0); // chunked: stream as we go
            long start = System.nanoTime();
            try (JsonOutcomeWriter writer = request.output().writer(exchange.getResponseBody())) {
                try {
                    RunSummary summary = engine.run(records, snapshot, request.options(), writer);
                    writer.finish(summary);
                    logCompleted(summary, Duration.ofNanos(System.nanoTime() - start));
                } catch (JsonSourceException e) {
                    LOG.log(Level.WARNING, "Request body is malformed: {0}", e.getMessage());
                    abortQuietly(writer, e.getMessage());
                } catch (RuntimeException e) {
                    logRunFailure(e);
                    abortQuietly(writer, "Validation failed; see server log");
                }
            }
        }
    }

    /** Opened before the {@code 200} is sent, so a stored body that can't be read is still a clean {@code 500}. */
    private static RecordSource openRecords(RunRequest request, SpooledBody body) {
        try {
            return request.input().reader(body.open());
        } catch (IOException e) {
            throw HttpError.serverError("Could not read the stored request body", e);
        }
    }

    private static void logCompleted(RunSummary summary, Duration took) {
        LOG.log(Level.INFO, () -> "Validated " + summary.recordsRead() + " records: " + summary.resultCount()
                + " results, " + summary.errorCount() + " errors in " + took.toMillis() + " ms");
    }

    /**
     * A run that failed while streaming. A client that disconnected surfaces here as a failing sink, with an
     * {@link IOException} somewhere in the cause chain: that's routine, not an error worth an operator's attention.
     */
    private static void logRunFailure(RuntimeException e) {
        if (causedByIo(e)) {
            LOG.log(Level.DEBUG, "Client went away while outcomes were streamed: {0}", e.getMessage());
        } else {
            LOG.log(Level.ERROR, "Validation failed", e);
        }
    }

    private static boolean causedByIo(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
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
        List<RuleView> rules = RunRequest.ruleFilter(exchange.getRequestURI().getRawQuery())
                .select(snapshotCatalog().rules())
                .stream().map(RuleView::of).toList();
        respond(exchange, 200, MAPPER.writeValueAsString(rules));
    }

    /** A rule as {@code GET /rules} shows it: its metadata, without the logic and decision mapping. */
    record RuleView(String id, String label, String status, String severity, String countryScope,
            Set<String> categories) {

        static RuleView of(Rule rule) {
            return new RuleView(rule.id(), rule.label(), rule.status().name(), rule.severity().name(),
                    rule.countryScope().toString(), rule.categories());
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Takes a snapshot of the catalog for one request. The catalog is host code (here possibly a watched file that
     * can become unreadable or invalid), so any failure is the server's.
     *
     * @throws HttpError {@code 500} if the catalog can't be loaded
     */
    private RuleCatalog snapshotCatalog() {
        try {
            return RuleCatalog.of(catalog.rules());
        } catch (RuntimeException e) {
            throw HttpError.serverError("Rule catalog can't be loaded", e);
        }
    }

    private static void respondError(HttpExchange exchange, int status, String message) throws IOException {
        respond(exchange, status, MAPPER.writeValueAsString(Map.of("error", message)));
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", Format.JSON.mediaType());
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
