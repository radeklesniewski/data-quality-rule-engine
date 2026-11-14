package io.github.radeklesniewski.dq.example.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FormatTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "null                                                   | JSON",
            "*/*                                                    | JSON",
            "application/x-ndjson                                   | NDJSON",
            "application/x-ndjson, application/json;q=0.9           | NDJSON",
            "application/json;q=0.5, application/x-ndjson           | NDJSON",
            "application/x-ndjson;q=0.1, */*                        | JSON",
            "application/x-ndjson;q=0, application/json;q=0.1       | JSON",
            "text/html, application/xhtml+xml                       | JSON",
            "application/x-ndjson;q=oops, application/json;q=0.2    | JSON"})
    void negotiatesTheResponseFormatFromAccept(String accept, Format expected) {
        assertThat(Format.negotiate(accept)).isEqualTo(expected);
    }

    @Test
    void readsTheBodyFormatFromContentType() {
        assertThat(Format.ofContentType(null)).isEqualTo(Format.JSON);
        assertThat(Format.ofContentType("Application/X-NDJSON; charset=utf-8")).isEqualTo(Format.NDJSON);
        assertThatThrownBy(() -> Format.ofContentType("text/csv"))
                .isInstanceOfSatisfying(HttpError.class, e -> assertThat(e.status()).isEqualTo(415));
    }

    @Test
    void formatParameterIsStrict() {
        assertThat(Format.ofParameter("NDJSON")).isEqualTo(Format.NDJSON);
        assertThatThrownBy(() -> Format.ofParameter("xml"))
                .isInstanceOfSatisfying(HttpError.class, e -> assertThat(e.status()).isEqualTo(400));
    }
}
