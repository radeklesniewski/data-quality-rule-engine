package io.github.radeklesniewski.dq.example.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.radeklesniewski.dq.Fixtures;
import io.github.radeklesniewski.dq.rule.Decision;
import io.github.radeklesniewski.dq.rule.RuleCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** End to end over real HTTP: JSON in, the library in the middle, streamed JSON out. */
class ExampleHttpServerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String FIXTURE_RECORDS = """
            [ { "id": "r1", "vatId": "DE111111111", "country": "DE", "legalName": "ACME GmbH", "iban": "DE123456789" },
              { "id": "r2", "vatId": "FR22", "country": "FR", "legalName": "Bricolage SARL", "iban": "FR123456789" },
              { "id": "r3", "country": "ZZ", "legalName": "Nowhere Ltd", "iban": "" } ]""";

    private static ExampleHttpServer fixtureServer;
    private static ExampleHttpServer bundledServer;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void startServers() throws IOException {
        fixtureServer = ExampleHttpServer.start(new InetSocketAddress("localhost", 0), RuleCatalog.of(Fixtures.RULES));
        bundledServer = ExampleHttpServer.start(new InetSocketAddress("localhost", 0),
                ExampleHttpServer.bundledCatalog());
    }

    @AfterAll
    static void stopServers() {
        fixtureServer.close();
        bundledServer.close();
    }

    private static URI uri(ExampleHttpServer server, String pathAndQuery) {
        return URI.create("http://localhost:" + server.port() + pathAndQuery);
    }

    private static HttpResponse<String> post(ExampleHttpServer server, String pathAndQuery, String contentType,
            String body) throws IOException, InterruptedException {
        return CLIENT.send(HttpRequest.newBuilder(uri(server, pathAndQuery))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(ExampleHttpServer server, String pathAndQuery)
            throws IOException, InterruptedException {
        return CLIENT.send(HttpRequest.newBuilder(uri(server, pathAndQuery)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Map<String, Decision>> decisionTable(JsonNode doc) {
        Map<String, Map<String, Decision>> table = new HashMap<>();
        for (JsonNode outcome : doc.get("outcomes")) {
            if (outcome.get("type").stringValue().equals("result")) {
                table.computeIfAbsent(outcome.get("recordId").stringValue(), id -> new HashMap<>())
                        .put(outcome.get("ruleId").stringValue(),
                                Decision.valueOf(outcome.get("decision").stringValue()));
            }
        }
        return table;
    }

    @Nested
    class Validate {

        @Test
        void briefScenarioOverHttp() throws Exception {
            HttpResponse<String> response = post(fixtureServer, "/validate", "application/json", FIXTURE_RECORDS);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
            JsonNode doc = JSON.readTree(response.body());
            assertThat(decisionTable(doc)).isEqualTo(Fixtures.EXPECTED);
            assertThat(doc.at("/summary/resultCount").intValue()).isEqualTo(9);
            assertThat(doc.at("/summary/resultsByDecision/INVALID").intValue()).isEqualTo(4);
            assertThat(doc.at("/outcomes/4/provenance/fieldsRead/0")).isEqualTo(JSON.readTree(
                    "{\"path\": \"vatId\", \"value\": \"FR22\"}"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"PARALLEL_ORDERED", "PARALLEL_UNORDERED"})
        void executionModesGiveTheSameDecisions(String mode) throws Exception {
            HttpResponse<String> response = post(fixtureServer, "/validate?mode=" + mode + "&batchSize=1",
                    "application/json", FIXTURE_RECORDS);

            assertThat(decisionTable(JSON.readTree(response.body()))).isEqualTo(Fixtures.EXPECTED);
        }

        @Test
        void ndjsonInAndOut() throws Exception {
            String ndjson = String.join("\n", JSON.readTree(FIXTURE_RECORDS).valueStream()
                    .map(JsonNode::toString).toList());
            HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(uri(fixtureServer, "/validate"))
                    .header("Content-Type", "application/x-ndjson")
                    .header("Accept", "application/x-ndjson")
                    .POST(HttpRequest.BodyPublishers.ofString(ndjson)).build(), HttpResponse.BodyHandlers.ofString());

            List<String> lines = response.body().lines().toList();
            assertThat(response.headers().firstValue("Content-Type")).contains("application/x-ndjson");
            assertThat(lines).hasSize(10);
            assertThat(JSON.readTree(lines.getLast()).get("type").stringValue()).isEqualTo("summary");
        }

        @Test
        void badRecordsAreReportedAndTheRestIsValidated() throws Exception {
            HttpResponse<String> response = post(fixtureServer, "/validate", "application/json", """
                    [ {"id": "a", "vatId": "DE111111111", "country": "DE", "iban": "DE1"},
                      42,
                      {"country": "DE"} ]""");

            JsonNode doc = JSON.readTree(response.body());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(doc.at("/summary/resultCount").intValue()).isEqualTo(3);
            assertThat(doc.at("/summary/recordErrorCount").intValue()).isEqualTo(2);
            assertThat(doc.at("/outcomes/3/type").stringValue()).isEqualTo("recordError");
            assertThat(doc.at("/outcomes/3/message").stringValue()).contains("element 1 is a number");
        }

        @Test
        void malformedBodyEndsWithErrorAndNoSummary() throws Exception {
            HttpResponse<String> response = post(fixtureServer, "/validate", "application/json", """
                    [ {"id": "r1", "vatId": "DE111111111", "country": "DE", "iban": "DE1"},
                      {"id": "r2" "oops": 1} ]""");

            JsonNode doc = JSON.readTree(response.body());
            assertThat(doc.get("outcomes")).hasSize(3);
            assertThat(doc.get("error").stringValue()).startsWith("Malformed JSON at line 2");
            assertThat(doc.has("summary")).isFalse();
        }

        @Test
        void streamsLargeBatchesInBothDirections() throws Exception {
            int records = 50_000;
            Stream<String> body = Stream.concat(Stream.concat(Stream.of("["),
                    Stream.iterate(0, i -> i < records, i -> i + 1).map(i -> (i == 0 ? "" : ",")
                            + "{\"id\":\"r" + i + "\",\"country\":\"DE\",\"vatId\":\"DE111111111\",\"iban\":\"DE1\"}")),
                    Stream.of("]"));
            AtomicLong lines = new AtomicLong();

            HttpResponse<Stream<String>> response = CLIENT.send(
                    HttpRequest.newBuilder(uri(fixtureServer, "/validate?format=ndjson"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> toStream(body))).build(),
                    HttpResponse.BodyHandlers.ofLines());
            String last;
            try (Stream<String> responseLines = response.body()) {
                last = responseLines.peek(line -> lines.incrementAndGet()).reduce((a, b) -> b).orElseThrow();
            }

            assertThat(lines).hasValue(3L * records + 1);
            assertThat(JSON.readTree(last).get("recordsRead").intValue()).isEqualTo(records);
        }
    }

    @Nested
    class Selection {

        @Test
        void filtersFromQueryParameters() throws Exception {
            JsonNode bank = JSON.readTree(post(bundledServer, "/validate?category=bank", "application/json",
                    FIXTURE_RECORDS).body());
            assertThat(bank.at("/summary/rulesSelected").intValue()).isEqualTo(1);

            JsonNode drafts = JSON.readTree(post(bundledServer, "/validate?status=DRAFT", "application/json",
                    FIXTURE_RECORDS).body());
            assertThat(drafts.at("/summary/rulesSelected").intValue()).as("DRAFT never runs").isZero();
            assertThat(drafts.get("outcomes")).isEmpty();

            JsonNode german = JSON.readTree(post(bundledServer, "/validate?country=DE&category=tax",
                    "application/json", FIXTURE_RECORDS).body());
            assertThat(german.at("/summary/rulesSelected").intValue()).isEqualTo(2); // vatFormat + deVatPrefix
            assertThat(german.get("outcomes").valueStream().filter(o -> o.get("ruleId").stringValue()
                    .equals("deVatPrefix")).map(o -> o.get("recordId").stringValue()).toList())
                    .as("country-scoped rule only runs on DE records").containsExactly("r1");
        }

        @Test
        void outOfScopeResultsOnRequest() throws Exception {
            JsonNode doc = JSON.readTree(post(bundledServer, "/validate?category=tax&emitOutOfScope=true",
                    "application/json", FIXTURE_RECORDS).body());

            assertThat(doc.get("outcomes").valueStream()
                    .filter(o -> o.at("/provenance/basis").stringValue().equals("OUT_OF_SCOPE"))
                    .map(o -> o.get("recordId").stringValue()).toList()).containsExactly("r2", "r3");
        }

        @Test
        void listsRulesWithTheSameFilters() throws Exception {
            JsonNode all = JSON.readTree(get(bundledServer, "/rules").body());
            JsonNode tax = JSON.readTree(get(bundledServer, "/rules?category=tax").body());

            assertThat(all).hasSize(5);
            assertThat(tax.valueStream().map(r -> r.get("id").stringValue()).toList())
                    .containsExactly("vatFormat", "deVatPrefix");
            assertThat(tax.get(1).get("countryScope").stringValue()).isEqualTo("DE");
        }
    }

    @Nested
    class Errors {

        @ParameterizedTest
        @ValueSource(strings = {"mode=FAST", "batchSize=0", "batchSize=abc", "batchSize=1000000", "country=Germany",
                "status=LIVE", "unknown=1", "category=a&category=b", "emitOutOfScope=maybe"})
        void invalidParametersAreRejectedBeforeReadingTheBody(String query) throws Exception {
            HttpResponse<String> response = post(fixtureServer, "/validate?" + query, "application/json", "[]");

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(JSON.readTree(response.body()).get("error").stringValue()).isNotBlank();
        }

        @Test
        void wrongMethodAndContentType() throws Exception {
            HttpResponse<String> get = get(fixtureServer, "/validate");
            assertThat(get.statusCode()).isEqualTo(405);
            assertThat(get.headers().firstValue("Allow")).contains("POST");

            assertThat(post(fixtureServer, "/validate", "text/csv", "id\nr1").statusCode()).isEqualTo(415);
        }

        @Test
        void health() throws Exception {
            assertThat(get(fixtureServer, "/health").body()).isEqualTo("{\"status\":\"UP\"}");
        }
    }

    private static InputStream toStream(Stream<String> parts) {
        var iterator = parts.iterator();
        return new InputStream() {
            private byte[] current = new byte[0];
            private int position;

            @Override
            public int read() {
                while (position >= current.length) {
                    if (!iterator.hasNext()) {
                        return -1;
                    }
                    current = iterator.next().getBytes(UTF_8);
                    position = 0;
                }
                return current[position++] & 0xFF;
            }
        };
    }
}
