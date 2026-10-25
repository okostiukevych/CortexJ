package io.cortexj.model.json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

/**
 * Recursive-descent JSON reader for the {@code model} tier (issue #241) —
 * replaces Jackson's {@code ObjectMapper.readTree} for the narrow inputs this
 * module parses (tokenizer.json, safetensors headers).
 *
 * <p>Security properties preserved from the previous Jackson-based path:
 * <ul>
 *   <li>nesting-depth, string-length and number-length limits (hostile input
 *       fails typed, not with StackOverflow);</li>
 *   <li>no code execution, no type coercion surprises (numeric access documented
 *       in {@link Json});</li>
 *   <li>duplicate keys: last value wins (previous behaviour).</li>
 * </ul>
 */
public final class JsonReader {

    private static final int BUFFER = 64 * 1024;

    private JsonReader() {
    }

    public static Json parse(byte[] utf8) throws Json.JsonParseException {
        return parse(new StringReader(new String(utf8, StandardCharsets.UTF_8)),
                Json.Limits.defaults());
    }

    public static Json parse(byte[] utf8, Json.Limits limits) throws Json.JsonParseException {
        return parse(new StringReader(new String(utf8, StandardCharsets.UTF_8)), limits);
    }

    public static Json parse(InputStream utf8) throws Json.JsonParseException, IOException {
        return parse(utf8, Json.Limits.defaults());
    }

    public static Json parse(InputStream utf8, Json.Limits limits)
            throws Json.JsonParseException, IOException {
        byte[] bytes = readAll(utf8);
        return parse(bytes, limits);
    }

    public static Json parse(String text) throws Json.JsonParseException {
        return parse(text, Json.Limits.defaults());
    }

    public static Json parse(String text, Json.Limits limits) throws Json.JsonParseException {
        return parse(new StringReader(text), limits);
    }

    private static Json parse(Reader source, Json.Limits limits) throws Json.JsonParseException {
        PushbackReader in = new PushbackReader(source, 4);
        try {
            skipWhitespace(in);
            if (peek(in) < 0) {
                throw new Json.JsonParseException("empty JSON input");
            }
            Json value = readValue(in, limits, 1);
            // parity with the previous readTree: trailing content after the first
            // value is ignored; only whitespace/EOF is inspected here
            skipWhitespace(in);
            return value;
        } catch (IOException e) {
            throw new Json.JsonParseException("cannot read JSON: " + e.getMessage());
        } finally {
            close(in);
        }
    }

    private static Json readValue(PushbackReader in, Json.Limits limits, int depth)
            throws IOException, Json.JsonParseException {
        if (depth > limits.maxNestingDepth()) {
            throw new Json.JsonParseException(
                    "JSON nesting depth exceeds " + limits.maxNestingDepth());
        }
        int next = peek(in);
        if (next == '{') {
            return readObject(in, limits, depth);
        }
        if (next == '[') {
            return readArray(in, limits, depth);
        }
        if (next == '"') {
            return Json.ofText(readString(in, limits));
        }
        if (next == 't' || next == 'f') {
            return readBoolean(in);
        }
        if (next == 'n') {
            readLiteral(in, "null");
            return Json.nullValue();
        }
        if (next == '-' || (next >= '0' && next <= '9')) {
            return readNumber(in, limits);
        }
        throw new Json.JsonParseException("unexpected character '" + (char) next + "' in JSON");
    }

    private static Json readObject(PushbackReader in, Json.Limits limits, int depth)
            throws IOException, Json.JsonParseException {
        in.read(); // '{'
        Json object = Json.ofObject();
        skipWhitespace(in);
        if (peek(in) == '}') {
            in.read();
            return object;
        }
        while (true) {
            skipWhitespace(in);
            if (peek(in) != '"') {
                throw new Json.JsonParseException("object key must be a string");
            }
            String key = readString(in, limits);
            skipWhitespace(in);
            if (in.read() != ':') {
                throw new Json.JsonParseException("expected ':' after object key '" + key + "'");
            }
            skipWhitespace(in);
            Json value = readValue(in, limits, depth + 1);
            setField(object, key, value);
            skipWhitespace(in);
            int separator = in.read();
            if (separator == '}') {
                return object;
            }
            if (separator != ',') {
                throw new Json.JsonParseException("expected ',' or '}' in object");
            }
        }
    }

    private static Json readArray(PushbackReader in, Json.Limits limits, int depth)
            throws IOException, Json.JsonParseException {
        in.read(); // '['
        Json array = Json.ofArray();
        skipWhitespace(in);
        if (peek(in) == ']') {
            in.read();
            return array;
        }
        while (true) {
            skipWhitespace(in);
            Json value = readValue(in, limits, depth + 1);
            appendElement(array, value);
            skipWhitespace(in);
            int separator = in.read();
            if (separator == ']') {
                return array;
            }
            if (separator != ',') {
                throw new Json.JsonParseException("expected ',' or ']' in array");
            }
        }
    }

    private static void setField(Json object, String key, Json value) {
        // last-wins duplicate policy mirrors the previous mapper behaviour
        object.putForReader(key, value);
    }

