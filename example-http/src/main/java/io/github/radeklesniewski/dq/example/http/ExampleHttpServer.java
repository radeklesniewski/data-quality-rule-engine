package io.github.radeklesniewski.dq.example.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.radeklesniewski.dq.RunSummary;
import io.github.radeklesniewski.dq.ValidationEngine;
import io.github.radeklesniewski.dq.json.CatalogLoadException;
import io.github.radeklesniewski.dq.json.JsonOutcomeWriter;
import io.github.radeklesniewski.dq.json.JsonRecordSources;
import io.github.radeklesniewski.dq.json.JsonRuleCatalog;
import io.github.radeklesniewski.dq.json.JsonSourceException;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 * <p>The request body is read and the response written <em>while</em> the engine runs, so a request's memory doesn't
 * grow with the batch size. As a consequence the 200 status is sent before the input has been fully read. If the
 * body turns out to be malformed half-way, the response ends with an {@code "error"} and has no {@code "summary"}.
 * Clients must treat a response without a summary as incomplete.
 *
 * <p>Run: {@code ./gradlew :example-http:run --args="--port 8080 --catalog path/to/catalog.json"}. Both arguments are
 * optional; the bundled {@code example-catalog.json} is used by default.
 */
public final class ExampleHttpServer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ExampleHttpServer.class.getName());
    private static final String JSON = "application/json";
    private static final String NDJSON = "application/x-ndjson";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final HttpServer server;
    private final ExecutorService executor;
    private final RuleCatalog catalog;
    private final ValidationEngine engine = ValidationEngine.create();

    private ExampleHttpServer(InetSocketAddress address, RuleCatalog catalog) throws IOException {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
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
        return new ExampleHttpServer(address, catalog);
    }

    /**
     * Loads the bundled example catalog.
     *
     * @return the catalog bundled with this example
     */
    public static RuleCatalog bundledCatalog() {
        try (InputStream in = ExampleHttpServer.class.getResourceAsStream("/example-catalog.json")) {
            return JsonRuleCatalog.load(Objects.requireNonNull(in, "example-catalog.json missing"),
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
     * Entry point: {@code [--port N] [--catalog file.json]}.
     *
     * @param args command-line arguments
     * @throws IOException if the port can't be bound
     */
    public static void main(String[] args) throws IOException {
        int port = 8080;
        RuleCatalog catalog = null;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                case "--catalog" -> catalog = JsonRuleCatalog.watching(Path.of(args[i + 1]), ExampleRuleLogic.REGISTRY);
                default -> throw new IllegalArgumentException("Unknown argument " + args[i]);
            }
        }
        ExampleHttpServer server = start(new InetSocketAddress(port), catalog != null ? catalog : bundledCatalog());
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        LOG.log(Level.INFO, "Listening on http://localhost:{0}/validate", String.valueOf(server.port()));
    }

    // ------------------------------------------------------------------ POST /validate

    private void validate(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                respondError(exchange, 405, "Use POST with a JSON array of records");
                return;
            }
            RunRequest request;
            RuleCatalog snapshot;
            try {
                request = RunRequest.parse(exchange.getRequestURI().getRawQuery());
                snapshot = RuleCatalog.of(catalog.rules()); // catalog problems become a 500 before streaming starts
            } catch (RunRequest.BadRequest e) {
                respondError(exchange, 400, e.getMessage());
                return;
            } catch (CatalogLoadException e) {
                LOG.log(Level.ERROR, "Rule catalog is invalid", e);
                respondError(exchange, 500, "Rule catalog is invalid; see server log");
                return;
            }
            String contentType = mediaType(exchange.getRequestHeaders().getFirst("Content-Type"), JSON);
            if (!contentType.equals(JSON) && !contentType.equals(NDJSON)) {
                respondError(exchange, 415, "Content-Type must be " + JSON + " or " + NDJSON);
                return;
            }
            boolean ndjsonOut = NDJSON.equals(mediaType(exchange.getRequestHeaders().getFirst("Accept"), JSON))
                    || "ndjson".equals(RunRequest.params(exchange.getRequestURI().getRawQuery()).get("format"));

            exchange.getResponseHeaders().set("Content-Type", ndjsonOut ? NDJSON : JSON);
            exchange.sendResponseHeaders(200, 0); // chunked: stream as we go
            long start = System.nanoTime();
            InputStream body = exchange.getRequestBody();
            OutputStream out = exchange.getResponseBody();
            try (RecordSource records = contentType.equals(NDJSON)
                            ? JsonRecordSources.lines(body) : JsonRecordSources.array(body);
                    JsonOutcomeWriter writer = ndjsonOut ? JsonOutcomeWriter.lines(out)
                            : JsonOutcomeWriter.document(out)) {
                try {
                    RunSummary summary = engine.run(records, snapshot, request.options(), writer);
                    writer.finish(summary);
                    LOG.log(Level.INFO, "Validated {0} records: {1} results, {2} errors in {3} ms",
                            summary.recordsRead(), summary.resultCount(), summary.errorCount(),
                            (System.nanoTime() - start) / 1_000_000);
                } catch (JsonSourceException e) {
                    LOG.log(Level.WARNING, "Request body is malformed: {0}", e.getMessage());
                    writer.abort(e.getMessage());
                } catch (RuntimeException e) {
                    LOG.log(Level.ERROR, "Validation failed", e);
                    abortQuietly(writer, "Validation failed; see server log");
                }
            }
        } catch (UncheckedIOException | IOException e) {
            LOG.log(Level.DEBUG, "Client went away: {0}", e.getMessage()); // nothing left to tell the client
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
            List<Rule> rules;
            try {
                RuleFilter filter = RunRequest.filter(RunRequest.params(exchange.getRequestURI().getRawQuery()));
                rules = filter.select(catalog.rules());
            } catch (RunRequest.BadRequest e) {
                respondError(exchange, 400, e.getMessage());
                return;
            }
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
