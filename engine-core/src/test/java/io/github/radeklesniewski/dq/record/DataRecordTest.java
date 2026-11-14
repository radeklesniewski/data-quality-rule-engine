package io.github.radeklesniewski.dq.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DataRecordTest {

    @Test
    void readsTopLevelAndNestedFields() {
        DataRecord record = DataRecord.ofMap(Map.of(
                "id", "r1",
                "employees", new BigDecimal("12"),
                "address", Map.of("city", "Berlin", "geo", Map.of("lat", 52.5))));

        assertThat(record.get("id")).contains("r1");
        assertThat(record.get("employees")).contains(new BigDecimal("12"));
        assertThat(record.get("address.city")).contains("Berlin");
        assertThat(record.get("address.geo.lat")).contains(52.5);
    }

    @Test
    void missingAndNullFieldsAreAbsent() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("vatId", null);
        DataRecord record = DataRecord.ofMap(fields);

        assertThat(record.get("vatId")).isEmpty();
        assertThat(record.get("nope")).isEmpty();
        assertThat(record.get("nope.deeper")).isEmpty();
    }

    @Test
    void pathThroughScalarOrListIsAbsent() {
        DataRecord record = DataRecord.ofMap(Map.of("name", "ACME", "tags", List.of("a")));

        assertThat(record.get("name.first")).isEmpty();
        assertThat(record.get("tags.0")).isEmpty();
    }

    @Test
    void emptyStringIsPresent() {
        assertThat(DataRecord.ofMap(Map.of("iban", "")).get("iban")).contains("");
    }

    @Test
    void isDeepCopiedAndImmutable() {
        Map<String, Object> address = new HashMap<>(Map.of("city", "Berlin"));
        List<Object> tags = new ArrayList<>(List.of("a"));
        Map<String, Object> fields = new HashMap<>(Map.of("address", address, "tags", tags));
        DataRecord record = DataRecord.ofMap(fields);

        address.put("city", "Paris");
        tags.add("b");
        fields.put("id", "late");

        assertThat(record.get("address.city")).contains("Berlin");
        assertThat(record.get("tags")).contains(List.of("a"));
        assertThat(record.get("id")).isEmpty();
        @SuppressWarnings("unchecked")
        List<Object> copiedTags = (List<Object>) record.get("tags").orElseThrow();
        assertThatThrownBy(() -> copiedTags.add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNonStringKeys() {
        assertThatThrownBy(() -> DataRecord.ofMap(Map.of("nested", Map.of(1, "x"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ".a", "a.", "a..b"})
    void rejectsMalformedPaths(String path) {
        DataRecord record = DataRecord.ofMap(Map.of("a", "x"));

        assertThatThrownBy(() -> record.get(path)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalByContent() {
        assertThat(DataRecord.ofMap(Map.of("a", "x"))).isEqualTo(DataRecord.ofMap(Map.of("a", "x")));
    }

    @Test
    void toStringShowsFieldNamesButNeverValues() {
        DataRecord record = DataRecord.ofMap(Map.of("iban", "DE89370400440532013000"));

        assertThat(record.toString()).contains("iban").doesNotContain("DE89");
    }
}
