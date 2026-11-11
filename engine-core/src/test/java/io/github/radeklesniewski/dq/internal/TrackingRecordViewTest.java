package io.github.radeklesniewski.dq.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.radeklesniewski.dq.FieldRead;
import io.github.radeklesniewski.dq.record.DataRecord;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TrackingRecordViewTest {

    private final TrackingRecordView view =
            new TrackingRecordView(DataRecord.ofMap(Map.of("country", "DE", "address", Map.of("city", "Berlin"))));

    @Test
    void recordsEachDistinctReadInFirstReadOrder() {
        view.getString("country");
        view.has("vatId");
        view.get("address.city");
        view.getString("country");

        assertThat(view.reads()).containsExactly(
                FieldRead.present("country", "DE"),
                FieldRead.absent("vatId"),
                FieldRead.present("address.city", "Berlin"));
    }

    @Test
    void readsAreASnapshot() {
        view.get("country");
        var snapshot = view.reads();
        view.get("vatId");

        assertThat(snapshot).hasSize(1);
        assertThat(view.reads()).hasSize(2);
    }

    @Test
    void malformedPathIsNotRecorded() {
        assertThatThrownBy(() -> view.get("")).isInstanceOf(IllegalArgumentException.class);
        assertThat(view.reads()).isEmpty();
    }

    @Test
    void rejectsHostRecordReturningNull() {
        TrackingRecordView broken = new TrackingRecordView(path -> null);

        assertThatThrownBy(() -> broken.get("x"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Optional.empty()");
    }
}
