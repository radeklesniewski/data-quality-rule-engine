package io.github.radeklesniewski.dq.json.record;

import io.github.radeklesniewski.dq.record.DataRecord;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * A {@link DataRecord} that reads directly from a Jackson JSON object, without copying it into maps first.
 *
 * <p>Value mapping: strings → {@link String}, booleans → {@link Boolean}, numbers → {@link Number} (decimals are
 * {@link java.math.BigDecimal} when read by this module's sources), objects → {@code Map<String, Object>}, arrays →
 * {@code List}. A JSON {@code null} or a missing property is absent.
 *
 * <p>The node isn't copied. It must not be modified while a validation run reads the record.
 */
public final class JsonDataRecord implements DataRecord {

    private final JsonNode object;

    private JsonDataRecord(JsonNode object) {
        this.object = object;
    }

    /**
     * Wraps a JSON object node.
     *
     * @param object a node for which {@code isObject()} is true
     * @return the record
     * @throws IllegalArgumentException if {@code object} isn't a JSON object
     */
    public static JsonDataRecord of(JsonNode object) {
        Objects.requireNonNull(object, "object");
        if (!object.isObject()) {
            throw new IllegalArgumentException("A record must be a JSON object, got " + object.getNodeType());
        }
        return new JsonDataRecord(object);
    }

    @Override
    public Optional<Object> get(String path) {
        JsonNode current = object;
        for (String segment : segments(path)) {
            if (!current.isObject()) {
                return Optional.empty();
            }
            current = current.get(segment);
            if (current == null) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(JsonValues.toJava(current));
    }

    private static String[] segments(String path) {
        Objects.requireNonNull(path, "path");
        String[] segments = path.split("\\.", -1);
        for (String segment : segments) {
            if (segment.isBlank()) {
                throw new IllegalArgumentException("Invalid field path '" + path + "'");
            }
        }
        return segments;
    }

    /** Field names only: values may be personal data and must not end up in logs or exception messages. */
    @Override
    public String toString() {
        return "JsonDataRecord" + object.properties().stream().map(Map.Entry::getKey).toList();
    }
}
