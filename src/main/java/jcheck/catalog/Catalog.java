package jcheck.catalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps {@code (source, source_key)} to a stable jcheck code.
 *
 * <p>Catalog entries are data, not code: they live in TSV resources under
 * {@code /jcheck/catalog/}. Unmapped upstream diagnostics are never dropped; they receive a
 * {@code J9xxx} code derived from a hash of the key and are reported as needing curation.
 */
public final class Catalog {

    /** Resource names loaded by {@link #loadDefault()}, in order. */
    public static final List<String> DEFAULT_RESOURCES = List.of(
        "javac.tsv", "errorprone.tsv", "nullaway.tsv", "checker.tsv", "spotbugs.tsv", "jcheck.tsv");

    private static final String RESOURCE_DIR = "/jcheck/catalog/";

    private final Map<String, CatalogEntry> byKey;
    private final Map<String, CatalogEntry> byCode;

    private Catalog(Map<String, CatalogEntry> byKey) {
        this.byKey = byKey;
        Map<String, CatalogEntry> codes = new LinkedHashMap<>();
        for (CatalogEntry e : byKey.values()) {
            codes.putIfAbsent(e.code, e);
        }
        this.byCode = codes;
    }

    public static Catalog empty() {
        return new Catalog(new LinkedHashMap<>());
    }

    /** Loads the bundled catalog, tolerating absent resources. */
    public static Catalog loadDefault() {
        return load(DEFAULT_RESOURCES);
    }

    public static Catalog load(List<String> resourceNames) {
        Map<String, CatalogEntry> map = new LinkedHashMap<>();
        for (String name : resourceNames) {
            try (InputStream in = Catalog.class.getResourceAsStream(RESOURCE_DIR + name)) {
                if (in == null) {
                    continue;
                }
                parse(in, name, map);
            } catch (IOException e) {
                throw new IllegalStateException("failed to read catalog resource " + name, e);
            }
        }
        return new Catalog(map);
    }

    /** Parses a TSV stream into the given map; later rows override earlier ones. */
    public static void parse(InputStream in, String origin, Map<String, CatalogEntry> into) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        int lineNo = 0;
        while ((line = r.readLine()) != null) {
            lineNo++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            List<String> raw = splitRaw(line, '\t');
            if (raw.size() < 4) {
                throw new IllegalArgumentException(
                    origin + ":" + lineNo + ": expected at least 4 tab-separated fields, got " + raw.size());
            }
            String source = unescape(raw.get(0)).trim();
            String sourceKey = unescape(raw.get(1)).trim();
            String code = unescape(raw.get(2)).trim();
            int tier = -1;
            if (raw.size() > 3 && !raw.get(3).trim().isEmpty()) {
                try {
                    tier = Integer.parseInt(unescape(raw.get(3)).trim());
                } catch (NumberFormatException nfe) {
                    throw new IllegalArgumentException(
                        origin + ":" + lineNo + ": tier must be an integer 0..5, got '" + raw.get(3) + "'");
                }
            }
            String headline = raw.size() > 4 ? unescape(raw.get(4)) : "";
            String label = raw.size() > 5 ? unescape(raw.get(5)) : "";
            String group = raw.size() > 6 ? unescape(raw.get(6)).trim() : "";
            List<String> help = new ArrayList<>();
            if (raw.size() > 7 && !raw.get(7).isEmpty()) {
                for (String part : splitRaw(raw.get(7), '|')) {
                    String h = unescape(part);
                    if (!h.isEmpty()) {
                        help.add(h);
                    }
                }
            }
            String doc = raw.size() > 8 ? unescape(raw.get(8)).trim() : "";
            into.put(key(source, sourceKey),
                new CatalogEntry(source, sourceKey, code, tier, headline, label, group, help, doc));
        }
    }

    static String key(String source, String sourceKey) {
        return (source == null ? "" : source) + '\u0000' + (sourceKey == null ? "" : sourceKey);
    }

    public CatalogEntry lookup(String source, String sourceKey) {
        return byKey.get(key(source, sourceKey));
    }

    /** Looks up a catalog entry by its jcheck code. */
    public CatalogEntry lookupCode(String code) {
        return byCode.get(code);
    }

    /**
     * The code for an upstream key: the catalog code when mapped, otherwise a stable
     * {@code J9xxx} fallback derived from a hash of the key.
     */
    public String codeFor(String source, String sourceKey) {
        CatalogEntry e = lookup(source, sourceKey);
        if (e != null) {
            return e.code;
        }
        return fallbackCode(source, sourceKey);
    }

    /**
     * Stable code for an unmapped key. Uses FNV-1a over {@code source\0sourceKey} so the same
     * unmapped diagnostic always lands on the same {@code J9xxx} code across runs and machines.
     */
    public static String fallbackCode(String source, String sourceKey) {
        String k = (source == null ? "" : source) + '\u0000' + (sourceKey == null ? "" : sourceKey);
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < k.length(); i++) {
            hash ^= k.charAt(i);
            hash *= 0x100000001b3L;
        }
        long v = Math.floorMod(hash, 1000L);
        return String.format("J9%03d", v);
    }

    /** True when the key has an explicit catalog row. */
    public boolean isMapped(String source, String sourceKey) {
        return byKey.containsKey(key(source, sourceKey));
    }

    public Collection<CatalogEntry> entries() {
        return Collections.unmodifiableCollection(byKey.values());
    }

    public int size() {
        return byKey.size();
    }

    // ---- TSV helpers -------------------------------------------------------

    /** Splits on unescaped delimiters, preserving backslash escapes in the returned segments. */
    static List<String> splitRaw(String s, char delim) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                cur.append('\\').append(c);
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else if (c == delim) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (esc) {
            cur.append('\\');
        }
        out.add(cur.toString());
        return out;
    }

    /** Expands {@code \n}, {@code \t}, {@code \\} and {@code \|}. */
    static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case '\\': sb.append('\\'); break;
                    case '|': sb.append('|'); break;
                    default: sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
