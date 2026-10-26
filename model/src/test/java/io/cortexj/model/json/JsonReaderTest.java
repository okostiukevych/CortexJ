package io.cortexj.model.json;

import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the dependency-free JSON reader (issue #241) that replaced
 * Jackson in the shared model tier.
 */
class JsonReaderTest {

    @Test
    void parsesObjectsArraysAndScalars() throws Json.JsonParseException {
        Json root = JsonReader.parse("{\"a\":1,\"b\":[true,null,\"x\"],\"c\":{\"d\":2.5}}");
        assertTrue(root.isObject());
        assertEquals(1L, root.path("a").asLong());
        assertTrue(root.path("b").isArray());
        assertEquals(3, root.path("b").size());
        assertTrue(root.path("b").get(0).asBoolean());
        assertTrue(root.path("b").get(1).isNull());
        assertEquals("x", root.path("b").get(2).asText());
        assertEquals(2.5d, root.path("c").path("d").asDouble(), 0.0d);
    }

    @Test
    void stringEscapesAndUnicode() throws Json.JsonParseException {
        Json root = JsonReader.parse(
                "{\"k\":\"line\\n\\t\\u0041\\\"q\\\\ \\ud83d\\ude00 tail\"}");
        assertEquals("line\n\tA\"q\\ 😀 tail", root.path("k").asText());
    }

    @Test
    void numericKindsAreDistinguished() throws Json.JsonParseException {
        Json root = JsonReader.parse("[42, 42.5, 10000000000]");
        assertTrue(root.get(0).isInt());
        assertEquals(42, root.get(0).asInt());
        assertFalse(root.get(1).isInt());
        assertFalse(root.get(1).isIntegralNumber());
        assertEquals(42.5d, root.get(1).asDouble(), 0.0d);
        assertTrue(root.get(2).isLong());
        assertFalse(root.get(2).isInt(), "values beyond int range are not ints");
    }

    @Test
    void missingNavigationIsNullSafe() throws Json.JsonParseException {
        Json root = JsonReader.parse("{\"a\":{}}");
        Json missing = root.path("nope");
        assertNotNull(missing);
        assertTrue(missing.isNull());
        assertEquals("fb", missing.asText("fb"));
        assertEquals("fb", root.path("a").path("deep").path("deeper").asText("fb"));
        assertFalse(root.path("nope").has("x"));
        assertEquals(7, root.path("nope").asInt(7));
    }

    @Test
    void duplicateKeysLastValueWins() throws Json.JsonParseException {
        Json root = JsonReader.parse("{\"k\":1,\"k\":2}");
        assertEquals(2L, root.path("k").asLong());
    }

    @Test
    void fieldNamesIterateInDocumentOrder() throws Json.JsonParseException {
        Json root = JsonReader.parse("{\"x\":1,\"y\":2,\"z\":3}");
        Map<String, Long> seen = new LinkedHashMap<String, Long>();
        Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            String name = fields.next();
            seen.put(name, root.path(name).asLong());
        }
        assertEquals(3, seen.size());
        assertTrue(seen.containsKey("x") && seen.containsKey("y") && seen.containsKey("z"));
    }

    @Test
    void malformedInputFailsTyped() {
        assertThrows(Json.JsonParseException.class, () -> JsonReader.parse("{"));
        assertThrows(Json.JsonParseException.class, () -> JsonReader.parse("[1,]"));
        assertThrows(Json.JsonParseException.class, () -> JsonReader.parse("{\"a\" 1}"));
        assertThrows(Json.JsonParseException.class, () -> JsonReader.parse("nul"));
        assertThrows(Json.JsonParseException.class, () -> JsonReader.parse(""));
        assertThrows(Json.JsonParseException.class, () -> JsonReader.parse("\"unterminated"));
        assertThrows(Json.JsonParseException.class, () -> JsonReader.parse("[1e999]"));
    }

    @Test
    void nestingDepthLimitIsEnforced() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < Json.Limits.defaults().maxNestingDepth() + 2; i++) {
            deep.append('[');
        }
        deep.append(']');
        Json.Limits limits = Json.Limits.defaults();
        assertThrows(Json.JsonParseException.class, () -> JsonReader.parse(deep.toString(), limits));
    }

    @Test
    void bytesStreamAndVariantsParseTheSame() throws Exception {
        byte[] bytes = "{\"v\":123}".getBytes("UTF-8");
        assertEquals(123L, JsonReader.parse(bytes).path("v").asLong());
        assertEquals(123L, JsonReader.parse(
                new java.io.ByteArrayInputStream(bytes)).path("v").asLong());
        assertEquals(123L, JsonReader.parse("{\"v\":123}").path("v").asLong());
    }

    @Test
    void trailingContentAfterFirstValueIsIgnored() throws Json.JsonParseException {
        Json root = JsonReader.parse("{\"a\":1} trailing-bytes");
        assertEquals(1L, root.path("a").asLong());
    }

    @Test
    void strictnessOptionsExistButStayLenientByDefault() throws Json.JsonParseException {
        // "," array of whitespace-empty object still parses: default JSON grammar
        Json root = JsonReader.parse(" {  \"a\" : 1 , \"b\" : [ ]  } ");
        assertEquals(1L, root.path("a").asLong());
        assertEquals(0, root.path("b").size());
    }

    @Test
    void streamExceedingMaxStringLengthFailsFast() {
        Json.Limits smallLimits = new Json.Limits(16, 16L, 16L);
        java.io.InputStream oversized = new java.io.ByteArrayInputStream(new byte[256]);
        assertThrows(Json.JsonParseException.class, () -> JsonReader.parse(oversized, smallLimits));
    }
}
