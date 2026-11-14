package io.github.radeklesniewski.dq.internal;

import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.record.FieldTypeException;
import io.github.radeklesniewski.dq.record.MissingFieldException;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * Extracts the engine-level keys of a record: its id (required) and country (optional). Internal.
 *
 * @param idField path of the id field
 * @param countryField path of the country field
 */
public record RecordKeys(String idField, String countryField) {

    /** Validates the paths. */
    public RecordKeys {
        FieldPaths.requireValid(idField);
        FieldPaths.requireValid(countryField);
    }

    /**
     * Returns the record id: a non-blank string, or a number rendered in plain notation (never with an exponent,
     * whatever its Java type: {@code 1.2345678E7} becomes {@code "12345678"}).
     *
     * @param record the record
     * @return the id
     * @throws MissingFieldException if absent
     * @throws FieldTypeException if neither a string nor a number
     * @throws IllegalArgumentException if blank, or a number that isn't finite
     */
    String id(DataRecord record) {
        Object value = RecordFields.read(record, idField).orElseThrow(() -> new MissingFieldException(idField));
        String id = switch (value) {
            case String s -> s;
            case Number n -> plain(n);
            default -> throw new FieldTypeException(idField, String.class, value.getClass());
        };
        if (id.isBlank()) {
            throw new IllegalArgumentException("Record id field '" + idField + "' is blank");
        }
        return id;
    }

    /**
     * Returns the record country.
     *
     * @param record the record
     * @return the country, or empty if absent
     * @throws FieldTypeException if present but not a string
     */
    Optional<String> country(DataRecord record) {
        return RecordFields.read(record, countryField).map(value -> RecordFields.as(countryField, value, String.class));
    }

    /** One rendering for every numeric type, via its decimal value; {@code Double.toString} would use exponents. */
    private String plain(Number number) {
        if (number instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        try {
            return new BigDecimal(number.toString()).toPlainString();
        } catch (NumberFormatException e) { // NaN, Infinity, or a custom Number type without a decimal rendering
            throw new IllegalArgumentException("Record id field '" + idField + "' is not a finite number", e);
        }
    }
}
