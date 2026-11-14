/**
 * JSON adapters for the data-quality rule engine, built on Jackson 3, one package per concern: streaming record
 * sources ({@code record}), a JSON rule catalog ({@code catalog}) and a streaming outcome writer ({@code outcome}).
 */
module io.github.radeklesniewski.dq.json {
    requires transitive io.github.radeklesniewski.dq.core;
    requires transitive tools.jackson.databind; // JsonDataRecord.of(JsonNode) is part of the API
    requires tools.jackson.core;

    exports io.github.radeklesniewski.dq.json.record;
    exports io.github.radeklesniewski.dq.json.catalog;
    exports io.github.radeklesniewski.dq.json.outcome;
    // io.github.radeklesniewski.dq.json.internal is deliberately not exported.
}
