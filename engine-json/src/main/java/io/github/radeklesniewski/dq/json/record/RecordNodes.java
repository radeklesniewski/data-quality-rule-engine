package io.github.radeklesniewski.dq.json.record;

import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.record.RecordReadException;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/** Turns a parsed JSON value into a record, with one error wording for every input format. */
final class RecordNodes {

    private RecordNodes() {
    }

    /**
     * Wraps {@code node} as a record.
     *
     * @param unit what the input is made of, e.g. {@code "Line"} or {@code "Array element"}
     * @param position where {@code node} came from, in {@code unit}s
     * @throws RecordReadException if {@code node} isn't a JSON object, e.g. "Line 3 is an array, expected a JSON
     *     object"; the message gives the position and the kind of value, never the value
     */
    static DataRecord toRecord(JsonNode node, String unit, long position) {
        if (node == null || !node.isObject()) {
            throw new RecordReadException(
                    unit + " " + position + " is " + describe(node) + ", expected a JSON object", null);
        }
        return JsonDataRecord.of(node);
    }

    private static String describe(JsonNode node) {
        if (node == null) {
            return "empty";
        }
        return switch (node.getNodeType()) {
            case ARRAY -> "an array";
            case STRING -> "a string";
            case NUMBER -> "a number";
            case BOOLEAN -> "a boolean";
            case NULL -> "null";
            case MISSING -> "empty";
            default -> node.getNodeType().name().toLowerCase(Locale.ROOT);
        };
    }
}
