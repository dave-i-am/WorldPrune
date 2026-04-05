package dev.minecraft.prune;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonUtilTest {

    @Test
    void emptyObject() {
        assertEquals("{\n}\n", JsonUtil.toJson(Map.of()));
    }

    @Test
    void stringValue() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("world", "survival");
        String json = JsonUtil.toJson(m);
        assertTrue(json.contains("\"world\": \"survival\""));
    }

    @Test
    void integerValue() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", 42);
        assertTrue(JsonUtil.toJson(m).contains("\"count\": 42"));
    }

    @Test
    void doubleValue() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gib", 1.234);
        assertTrue(JsonUtil.toJson(m).contains("\"gib\": 1.234"));
    }

    @Test
    void booleanValue() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dryRun", true);
        assertTrue(JsonUtil.toJson(m).contains("\"dryRun\": true"));
    }

    @Test
    void nullValue() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("optional", null);
        assertTrue(JsonUtil.toJson(m).contains("\"optional\": null"));
    }

    @Test
    void listOfStrings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", List.of("a", "b", "c"));
        String json = JsonUtil.toJson(m);
        assertTrue(json.contains("\"items\": [\"a\", \"b\", \"c\"]"));
    }

    @Test
    void stringEscapingQuoteAndBackslash() {
        assertEquals("\"say \\\"hi\\\"\"", JsonUtil.quote("say \"hi\""));
        assertEquals("\"a\\\\b\"", JsonUtil.quote("a\\b"));
    }

    @Test
    void stringEscapingControlCharacters() {
        assertEquals("\"line1\\nline2\"", JsonUtil.quote("line1\nline2"));
        assertEquals("\"col1\\tcol2\"", JsonUtil.quote("col1\tcol2"));
    }

    @Test
    void insertionOrderPreserved() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", 1);
        m.put("b", 2);
        m.put("c", 3);
        String json = JsonUtil.toJson(m);
        assertTrue(json.indexOf("\"a\"") < json.indexOf("\"b\""));
        assertTrue(json.indexOf("\"b\"") < json.indexOf("\"c\""));
    }

    @Test
    void multipleFieldsProducesValidStructure() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("world", "survival");
        m.put("keepRegions", 10);
        m.put("reclaimableGiBEstimate", 0.125);
        String json = JsonUtil.toJson(m);
        assertTrue(json.startsWith("{"));
        assertTrue(json.contains("}\n"));
        // Comma after first and second field, not after last
        assertTrue(json.contains("\"world\": \"survival\","));
        assertTrue(json.contains("\"keepRegions\": 10,"));
        assertFalse(json.contains("0.125,"));
    }
}
