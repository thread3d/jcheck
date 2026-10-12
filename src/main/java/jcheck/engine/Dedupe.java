package jcheck.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jcheck.catalog.Catalog;
import jcheck.catalog.CatalogEntry;
import jcheck.model.Diagnostic;

/**
 * Merges diagnostics that describe the same defect.
 *
 * <p>Codes that share a catalog {@code group} are deduped loosely on code group plus location,
 * because different tools word the same problem differently. Codes that are their own group are
 * deduped strictly on code, location and message, which only collapses true repeats.
 *
 * <p>The highest-fidelity source wins; the others are attached as {@code related} so nothing is
 * lost from the report.
 */
public final class Dedupe {

    private Dedupe() {
    }

    public static List<Diagnostic> apply(List<Diagnostic> in, Catalog catalog) {
        Map<String, Diagnostic> winners = new LinkedHashMap<>();
        List<Diagnostic> out = new ArrayList<>();
        for (Diagnostic d : in) {
            String key = keyFor(d, catalog);
            Diagnostic existing = winners.get(key);
            if (existing == null) {
                winners.put(key, d);
                out.add(d);
                continue;
            }
            Diagnostic survivor;
            Diagnostic loser;
            if (fidelity(d) > fidelity(existing)) {
                survivor = d;
                loser = existing;
            } else {
                survivor = existing;
                loser = d;
            }
            List<Diagnostic> related = new ArrayList<>(survivor.related);
            related.addAll(loser.related);
            related.add(loser);
            Diagnostic merged = survivor.toBuilder().setRelated(related).build();
            int idx = indexOfIdentity(out, existing);
            if (idx >= 0) {
                out.set(idx, merged);
            }
            winners.put(key, merged);
        }
        return out;
    }

    private static int indexOfIdentity(List<Diagnostic> list, Diagnostic target) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == target) {
                return i;
            }
        }
        return -1;
    }

    static String keyFor(Diagnostic d, Catalog catalog) {
        String group = d.code;
        boolean shared = false;
        CatalogEntry e = catalog.lookup(d.source, d.sourceKey);
        if (e != null && !e.group.equals(e.code)) {
            group = e.group;
            shared = true;
        }
        String where = d.primary == null
            ? "-"
            : d.primary.file() + ":" + d.primary.lineStart();
        if (shared) {
            return "g:" + group + "|" + where;
        }
        return "c:" + group + "|" + where + "|" + dedupeText(d);
    }

    /**
     * The text used to decide whether two same-tool diagnostics are the same finding.
     *
     * <p>Notes are included on purpose. javac puts the specific symbol in its detail lines, so
     * "cannot find symbol" alone would merge two genuinely different unresolved names on one
     * line, while including the detail merges only true repeats — which is exactly what javac
     * emits when one missing type is mentioned twice.
     */
    static String dedupeText(Diagnostic d) {
        StringBuilder sb = new StringBuilder(normalize(d.message));
        for (String n : d.notes) {
            sb.append('\n').append(normalize(n));
        }
        return sb.toString();
    }

    static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** How much detail a source's diagnostics tend to carry; higher wins a dedupe contest. */
    static int fidelity(Diagnostic d) {
        switch (d.source == null ? "" : d.source) {
            case "checker":
                return 5;
            case "errorprone":
            case "nullaway":
                return 4;
            case "spotbugs":
            case "infer":
                return 3;
            case "javac":
                return 2;
            case "jcheck":
                return 1;
            default:
                return 0;
        }
    }
}
