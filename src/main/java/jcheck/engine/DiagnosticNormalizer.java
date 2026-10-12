package jcheck.engine;

import java.util.ArrayList;
import java.util.List;
import javax.tools.Diagnostic.Kind;
import javax.tools.JavaFileObject;
import jcheck.catalog.Catalog;
import jcheck.catalog.CatalogEntry;
import jcheck.model.Diagnostic;
import jcheck.model.Level;
import jcheck.model.Span;

/**
 * Converts one javac diagnostic into the jcheck diagnostic model, consulting the catalog for
 * the stable code, tier, curated headline, span label and help lines.
 *
 * <p>javac's own message text is never thrown away: whichever of the two messages is not used
 * as the headline becomes the span label, and any further lines become notes.
 */
public final class DiagnosticNormalizer {

    public static final String SOURCE = "javac";

    private final Catalog catalog;
    private final SpanResolver spans;

    public DiagnosticNormalizer(Catalog catalog, SpanResolver spans) {
        this.catalog = catalog;
        this.spans = spans;
    }

    public Diagnostic normalize(javax.tools.Diagnostic<? extends JavaFileObject> d, int seq) {
        String key = d.getCode() == null ? "compiler.err.unknown" : d.getCode();
        Level level = levelOf(d.getKind());

        CatalogEntry entry = catalog.lookup(SOURCE, key);
        String code = entry != null ? entry.code : Catalog.fallbackCode(SOURCE, key);
        int tier = TierAssign.tierFor(SOURCE, key, entry == null ? -1 : entry.tier);

        String rawMessage = d.getMessage(null);
        if (rawMessage == null) {
            rawMessage = key;
        }
        List<String> lines = splitLines(rawMessage);
        String javacHeadline = lines.isEmpty() ? key : lines.get(0);
        List<String> detailLines = lines.size() > 1 ? lines.subList(1, lines.size()) : List.of();

        String catalogHeadline = entry == null ? "" : entry.headline;
        String catalogLabel = entry == null ? "" : entry.label;

        String message;
        String label;
        if (!catalogHeadline.isEmpty() && !catalogHeadline.contains("{")) {
            message = catalogHeadline;
            label = !catalogLabel.isEmpty() ? catalogLabel : javacHeadline;
        } else {
            message = javacHeadline;
            label = catalogLabel;
        }

        JavaFileObject src = d.getSource();
        String file = src == null ? null : src.getName();
        Span primary = spans.span(file, src, d.getStartPosition(), d.getEndPosition(),
            d.getLineNumber(), d.getColumnNumber());

        Diagnostic.Builder b = Diagnostic.builder(code)
            .source(SOURCE)
            .sourceKey(key)
            .level(level)
            .tier(tier)
            .message(message)
            .primary(primary)
            .raw(rawMessage)
            .seq(seq);

        if (!label.isEmpty()) {
            b.label(primary, label);
        }
        for (String detail : detailLines) {
            b.note(detail);
        }
        if (entry != null) {
            for (String h : entry.help) {
                b.help(h);
            }
        }
        if (entry == null) {
            b.note("unmapped upstream key; catalog curation pending");
        }
        return b.build();
    }

    private static Level levelOf(Kind kind) {
        switch (kind) {
            case ERROR:
                return Level.ERROR;
            case WARNING:
            case MANDATORY_WARNING:
                return Level.WARNING;
            case NOTE:
            case OTHER:
            default:
                return Level.NOTE;
        }
    }

    /** Splits a javac message into its logical lines, dropping blank ones but keeping order. */
    static List<String> splitLines(String message) {
        List<String> out = new ArrayList<>();
        for (String line : message.split("\n", -1)) {
            String t = line.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
