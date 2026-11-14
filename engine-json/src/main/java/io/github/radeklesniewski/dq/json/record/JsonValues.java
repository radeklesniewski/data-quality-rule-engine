package io.github.radeklesniewski.dq.json.record;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Converts Jackson nodes to the library's plain value model (ADR-0008): strings → {@link String}, booleans →
 * {@link Boolean}, numbers → {@link Number}, objects → unmodifiable {@code Map<String, Object>}, arrays →
 * unmodifiable {@link List}. JSON {@code null} means absent: such properties are left out of maps, and array
 * elements keep their position as {@code null}.
 */
final class JsonValues {

    private JsonValues() {
    }

    /** The plain value of {@code node}, or {@code null} for JSON null or a missing node. */
    static Object toJava(JsonNode node) {
        return switch (node.getNodeType()) {
            case STRING -> node.stringValue();
            case BOOLEAN -> node.booleanValue();
            case NUMBER -> node.numberValue();
            case OBJECT -> toMap(node);
            case ARRAY -> toList(node);
            case NULL, MISSING -> null;
            case BINARY, POJO -> node.asString();
        };
    }

    private static Map<String, Object> toMap(JsonNode object) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> property : object.properties()) {
            Object value = toJava(property.getValue());
            if (value != null) {
                map.put(property.getKey(), value);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static List<Object> toList(JsonNode array) {
        List<Object> list = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            list.add(toJava(array.get(i)));
        }
        return Collections.unmodifiableList(list);
    }
}
