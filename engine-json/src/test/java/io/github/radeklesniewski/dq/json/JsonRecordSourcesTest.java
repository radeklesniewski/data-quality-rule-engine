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
import java.io.UncheckedIOException;
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
                    .hasMessage("Line 3 is an array, expected a JSON object");
            assertThat(next(source).get("id")).contains("r4");
        }

        @Test
        void invalidUtf8MakesOnlyThatLineUnreadableInsteadOfAlteringTheValue() {
            byte[] input = "{\"id\": \"r1\", \"name\": \"AXB\"}\n{\"id\": \"r2\"}\n".getBytes(UTF_8);
            input[23] = (byte) 0xFF; // the 'X' in "AXB"
            RecordSource source = JsonRecordSources.lines(new ByteArrayInputStream(input));

            assertThatThrownBy(source::next).isInstanceOf(RecordReadException.class)
                    .hasMessage("Line 1 is not valid JSON");
            assertThat(next(source).get("id")).contains("r2");
        }

        @Test
        void acceptsAByteOrderMarkCrlfAndAMissingFinalNewline() {
            RecordSource source = JsonRecordSources.lines(withBom("{\"id\": \"r1\"}\r\n\r\n{\"id\": \"r2\"}"));

            assertThat(next(source).get("id")).contains("r1");
            assertThat(next(source).get("id")).contains("r2");
            assertThat(source.next()).isEmpty();
        }

        @Test
        void longLinesSpanningSeveralBuffersAreReadWhole() {
            String name = "x".repeat(200_000);
            RecordSource source = JsonRecordSources.lines(stream("{\"name\": \"" + name + "\"}\n{\"id\": \"r2\"}"));

            assertThat(next(source).get("name")).contains(name);
            assertThat(next(source).get("id")).contains("r2");
        }

        @Test
        void aFailingStreamIsAnIoErrorNotABadLine() {
            RecordSource source = JsonRecordSources.lines(failingAfter("{\"id\": \"r1\"}\n"));

            assertThat(next(source).get("id")).contains("r1");
            assertThatThrownBy(source::next).isInstanceOf(UncheckedIOException.class)
                    .hasRootCauseMessage("connection reset");
        }
    }

    @Nested
    class Encoding {

        @Test
        void arrayAcceptsAByteOrderMark() {
            assertThat(next(JsonRecordSources.array(withBom("[{\"id\": \"r1\"}]"))).get("id")).contains("r1");
        }

        @Test
        void aFailingStreamInAnArrayIsAnIoErrorNotMalformedJson() {
            RecordSource source = JsonRecordSources.array(failingAfter("[{\"id\": \"r1\"},"));

            assertThat(next(source).get("id")).contains("r1");
            assertThatThrownBy(source::next).isInstanceOf(UncheckedIOException.class)
                    .hasMessage("Failed to read JSON input")
                    .hasRootCauseMessage("connection reset");
            assertThat(source.next()).as("source stays finished after a fatal error").isEmpty();
        }
    }

    private static InputStream withBom(String text) {
        byte[] body = text.getBytes(UTF_8);
        byte[] bytes = new byte[body.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bytes, 3, body.length);
        return new ByteArrayInputStream(bytes);
    }

    /** Delivers {@code text}, then fails like a dropped connection. */
    private static InputStream failingAfter(String text) {
        byte[] bytes = text.getBytes(UTF_8);
        return new InputStream() {
            private int position;

            @Override
            public int read() throws IOException {
                if (position < bytes.length) {
                    return bytes[position++];
                }
                throw new IOException("connection reset");
            }
        };
    }

    @Test
    void jsonDataRecordRejectsNonObjectsAndMalformedPaths() {
        assertThatThrownBy(() -> JsonDataRecord.of(Json.MAPPER.readTree("[1]")))
                .isInstanceOf(IllegalArgumentException.class);
        DataRecord record = JsonDataRecord.of(Json.MAPPER.readTree("{\"a\": {\"b\": 1}}"));
        assertThatThrownBy(() -> record.get("a..b")).isInstanceOf(IllegalArgumentException.class);
        assertThat(record.get("a.b")).contains(1);
    }

    @Test
    void jsonDataRecordToStringShowsFieldNamesButNeverValues() {
        DataRecord record = JsonDataRecord.of(Json.MAPPER.readTree("{\"id\": \"r1\", \"iban\": \"DE89370400440532013000\"}"));

        assertThat(record.toString()).isEqualTo("JsonDataRecord[id, iban]");
    }
}
