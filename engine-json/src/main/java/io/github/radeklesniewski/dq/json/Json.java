package io.github.radeklesniewski.dq.json;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/** Shared Jackson configuration. Internal to this module. */
final class Json {

    /**
     * Mapper for reading records and catalogs, and for writing outcomes. Decimals are read as {@code BigDecimal}, so
     * values such as amounts aren't rounded. Parser error messages never quote the input, which may contain personal
     * data. Root values are separated by a newline, so NDJSON output has one value per line.
     */
    static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder().rootValueSeparator("\n").build())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build();

    /**
     * Reads one value out of a larger stream (an element of the records array). The rest of the array necessarily
     * follows, so the "no trailing tokens" check that protects whole-document reads must be off here.
     */
    static final ObjectReader ELEMENT_READER = MAPPER.reader().without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private Json() {
    }
}
