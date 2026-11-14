package io.github.radeklesniewski.dq.json.record;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ByteLineReaderTest {

    private static List<String> lines(String input, int bufferSize) throws IOException {
        ByteLineReader reader = new ByteLineReader(new ByteArrayInputStream(input.getBytes(UTF_8)), bufferSize);
        List<String> lines = new ArrayList<>();
        for (Optional<byte[]> line = reader.readLine(); line.isPresent(); line = reader.readLine()) {
            lines.add(new String(line.get(), UTF_8));
        }
        return lines;
    }

    @Test
    void linesSpanningBufferBoundariesAreReadWhole() throws IOException {
        assertThat(lines("first line\nsecond\n\nlast without newline", 4))
                .containsExactly("first line", "second", "", "last without newline");
    }

    @Test
    void keepsCarriageReturnsAndHasNoLinesForEmptyInput() throws IOException {
        assertThat(lines("a\r\nb\r\n", 3)).containsExactly("a\r", "b\r");
        assertThat(lines("", 3)).isEmpty();
        assertThat(lines("\n", 3)).containsExactly("");
    }

    @Test
    void countsLinesFromOne() throws IOException {
        ByteLineReader reader = new ByteLineReader(new ByteArrayInputStream("a\nb".getBytes(UTF_8)));

        reader.readLine();
        assertThat(reader.lineNumber()).isEqualTo(1);
        reader.readLine();
        assertThat(reader.lineNumber()).isEqualTo(2);
        assertThat(reader.readLine()).isEmpty();
        assertThat(reader.lineNumber()).isEqualTo(2);
    }
}
