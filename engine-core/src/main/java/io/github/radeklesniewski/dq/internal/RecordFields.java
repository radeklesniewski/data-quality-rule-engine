package io.github.radeklesniewski.dq.internal;

import io.github.radeklesniewski.dq.record.DataRecord;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads fields from host-supplied {@link DataRecord}s, enforcing the SPI's contract. Every read the library makes goes
 * through here, so a misbehaving host record fails the same way, with the same message, wherever it's read. Internal.
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

}
