package io.github.radeklesniewski.dq.json;

import java.io.IOException;
import java.io.UncheckedIOException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/** Shared Jackson configuration. Internal to this module. */
final class Json {

    /**
     * Mapper for reading records and catalogs, and for writing outcomes. Decimals are read as {@code BigDecimal}, so
     * values such as amounts aren't rounded. Parser error messages never quote the input, which may contain personal
     * data. Root values are separated by a newline, so NDJSON output has one value per line. Closing a generator
     * closes whatever arrays and objects are still open (stated explicitly, though it's Jackson's default), so output
     * stays well-formed even if a write failed half-way through an outcome.
     */
    static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder().rootValueSeparator("\n").build())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .enable(StreamWriteFeature.AUTO_CLOSE_CONTENT)
            .build();

    /**
     * Reads one value out of a larger stream (an element of the records array). The rest of the array necessarily
     * follows, so the "no trailing tokens" check that protects whole-document reads must be off here.
     */
    static final ObjectReader ELEMENT_READER = MAPPER.reader().without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private Json() {
    }

    /**
     * Converts a failure of the underlying stream back into the I/O error it is. Jackson wraps such failures in
     * {@link JacksonIOException}, a subtype of the {@code JacksonException} that also signals syntax errors, so
     * without this a disconnect or a disk error would be reported as malformed JSON.
     */
    static UncheckedIOException ioFailure(String message, JacksonIOException e) {
        return new UncheckedIOException(message, e.getCause() instanceof IOException cause ? cause : new IOException(e));
    }
}
