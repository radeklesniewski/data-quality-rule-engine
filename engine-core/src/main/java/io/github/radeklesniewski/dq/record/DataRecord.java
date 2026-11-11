package io.github.radeklesniewski.dq.record;

import io.github.radeklesniewski.dq.internal.MapDataRecord;
import java.util.Map;
import java.util.Optional;

/**
 * One input record (for example one business partner) as supplied by the host.
 *
 * <p>This is a deliberately minimal SPI. Hosts adapt their own representation (a JSON tree, a database row,
 * an Avro record and so on) by implementing a single method. Rule authors never use this type directly. They
 * read fields through a {@link RecordView}, which adds typed accessors and records which fields were read.
 *
 * <p>Implementations should be immutable, or at least must not change while a validation run reads them.
 * They may be read from several threads when the engine runs in a parallel mode.
 */
@FunctionalInterface
public interface DataRecord {

    /**
     * Returns the value at the given field path.
     *
     * <p>Values should be one of: {@link String}, {@link Boolean}, {@link Number},
     * {@link java.util.List}, or {@link Map} with {@code String} keys (a nested object).
     *
     * @param path a dot-separated field path, e.g. {@code "vatId"} or {@code "address.city"}
     * @return the value, or empty if the field is absent or {@code null}; never {@code null} itself
     */
    Optional<Object> get(String path);

    /**
     * Creates an immutable record from a map, e.g. one produced by a JSON parser.
     *
     * <p>The map is deep-copied: later changes to {@code fields} don't affect the record. Entries with
     * {@code null} values are treated as absent. Nested maps become nested objects addressable by path.
     *
     * @param fields field name to value; keys must be strings (nested maps included)
     * @return an immutable, thread-safe record
     * @throws IllegalArgumentException if any map key, at any depth, is not a {@code String}
     */
    static DataRecord ofMap(Map<String, ?> fields) {
        return MapDataRecord.copyOf(fields);
    }
}
