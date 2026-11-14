package io.github.radeklesniewski.dq.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.record.FieldTypeException;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordFieldsTest {

    @Test
    void readsThroughTheHostRecord() {
        DataRecord record = DataRecord.ofMap(Map.of("a", "x"));

        assertThat(RecordFields.read(record, "a")).contains("x");
        assertThat(RecordFields.read(record, "b")).isEmpty();
    }

    @Test
    void castsOrReportsPathAndTypesButNeverTheValue() {
        assertThat(RecordFields.as("vatId", "DE1", String.class)).isEqualTo("DE1");
        assertThatThrownBy(() -> RecordFields.as("vatId", new BigDecimal("4711"), String.class))
                .isInstanceOfSatisfying(FieldTypeException.class, e -> {
                    assertThat(e.path()).isEqualTo("vatId");
                    assertThat(e.actualType()).isEqualTo(BigDecimal.class.getName());
                })
                .hasMessage("Field 'vatId' expected String but was BigDecimal")
                .message().doesNotContain("4711");
    }
}
