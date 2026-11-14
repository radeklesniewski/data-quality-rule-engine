package io.github.radeklesniewski.dq.internal;

import io.github.radeklesniewski.dq.record.DataRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable {@link DataRecord} backed by a deep copy of a map. Internal: create via {@link DataRecord#ofMap(Map)}.
 */
public final class MapDataRecord implements DataRecord {

    private final Map<String, Object> fields;

    private MapDataRecord(Map<String, Object> fields) {
        this.fields = fields;
    }

    /**
     * Deep-copies {@code source} into an immutable record. {@code null} values are dropped (absent).
     *
     * @param source the fields
     * @return the record
     */
    public static MapDataRecord copyOf(Map<String, ?> source) {
        Objects.requireNonNull(source, "fields");
        return new MapDataRecord(copyMap(source));
    }

    @Override
    public Optional<Object> get(String path) {
        Object current = fields;
        for (String segment : FieldPaths.segments(path)) {
            if (!(current instanceof Map<?, ?> map)) {
                return Optional.empty(); // traversing into a scalar or list: the path does not exist
            }
            current = map.get(segment);
            if (current == null) {
                return Optional.empty();
            }
        }
        return Optional.of(current);
    }

    private static Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Record field names must be strings, got: " + entry.getKey());
            }
            Object value = copyValue(entry.getValue());
            if (value != null) {
                copy.put(key, value);
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object copyValue(Object value) {
        return switch (value) {
            case null -> null;
            case Map<?, ?> map -> copyMap(map);
            case List<?> list -> {
                List<Object> copy = new ArrayList<>(list.size());
                list.forEach(element -> copy.add(copyValue(element)));
                yield Collections.unmodifiableList(copy);
            }
            default -> value;
        };
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MapDataRecord that && fields.equals(that.fields);
    }

    @Override
    public int hashCode() {
        return fields.hashCode();
    }

    /** Field names only: values may be personal data and must not end up in logs or exception messages. */
    @Override
    public String toString() {
        return "MapDataRecord" + fields.keySet();
    }
}
