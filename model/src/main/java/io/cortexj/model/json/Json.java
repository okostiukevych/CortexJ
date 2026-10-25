package io.cortexj.model.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON value (issue #241). Replaces the narrow
 * Jackson subset the shared {@code model} module used for tokenizer.json and
 * safetensors headers; keeps the accessor names the call sites already used.
 *
 * <p>Internal package — deliberately not exported by {@code module-info}, so
 * the shared tier ships without a JSON dependency (house rule: Jackson only in
 * cli/server/adapters).
 *
 * <p>Semantics (matching the previous behaviour, not arbitrary):
 * <ul>
 *   <li>duplicate object keys: last one wins;</li>
 *   <li>numeric access: {@link #asInt()} / {@link #asLong()} return the
 *       default when the value is not an integral number that fits the target
 *       type; {@link #asDouble()} converts integral numbers exactly and parses
 *       text that looks numeric;</li>
 *   <li>{@link #isInt()} is true only for integral values that fit an int
 *       (the previous call sites branch on exactly this).</li>
 * </ul>
 */
public final class Json implements Iterable<Json> {

    private enum Type {
        OBJECT, ARRAY, TEXT, NUMBER, BOOLEAN, NULL
    }

    /** Parse limits; hostile input must not StackOverflow or exhaust the heap. */
    public static final class Limits {
        private final int maxNestingDepth;
        private final long maxNumberLength;
        private final long maxStringLength;

        public Limits(int maxNestingDepth, long maxNumberLength, long maxStringLength) {
            if (maxNestingDepth <= 0 || maxNumberLength <= 0 || maxStringLength <= 0) {
                throw new IllegalArgumentException("limits must be positive");
            }
            this.maxNestingDepth = maxNestingDepth;
            this.maxNumberLength = maxNumberLength;
            this.maxStringLength = maxStringLength;
        }

        public static Limits defaults() {
            return new Limits(50, 10000L, 20L * 1024L * 1024L);
        }

        public int maxNestingDepth() {
            return maxNestingDepth;
        }

        public long maxNumberLength() {
            return maxNumberLength;
        }

        public long maxStringLength() {
            return maxStringLength;
        }
    }

    /** Malformed input or a limit violation (checked like the old read path). */
    public static final class JsonParseException extends Exception {
        public JsonParseException(String message) {
            super(message);
        }
    }

    private final Type type;
    private final Map<String, Json> object;
    private final List<Json> array;
    private final String text;
    private final boolean isIntegral;
    private final long longValue;
    private final double doubleValue;
    private final boolean booleanValue;

    private Json(Type type, Map<String, Json> object, List<Json> array, String text,
            boolean isIntegral, long longValue, double doubleValue, boolean booleanValue) {
        this.type = type;
        this.object = object;
        this.array = array;
        this.text = text;
        this.isIntegral = isIntegral;
        this.longValue = longValue;
        this.doubleValue = doubleValue;
        this.booleanValue = booleanValue;
    }

    private static final Json NULL_VALUE = new Json(Type.NULL, null, null, null,
            false, 0L, 0.0d, false);

    public static Json nullValue() {
        return NULL_VALUE;
    }

    public static Json ofObject() {
        return new Json(Type.OBJECT, new LinkedHashMap<String, Json>(),
                null, null, false, 0L, 0.0d, false);
    }

    public static Json ofArray() {
        return new Json(Type.ARRAY, null, new ArrayList<Json>(),
                null, false, 0L, 0.0d, false);
    }

    public static Json ofText(String value) {
        return new Json(Type.TEXT, null, null, value, false, 0L, 0.0d, false);
    }

    public static Json ofLong(long value) {
        return new Json(Type.NUMBER, null, null, null, true, value, (double) value, false);
    }

    public static Json ofDouble(double value) {
        return new Json(Type.NUMBER, null, null, null, false, 0L, value, false);
    }

    public static Json ofBoolean(boolean value) {
        return new Json(Type.BOOLEAN, null, null, null, false, 0L, 0.0d, value);
    }

    // ---------- type probes ----------

    public boolean isObject() {
        return type == Type.OBJECT;
    }

    public boolean isArray() {
        return type == Type.ARRAY;
    }

    public boolean isTextual() {
        return type == Type.TEXT;
    }

    public boolean isNumber() {
        return type == Type.NUMBER;
    }

    public boolean isIntegralNumber() {
        return isIntegral;
    }

    public boolean isInt() {
        return isIntegral && longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE;
    }

    public boolean isLong() {
        return isIntegral;
    }

    public boolean isBoolean() {
        return type == Type.BOOLEAN;
    }

    public boolean isNull() {
        return type == Type.NULL;
    }

    // ---------- object access ----------

    public boolean has(String field) {
        return type == Type.OBJECT && object.containsKey(field);
    }

    /** Null-safe navigation: missing field yields the null node (never null itself). */
    public Json path(String field) {
        if (type != Type.OBJECT) {
            return NULL_VALUE;
        }
        Json value = object.get(field);
        return value == null ? NULL_VALUE : value;
    }

    public Json get(String field) {
        if (type != Type.OBJECT) {
            return null;
        }
        return object.get(field);
    }

    public Iterator<String> fieldNames() {
        if (type != Type.OBJECT) {
            return Collections.<String>emptyList().iterator();
        }
        return Collections.unmodifiableSet(object.keySet()).iterator();
    }

    // ---------- array access ----------

    public int size() {
        return type == Type.ARRAY ? array.size() : 0;
    }

    public Json get(int index) {
        if (type != Type.ARRAY || index < 0 || index >= array.size()) {
            return null;
        }
        return array.get(index);
    }

    public Iterable<Json> elements() {
        if (type != Type.ARRAY) {
            return Collections.<Json>emptyList();
        }
        return Collections.unmodifiableList(array);
    }

    // ---------- value coercion (defaults as documented above) ----------

    public String asText() {
        switch (type) {
            case TEXT:
                return text;
            case NUMBER:
                return isIntegral ? Long.toString(longValue) : Double.toString(doubleValue);
            case BOOLEAN:
                return Boolean.toString(booleanValue);
            default:
                return "";
        }
    }

    public String asText(String fallback) {
        String value = asText();
        return value.isEmpty() && type != Type.TEXT ? fallback : value;
    }

    public int asInt() {
        return asInt(0);
    }

    public int asInt(int fallback) {
        if (isInt()) {
            return (int) longValue;
        }
        if (type == Type.TEXT) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public long asLong() {
        return asLong(0L);
    }

    public long asLong(long fallback) {
        if (isIntegral) {
            return longValue;
        }
        if (type == Type.NUMBER) {
            return (long) doubleValue;
        }
        if (type == Type.TEXT) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public double asDouble() {
        return asDouble(0.0d);
    }

    public double asDouble(double fallback) {
        if (type == Type.NUMBER) {
            return isIntegral ? (double) longValue : doubleValue;
        }
        if (type == Type.TEXT) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public boolean asBoolean() {
        return type == Type.BOOLEAN && booleanValue;
    }

    @Override
    public String toString() {
        switch (type) {
            case OBJECT:
                return object.toString();
            case ARRAY:
                return array.toString();
            default:
                return asText();
        }
    }

    @Override
    public Iterator<Json> iterator() {
        return elements().iterator();
    }

    // ---------- reader-side construction (package-private) ----------

    void putForReader(String key, Json value) {
        if (type != Type.OBJECT) {
            throw new IllegalStateException("put on a non-object node");
        }
        object.put(key, value);
    }

    void appendForReader(Json value) {
        if (type != Type.ARRAY) {
            throw new IllegalStateException("append on a non-array node");
        }
        array.add(value);
    }
}
