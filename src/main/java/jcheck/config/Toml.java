package jcheck.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small TOML reader covering the subset jcheck.toml needs: tables, dotted table headers,
 * basic and literal strings, integers, booleans, arrays and inline tables.
 *
 * <p>Arrays of tables ({@code [[x]]}), dates and floats are not supported; an unsupported value
 * is preserved as its raw text rather than failing the parse, so an unknown key never blocks a
 * check run.
 */
public final class Toml {

    private Toml() {
    }

    public static Map<String, Object> parseFile(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static Map<String, Object> parse(String text) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> current = root;
        for (String logical : logicalLines(text)) {
            String line = stripComment(logical).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                String path = line.substring(1, line.length() - 1).trim();
                if (path.startsWith("[")) {
                    path = path.substring(1).trim();
                }
                current = navigate(root, path);
                continue;
            }
            int eq = indexOfTopLevel(line, '=');
            if (eq < 0) {
                throw new IllegalArgumentException("not a key/value line: " + line);
            }
            String key = unquoteKey(line.substring(0, eq).trim());
            String value = line.substring(eq + 1).trim();
            current.put(key, parseValue(value));
        }
        return root;
    }

    /** Joins lines until brackets and braces balance, so multi-line arrays parse. */
    private static List<String> logicalLines(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        char quote = 0;
        for (String line : text.split("\n", -1)) {
            String stripped = stripComment(line);
            for (int i = 0; i < stripped.length(); i++) {
                char c = stripped.charAt(i);
                if (inString) {
                    if (c == quote && (i == 0 || stripped.charAt(i - 1) != '\\')) {
                        inString = false;
                    }
                } else if (c == '"' || c == '\'') {
                    inString = true;
                    quote = c;
                } else if (c == '[' || c == '{') {
                    depth++;
                } else if (c == ']' || c == '}') {
                    depth--;
                }
            }
            cur.append(line).append('\n');
            if (depth <= 0 && !inString) {
                out.add(cur.toString());
                cur.setLength(0);
                depth = 0;
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    static String stripComment(String line) {
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inString) {
                if (c == quote && (i == 0 || line.charAt(i - 1) != '\\')) {
                    inString = false;
                }
            } else if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
            } else if (c == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    /** First index of {@code delim} outside strings and outside [] / {}. */
    static int indexOfTopLevel(String s, char delim) {
        boolean inString = false;
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == quote && (i == 0 || s.charAt(i - 1) != '\\')) {
                    inString = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
            } else if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
            } else if (c == delim && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    static List<String> splitTopLevel(String s, char delim) {
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start <= s.length()) {
            int idx = indexOfTopLevel(s.substring(start), delim);
            if (idx < 0) {
                out.add(s.substring(start));
                break;
            }
            out.add(s.substring(start, start + idx));
            start = start + idx + 1;
        }
        return out;
    }

    static Object parseValue(String v) {
        String s = v.trim();
        if (s.isEmpty()) {
            return "";
        }
        char c = s.charAt(0);
        if (c == '"') {
            return parseBasicString(s);
        }
        if (c == '\'') {
            return parseLiteralString(s);
        }
        if ("true".equals(s)) {
            return Boolean.TRUE;
        }
        if ("false".equals(s)) {
            return Boolean.FALSE;
        }
        if (c == '[') {
            return parseArray(s);
        }
        if (c == '{') {
            return parseInlineTable(s);
        }
        try {
            return Long.parseLong(s.replace("_", ""));
        } catch (NumberFormatException ignored) {
            return s;
        }
    }

    private static List<Object> parseArray(String s) {
        List<Object> out = new ArrayList<>();
        String inner = s.trim();
        if (inner.startsWith("[")) {
            inner = inner.substring(1);
        }
        if (inner.endsWith("]")) {
            inner = inner.substring(0, inner.length() - 1);
        }
        for (String part : splitTopLevel(inner, ',')) {
            String p = part.trim();
            if (!p.isEmpty()) {
                out.add(parseValue(p));
            }
        }
        return out;
    }

    private static Map<String, Object> parseInlineTable(String s) {
        Map<String, Object> out = new LinkedHashMap<>();
        String inner = s.trim();
        if (inner.startsWith("{")) {
            inner = inner.substring(1);
        }
        if (inner.endsWith("}")) {
            inner = inner.substring(0, inner.length() - 1);
        }
        for (String part : splitTopLevel(inner, ',')) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int eq = indexOfTopLevel(p, '=');
            if (eq < 0) {
                throw new IllegalArgumentException("bad inline table entry: " + p);
            }
            out.put(unquoteKey(p.substring(0, eq).trim()), parseValue(p.substring(eq + 1)));
        }
        return out;
    }

    private static String parseBasicString(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': out.append('\n'); break;
                    case 't': out.append('\t'); break;
                    case 'r': out.append('\r'); break;
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    default: out.append(n);
                }
            } else if (c == '"') {
                break;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String parseLiteralString(String s) {
        int end = s.indexOf('\'', 1);
        return end < 0 ? s.substring(1) : s.substring(1, end);
    }

    private static String unquoteKey(String k) {
        if (k.length() >= 2) {
            char c = k.charAt(0);
            if ((c == '"' || c == '\'') && k.charAt(k.length() - 1) == c) {
                return k.substring(1, k.length() - 1);
            }
        }
        return k;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> navigate(Map<String, Object> root, String dottedPath) {
        Map<String, Object> cur = root;
        for (String part : dottedPath.split("\\.")) {
            String key = unquoteKey(part.trim());
            if (key.isEmpty()) {
                continue;
            }
            Object existing = cur.get(key);
            if (existing instanceof Map) {
                cur = (Map<String, Object>) existing;
            } else {
                Map<String, Object> next = new LinkedHashMap<>();
                cur.put(key, next);
                cur = next;
            }
        }
        return cur;
    }

    // ---- typed accessors ---------------------------------------------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> table(Map<String, Object> root, String dottedPath) {
        Object o = root;
        for (String part : dottedPath.split("\\.")) {
            if (!(o instanceof Map)) {
                return Map.of();
            }
            o = ((Map<String, Object>) o).get(part);
        }
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    public static String str(Map<String, Object> t, String key, String def) {
        Object o = t.get(key);
        return o == null ? def : String.valueOf(o);
    }

    public static Integer integer(Map<String, Object> t, String key, Integer def) {
        Object o = t.get(key);
        if (o instanceof Long) {
            return ((Long) o).intValue();
        }
        if (o instanceof String) {
            try {
                return Integer.parseInt(((String) o).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static Boolean bool(Map<String, Object> t, String key, Boolean def) {
        Object o = t.get(key);
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o instanceof String) {
            String s = ((String) o).trim();
            if ("true".equalsIgnoreCase(s)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(s)) {
                return Boolean.FALSE;
            }
        }
        return def;
    }

    public static List<String> strList(Map<String, Object> t, String key, List<String> def) {
        Object o = t.get(key);
        if (o instanceof List) {
            List<String> out = new ArrayList<>();
            for (Object item : (List<?>) o) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
            return out;
        }
        if (o instanceof String) {
            return List.of((String) o);
        }
        return def;
    }

    public static List<String> keys(Map<String, Object> t) {
        return new ArrayList<>(t.keySet());
    }
}
