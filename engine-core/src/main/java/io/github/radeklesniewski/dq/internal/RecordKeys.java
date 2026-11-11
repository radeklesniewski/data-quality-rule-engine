package io.github.radeklesniewski.dq.internal;

import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.record.FieldTypeException;
import io.github.radeklesniewski.dq.record.MissingFieldException;
import java.math.BigDecimal;
import java.util.Objects;
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
     * Returns the record id: a non-blank string, or a number rendered in plain notation.
     *
     * @param record the record
     * @return the id
     * @throws MissingFieldException if absent
     * @throws FieldTypeException if neither a string nor a number
     * @throws IllegalArgumentException if blank
     */
    public String id(DataRecord record) {
        Object value = present(record, idField).orElseThrow(() -> new MissingFieldException(idField));
        String id = switch (value) {
            case String s -> s;
            case BigDecimal d -> d.toPlainString();
            case Number n -> n.toString();
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
    public Optional<String> country(DataRecord record) {
        return present(record, countryField).map(value -> {
            if (value instanceof String s) {
                return s;
            }
            throw new FieldTypeException(countryField, String.class, value.getClass());
        });
    }

    private static Optional<Object> present(DataRecord record, String path) {
        return Objects.requireNonNull(record.get(path),
                () -> "DataRecord.get must not return null (path '" + path + "'); return Optional.empty() instead");
    }
}
