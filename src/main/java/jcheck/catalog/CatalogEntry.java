package jcheck.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One row of the catalog: how an upstream {@code (source, source_key)} pair becomes a
 * jcheck-stable code, plus the presentation data the renderers use.
 */
public final class CatalogEntry {

    public final String source;
    public final String sourceKey;
    public final String code;
    /** Analysis tier 0..5, or -1 when the row does not pin one. */
    public final int tier;
    /** Headline shown after {@code error[CODE]:}; may contain {0}, {1} style placeholders. */
    public final String headline;
    /** Label text attached to the primary span. */
    public final String label;
    /**
     * Codes that mean the same thing for dedupe purposes (across tools). Empty means the code
     * is its own group.
     */
    public final String group;
    public final List<String> help;
    /** Documentation path relative to the repo root, e.g. docs/J0201.md. */
    public final String doc;

    public CatalogEntry(String source, String sourceKey, String code, int tier, String headline,
                        String label, String group, List<String> help, String doc) {
        this.source = source;
        this.sourceKey = sourceKey;
        this.code = code;
        this.tier = tier;
        this.headline = headline == null ? "" : headline;
        this.label = label == null ? "" : label;
        this.group = group == null || group.isEmpty() ? code : group;
        this.help = Collections.unmodifiableList(new ArrayList<>(help));
        this.doc = doc == null || doc.isEmpty() ? ("docs/" + code + ".md") : doc;
    }

    public String source() {
        return source;
    }

    public String sourceKey() {
        return sourceKey;
    }

    public String code() {
        return code;
    }

    public int tier() {
        return tier;
    }

    public String headline() {
        return headline;
    }

    public String label() {
        return label;
    }

    public String group() {
        return group;
    }

    public List<String> help() {
        return help;
    }

    public String doc() {
        return doc;
    }

    @Override
    public String toString() {
        return source + "/" + sourceKey + " -> " + code;
    }
}
