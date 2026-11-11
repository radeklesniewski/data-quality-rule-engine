package io.github.radeklesniewski.dq.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordViewTest {

    private final RecordView view = RecordView.of(DataRecord.ofMap(Map.of(
            "vatId", "DE111111111",
            "iban", "",
            "employees", new BigDecimal("3"))));

    @Test
    void optionalAccessorsTreatAbsenceAsNormal() {
        assertThat(view.getString("vatId")).contains("DE111111111");
        assertThat(view.getString("missing")).isEmpty();
        assertThat(view.has("iban")).isTrue();
        assertThat(view.has("missing")).isFalse();
    }

    @Test
    void requireAccessorsTreatAbsenceAsFault() {
        assertThat(view.requireString("vatId")).isEqualTo("DE111111111");
        assertThat(view.require("employees")).isEqualTo(new BigDecimal("3"));
        assertThatThrownBy(() -> view.requireString("missing"))
                .isInstanceOfSatisfying(MissingFieldException.class, e -> assertThat(e.path()).isEqualTo("missing"))
                .hasMessage("Required field 'missing' is missing");
    }

    @Test
    void wrongTypeIsAFaultAndMessageHasNoValue() {
        assertThatThrownBy(() -> view.getString("employees"))
                .isInstanceOfSatisfying(FieldTypeException.class, e -> {
                    assertThat(e.path()).isEqualTo("employees");
                    assertThat(e.expectedType()).isEqualTo("java.lang.String");
                    assertThat(e.actualType()).isEqualTo("java.math.BigDecimal");
                })
                .hasMessage("Field 'employees' expected String but was BigDecimal");
    }

    @Test
    void validatesPaths() {
        assertThatThrownBy(() -> view.get("a..b")).isInstanceOf(IllegalArgumentException.class);
    }
}
