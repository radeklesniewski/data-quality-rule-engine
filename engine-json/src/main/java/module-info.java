/**
 * JSON adapters for the data-quality rule engine: streaming record sources, a JSON rule catalog and a streaming
 * outcome writer, built on Jackson 3.
 */
module io.github.radeklesniewski.dq.json {
    requires transitive io.github.radeklesniewski.dq.core;
    requires transitive tools.jackson.databind; // JsonDataRecord.of(JsonNode) is part of the API
    requires tools.jackson.core;

    exports io.github.radeklesniewski.dq.json;
}
