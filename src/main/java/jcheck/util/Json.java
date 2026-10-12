package jcheck.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small strict JSON reader.
 *
 * <p>jcheck previously only needed to <em>write</em> JSON ({@link JsonWriter}); MCP needs to read
 * it, so this is the missing half. It parses into {@code Map}, {@code List}, {@code String},
 * {@code Long}, {@code Double}, {@code Boolean} and {@code null} — no bindings, no dependencies.
 *
 * <p>It is strict on purpose: single quotes, trailing commas, unquoted keys, {@code NaN} and raw
 * control characters in strings are all rejected. A protocol parser that guesses is worse than
 * one that fails loudly.
 */
public final class Json {

    /** Thrown on any malformed input, with the byte offset so the caller can report it. */
    public static final class ParseException extends RuntimeException {
        public final int offset;

        public ParseException(String message, int offset) {
            super(message + " at offset " + offset);
            this.offset = offset;
        }
    }

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        String input = text == null ? "" : text;
        Json p = new Json(input);
        p.ws();
        if (p.i >= input.length()) {
            throw new ParseException("empty document", p.i);
        }
        Object v = p.value();
        p.ws();
        if (p.i != input.length()) {
            throw new ParseException("trailing data", p.i);
        }
        return v;
    }

    public static Map<String, Object> parseObject(String text) {
        Object o = parse(text);
        if (!(o instanceof Map)) {
            throw new ParseException("expected a JSON object", 0);
        }
        return asObject(o);
    }

    // ---- accessors ---------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    /** The value at {@code key} as a String, or null when absent or not a String. */
    public static String optString(Map<String, Object> m, String key) {
        Object o = m == null ? null : m.get(key);
        return o instanceof String ? (String) o : null;
    }

    /** The value at {@code key} as a nested object, or null. */
    public static Map<String, Object> optObject(Map<String, Object> m, String key) {
        return m == null ? null : asObject(m.get(key));
    }

    public static boolean has(Map<String, Object> m, String key) {
        return m != null && m.containsKey(key);
    }

    public static boolean isNull(Map<String, Object> m, String key) {
        return m == null || m.get(key) == null;
    }

    // ---- parser ------------------------------------------------------------

    private Object value() {
        if (i >= s.length()) {
            throw new ParseException("unexpected end of input", i);
        }
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': literal("true"); return Boolean.TRUE;
            case 'f': literal("false"); return Boolean.FALSE;
            case 'n': literal("null"); return null;
            default:
                if (c == '-' || isDigit(c)) {
                    return number();
                }
                throw new ParseException("invalid JSON value near '" + snippet() + "'", i);
        }
    }

    /** A short, single-line excerpt of the remaining input, for error messages. */
    private String snippet() {
        int end = Math.min(s.length(), i + 16);
        String raw = s.substring(i, end);
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < raw.length(); k++) {
            char c = raw.charAt(k);
            sb.append(c == '\n' ? ' ' : c == '\r' ? ' ' : c == '\t' ? ' ' : c);
        }
        return sb + (end < s.length() ? "..." : "");
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<>();
        expect('{');
        ws();
        if (peek() == '}') {
            i++;
            return out;
        }
        while (true) {
            ws();
            if (peek() != '"') {
                throw new ParseException("expected a quoted key", i);
            }
            String key = string();
            ws();
            expect(':');
            Object v = value();
            out.put(key, v);
            ws();
            char c = peek();
            if (c == ',') {
                i++;
                continue;
            }
            expect('}');
            return out;
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<>();
        expect('[');
        ws();
        if (peek() == ']') {
            i++;
            return out;
        }
        while (true) {
            out.add(value());
            ws();
            char c = peek();
            if (c == ',') {
                i++;
                continue;
            }
            expect(']');
            return out;
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw new ParseException("unterminated string", i);
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                if (c < 0x20) {
                    throw new ParseException("raw control character in string", i - 1);
                }
                out.append(c);
                continue;
            }
            if (i >= s.length()) {
                throw new ParseException("truncated escape", i);
            }
            char e = s.charAt(i++);
            switch (e) {
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) {
                        throw new ParseException("truncated \\u escape", i);
                    }
                    String hex = s.substring(i, i + 4);
                    try {
                        out.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException nfe) {
                        throw new ParseException("bad \\u escape '" + hex + "'", i);
                    }
                    i += 4;
                    break;
                default:
                    throw new ParseException("invalid escape '\\" + e + "'", i - 1);
            }
        }
    }

    private Object number() {
        int start = i;
        boolean floating = false;
        if (peek() == '-') {
            i++;
        }
        if (peek() == '0') {
            i++;
        } else if (isDigit(peek())) {
            while (isDigit(peek())) {
                i++;
            }
        } else {
            throw new ParseException("invalid number", start);
        }
        if (peek() == '.') {
            floating = true;
            i++;
            if (!isDigit(peek())) {
                throw new ParseException("expected a digit after '.'", i);
            }
            while (isDigit(peek())) {
                i++;
            }
        }
        if (peek() == 'e' || peek() == 'E') {
            floating = true;
            i++;
            if (peek() == '+' || peek() == '-') {
                i++;
            }
            if (!isDigit(peek())) {
                throw new ParseException("expected an exponent digit", i);
            }
            while (isDigit(peek())) {
                i++;
            }
        }
        String token = s.substring(start, i);
        try {
            if (floating) {
                return Double.valueOf(token);
            }
            return Long.valueOf(token);
        } catch (NumberFormatException e) {
            throw new ParseException("invalid number '" + token + "'", start);
        }
    }

    private void literal(String lit) {
        if (!s.startsWith(lit, i)) {
            throw new ParseException("invalid JSON value near '" + snippet() + "'", i);
        }
        i += lit.length();
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private void expect(char c) {
        if (peek() != c) {
            throw new ParseException("expected '" + c + "'", i);
        }
        i++;
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else {
                break;
            }
        }
    }
}
