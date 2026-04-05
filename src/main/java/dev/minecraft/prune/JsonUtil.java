package dev.minecraft.prune;

import java.util.List;
import java.util.Map;

/**
 * Minimal JSON serialiser for report summary files.
 * Handles String, Number, Boolean, List&lt;String&gt;, and null values.
 * No external dependencies required.
 */
final class JsonUtil {

    private JsonUtil() {}

    /**
     * Produce a pretty-printed JSON object from an ordered map.
     * Insertion order is preserved (use {@link java.util.LinkedHashMap}).
     */
    static String toJson(Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            sb.append("  ").append(quote(entry.getKey())).append(": ");
            sb.append(encodeValue(entry.getValue()));
            if (++i < fields.size()) sb.append(",");
            sb.append("\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String encodeValue(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean || v instanceof Number) return v.toString();
        if (v instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                sb.append(quote(String.valueOf(list.get(i))));
                if (i < list.size() - 1) sb.append(", ");
            }
            sb.append("]");
            return sb.toString();
        }
        return quote(v.toString());
    }

    static String quote(String s) {
        return "\"" + s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }
}
