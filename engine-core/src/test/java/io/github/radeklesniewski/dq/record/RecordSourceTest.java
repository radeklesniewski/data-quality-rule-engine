package io.github.radeklesniewski.dq.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class RecordSourceTest {

    private final DataRecord a = DataRecord.ofMap(Map.of("id", "a"));
    private final DataRecord b = DataRecord.ofMap(Map.of("id", "b"));

    @Test
    void adaptsIterables() {
        RecordSource source = RecordSource.of(List.of(a, b));

        assertThat(source.next()).contains(a);
        assertThat(source.next()).contains(b);
        assertThat(source.next()).isEmpty();
        assertThat(source.next()).isEmpty();
    }

    @Test
    void nullElementIsAnUnreadableRecordNotTheEnd() {
        RecordSource source = RecordSource.of(Arrays.asList(a, null, b));

        assertThat(source.next()).contains(a);
        assertThatThrownBy(source::next).isInstanceOf(RecordReadException.class);
        assertThat(source.next()).contains(b);
    }

    @Test
    void consumesStreamsLazilyAndClosesThem() {
        AtomicBoolean closed = new AtomicBoolean();
        int[] produced = {0};
        Stream<DataRecord> stream = Stream.generate(() -> {
            produced[0]++;
            return a;
        }).onClose(() -> closed.set(true));

        try (RecordSource source = RecordSource.of(stream)) {
            source.next();
            source.next();
            assertThat(produced[0]).isEqualTo(2);
        }
        assertThat(closed).isTrue();
    }
}
