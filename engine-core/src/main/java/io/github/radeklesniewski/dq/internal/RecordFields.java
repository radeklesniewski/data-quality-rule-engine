package io.github.radeklesniewski.dq.internal;

import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.record.FieldTypeException;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads fields from host-supplied {@link DataRecord}s and checks their types. Every read and typed access the library
 * makes goes through here, so a misbehaving host record or a wrongly typed value fails the same way, with the same
 * message, wherever it happens. Messages name paths and types, never values. Internal.
 */
public final class RecordFields {

    private RecordFields() {
    }

    /**
     * Reads {@code path} from {@code record}.
     *
     * @param record a host record
     * @param path a valid field path; not checked again here
     * @return the value, or empty if absent
     * @throws NullPointerException if the record breaks its contract by returning {@code null}
     */
    public static Optional<Object> read(DataRecord record, String path) {
        return Objects.requireNonNull(record.get(path),
                () -> "DataRecord.get must not return null (path '" + path + "'); return Optional.empty() instead");
    }

    /**
     * Returns {@code value} as a {@code type}.
     *
     * @param path where {@code value} was read, for the message
     * @param value a present field value
     * @param type the type the caller expects
     * @return {@code value}, cast
     * @throws FieldTypeException if {@code value} isn't a {@code type}
     */
    public static <T> T as(String path, Object value, Class<T> type) {
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        throw new FieldTypeException(path, type, value.getClass());
    }
}