    private static void appendElement(Json array, Json value) {
        array.appendForReader(value);
    }

    private static Json readBoolean(PushbackReader in) throws IOException, Json.JsonParseException {
        if (peek(in) == 't') {
            readLiteral(in, "true");
            return Json.ofBoolean(true);
        }
        readLiteral(in, "false");
        return Json.ofBoolean(false);
    }

    private static void readLiteral(PushbackReader in, String literal)
            throws IOException, Json.JsonParseException {
        for (int i = 0; i < literal.length(); i++) {
            if (in.read() != literal.charAt(i)) {
                throw new Json.JsonParseException("invalid JSON literal, expected '" + literal + "'");
            }
        }
    }

    private static Json readNumber(PushbackReader in, Json.Limits limits)
            throws IOException, Json.JsonParseException {
        StringBuilder raw = new StringBuilder();
        boolean integral = true;
        while (true) {
            int next = peek(in);
            if (next < 0) {
                break;
            }
            char c = (char) next;
            if (c >= '0' && c <= '9') {
                raw.append(c);
                in.read();
                continue;
            }
            if (c == '-' || c == '+') {
                raw.append(c);
                in.read();
                continue;
            }
            if (c == '.' || c == 'e' || c == 'E') {
                integral = false;
                raw.append(c);
                in.read();
                continue;
            }
            break;
        }
        if (raw.length() == 0) {
            throw new Json.JsonParseException("invalid JSON number");
        }
        if (raw.length() > limits.maxNumberLength()) {
            throw new Json.JsonParseException(
                    "JSON number length exceeds " + limits.maxNumberLength());
        }
        String text = raw.toString();
        try {
            if (integral) {
                return Json.ofLong(Long.parseLong(text));
            }
            double value = Double.parseDouble(text);
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new Json.JsonParseException("JSON number is not finite: " + text);
            }
            return Json.ofDouble(value);
        } catch (NumberFormatException e) {
            throw new Json.JsonParseException("invalid JSON number '" + text + "'");
        }
    }

    private static String readString(PushbackReader in, Json.Limits limits)
            throws IOException, Json.JsonParseException {
        if (in.read() != '"') {
            throw new Json.JsonParseException("expected '\"' to start a string");
        }
        StringBuilder out = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c < 0) {
                throw new Json.JsonParseException("unterminated JSON string");
            }
            if (c == '"') {
                if (out.length() > limits.maxStringLength()) {
                    throw new Json.JsonParseException(
                            "JSON string length exceeds " + limits.maxStringLength());
                }
                return out.toString();
            }
            if (c == '\\') {
                int escape = in.read();
                switch (escape) {
                    case '"':
                        out.append('"');
                        break;
                    case '\\':
                        out.append('\\');
                        break;
                    case '/':
                        out.append('/');
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        out.append(readUnicodeEscape(in));
                        break;
                    default:
                        throw new Json.JsonParseException("invalid JSON escape \\" + (char) escape);
                }
                continue;
            }
            if (c < 0x20) {
                throw new Json.JsonParseException("control character must be escaped in JSON string");
            }
            out.append((char) c);
            if (out.length() > limits.maxStringLength()) {
                throw new Json.JsonParseException(
                        "JSON string length exceeds " + limits.maxStringLength());
            }
        }
    }

    private static char[] readUnicodeEscape(PushbackReader in) throws IOException, Json.JsonParseException {
        char high = readHex4(in);
        if (Character.isHighSurrogate(high)) {
            if (peek(in) == '\\') {
                in.read();
                if (peek(in) == 'u') {
                    in.read();
                    char low = readHex4(in);
                    if (Character.isLowSurrogate(low)) {
                        return new char[] {high, low};
                    }
                    throw new Json.JsonParseException("invalid surrogate pair in JSON string");
                }
            }
            throw new Json.JsonParseException("unpaired surrogate in JSON string");
        }
        return new char[] {high};
    }

    private static char readHex4(PushbackReader in) throws IOException, Json.JsonParseException {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int c = in.read();
            int digit;
            if (c >= '0' && c <= '9') {
                digit = c - '0';
            } else if (c >= 'a' && c <= 'f') {
                digit = c - 'a' + 10;
            } else if (c >= 'A' && c <= 'F') {
                digit = c - 'A' + 10;
            } else {
                throw new Json.JsonParseException("invalid \\u escape in JSON string");
            }
            value = (value << 4) | digit;
        }
        return (char) value;
    }

    private static int peek(PushbackReader in) throws IOException {
        int next = in.read();
        if (next < 0) {
            return -1;
        }
        in.unread(next);
        return next;
    }

    private static void skipWhitespace(PushbackReader in) throws IOException {
        while (true) {
            int c = in.read();
            if (c < 0) {
                return;
            }
            if (c == 0xFEFF) {
                continue; // BOM as whitespace, matching the previous parser tolerance
            }
            if (Character.isWhitespace((char) c)) {
                continue;
            }
            in.unread(c);
            return;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(BUFFER);
        byte[] buffer = new byte[BUFFER];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void close(Reader in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // reader close failures carry no semantic value here
        }
    }
}
