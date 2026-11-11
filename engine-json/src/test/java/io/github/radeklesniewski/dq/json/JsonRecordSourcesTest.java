package io.github.radeklesniewski.dq.json;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.record.RecordReadException;
import io.github.radeklesniewski.dq.record.RecordSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class JsonRecordSourcesTest {

    private static InputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(UTF_8));
    }

    private static DataRecord next(RecordSource source) {
        return source.next().orElseThrow();
    }

    @Nested
    class ArrayFormat {

        @Test
        void readsObjectsWithJsonValueMapping() {
            RecordSource source = JsonRecordSources.array(stream("""
                    [ {"id": "r1", "n": 12, "amount": 0.10, "ok": true, "none": null,
                       "address": {"city": "Berlin"}, "tags": ["a", "b"]} ]"""));

            DataRecord record = next(source);

            assertThat(record.get("id")).contains("r1");
            assertThat(record.get("n")).contains(12);
            assertThat(record.get("amount")).contains(new BigDecimal("0.10"));
            assertThat(record.get("ok")).contains(true);
            assertThat(record.get("none")).isEmpty();
            assertThat(record.get("missing")).isEmpty();
            assertThat(record.get("address.city")).contains("Berlin");
            assertThat(record.get("address")).contains(Map.of("city", "Berlin"));
            assertThat(record.get("tags")).contains(List.of("a", "b"));
            assertThat(record.get("id.deeper")).isEmpty();
            assertThat(source.next()).isEmpty();
            assertThat(source.next()).isEmpty();
        }

        @Test
        void emptyArrayAndEmptyInputHaveNoRecords() {
            assertThat(JsonRecordSources.array(stream("[]")).next()).isEmpty();
            assertThat(JsonRecordSources.array(stream("")).next()).isEmpty();
        }

        @Test
        void nonObjectElementsAreUnreadableAndSkipped() {
            RecordSource source = JsonRecordSources.array(stream("""
                    [ {"id": "a"}, 42, [1, {"x": 2}], "text", null, {"id": "b"} ]"""));

            assertThat(next(source).get("id")).contains("a");
            assertThatThrownBy(source::next).isInstanceOf(RecordReadException.class)
                    .hasMessage("Array element 1 is a number, expected a JSON object");
            assertThatThrownBy(source::next).hasMessageContaining("element 2 is an array");
            assertThatThrownBy(source::next).hasMessageContaining("element 3 is a string");
            assertThatThrownBy(source::next).hasMessageContaining("element 4 is null");
            assertThat(next(source).get("id")).contains("b");
            assertThat(source.next()).isEmpty();
        }

        @Test
        void rootThatIsNotAnArrayIsRejected() {
            assertThatThrownBy(() -> JsonRecordSources.array(stream("{\"id\": \"r1\"}")).next())
                    .isInstanceOf(JsonSourceException.class)
                    .hasMessageContaining("Expected a JSON array");
        }

        @Test
        void syntaxErrorAbortsWithLocationButWithoutTheData() {
            RecordSource source = JsonRecordSources.array(stream("""
                    [ {"id": "r1"},
                      {"id": "secret-tax-id-123" "broken": 1} ]"""));

            assertThat(next(source).get("id")).contains("r1");
            assertThatThrownBy(source::next)
                    .isInstanceOf(JsonSourceException.class)
                    .hasMessageContaining("line 2")
                    .message().doesNotContain("secret-tax-id-123");
            assertThat(source.next()).as("source stays finished after a fatal error").isEmpty();
        }

        @Test
        void contentAfterTheArrayIsReportedNotSilentlyDropped() {
            for (String trailing : new String[] {" [{\"id\": \"b\"}]", " garbage", " {\"id\": \"b\"}"}) {
                RecordSource source = JsonRecordSources.array(stream("[{\"id\": \"a\"}]" + trailing));

                assertThat(next(source).get("id")).as("records of the array are still delivered").contains("a");
                assertThatThrownBy(source::next)
                        .as("trailing: %s", trailing)
                        .isInstanceOf(JsonSourceException.class);
                assertThat(source.next()).isEmpty();
            }
        }

        @Test
        void whitespaceAfterTheArrayIsFine() {
            RecordSource source = JsonRecordSources.array(stream("[{\"id\": \"a\"}]  \n\t "));

            assertThat(next(source).get("id")).contains("a");
            assertThat(source.next()).isEmpty();
        }

        @Test
        void rootThatIsNotAnArrayFailsOnlyOnce() {
            RecordSource source = JsonRecordSources.array(stream("{\"id\": \"r1\"}"));

            assertThatThrownBy(source::next).isInstanceOf(JsonSourceException.class);
            assertThat(source.next()).isEmpty();
        }

        @Test
        void truncatedInputAbortsTheRun() {
            RecordSource source = JsonRecordSources.array(stream("[ {\"id\": \"r1\"}, "));

            next(source);
            assertThatThrownBy(source::next).isInstanceOf(JsonSourceException.class);
        }

        @Test
        void readsLazilyFromAnEndlessStream() {
            // An endless array: only a source that streams can hand out records from it.
            InputStream endless = new InputStream() {
                private final byte[] element = "{\"id\": \"x\"},".getBytes(UTF_8);
                private long position;

                @Override
                public int read() {
                    long current = position++;
                    return current == 0 ? '[' : element[(int) ((current - 1) % element.length)];
                }
            };

            RecordSource source = JsonRecordSources.array(endless);
            for (int i = 0; i < 10_000; i++) {
                assertThat(next(source).get("id")).contains("x");
            }
        }

        @Test
        void closeClosesTheStream() {
            AtomicBoolean closed = new AtomicBoolean();
            InputStream in = new ByteArrayInputStream("[]".getBytes(UTF_8)) {
                @Override
                public void close() throws IOException {
                    closed.set(true);
                    super.close();
                }
            };

            JsonRecordSources.array(in).close();

            assertThat(closed).isTrue();
        }
    }

    @Nested
    class LinesFormat {

        @Test
        void readsOneObjectPerLineSkippingBlankLines() {
            RecordSource source = JsonRecordSources.lines(stream("""
                    {"id": "r1", "country": "DE"}

                    {"id": "r2", "address": {"city": "Paris"}}
                    """));

            assertThat(next(source).get("country")).contains("DE");
            assertThat(next(source).get("address.city")).contains("Paris");
            assertThat(source.next()).isEmpty();
        }

        @Test
        void recoversFromABrokenLine() {
            RecordSource source = JsonRecordSources.lines(stream("""
                    {"id": "r1"}
                    {"id": "secret-123" oops}
                    [1, 2]
                    {"id": "r4"}
                    """));

            assertThat(next(source).get("id")).contains("r1");
            assertThatThrownBy(source::next).isInstanceOf(RecordReadException.class)
                    .hasMessage("Line 2 is not valid JSON");
            assertThatThrownBy(source::next).isInstanceOf(RecordReadException.class)
                    .hasMessage("Line 3 is ARRAY, expected a JSON object");
            assertThat(next(source).get("id")).contains("r4");
        }
    }

    @Test
    void jsonDataRecordRejectsNonObjectsAndMalformedPaths() {
        assertThatThrownBy(() -> JsonDataRecord.of(Json.MAPPER.readTree("[1]")))
                .isInstanceOf(IllegalArgumentException.class);
        DataRecord record = JsonDataRecord.of(Json.MAPPER.readTree("{\"a\": {\"b\": 1}}"));
        assertThatThrownBy(() -> record.get("a..b")).isInstanceOf(IllegalArgumentException.class);
        assertThat(record.get("a.b")).contains(1);
    }
}
