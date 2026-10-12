package jcheck.explain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import jcheck.catalog.Catalog;
import jcheck.catalog.CatalogEntry;
import jcheck.model.Tier;

/**
 * {@code jcheck explain <code>}: the long form of a diagnostic.
 *
 * <p>Prefers the project's own {@code docs/<code>.md}, then the copy bundled with jcheck, then a
 * catalog-derived summary so a code is never a dead end.
 */
public final class Explainer {

    public static final class Result {
        public final int exitCode;
        public final String text;

        public Result(int exitCode, String text) {
            this.exitCode = exitCode;
            this.text = text;
        }
    }

    private Explainer() {
    }

    public static Result explain(String rootStr, String rawCode) {
        String code = normalize(rawCode);
        if (code == null) {
            return new Result(2, "jcheck: expected a diagnostic code, e.g. `jcheck explain J0201`\n");
        }
        Path root = Path.of(rootStr == null ? "." : rootStr).toAbsolutePath().normalize();

        String doc = readProjectDoc(root, code);
        if (doc == null) {
            doc = readBundledDoc(code);
        }
        if (doc != null) {
            return new Result(0, doc.endsWith("\n") ? doc : doc + "\n");
        }

        Catalog catalog = Catalog.loadDefault();
        CatalogEntry entry = catalog.lookupCode(code);
        if (entry == null) {
            return new Result(2, "jcheck: unknown diagnostic code '" + rawCode + "'\n");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(code).append('\n').append('\n');
        if (!entry.headline().isEmpty()) {
            sb.append(entry.headline()).append("\n\n");
        }
        sb.append("source: ").append(entry.source())
          .append(" (").append(entry.sourceKey()).append(")\n");
        sb.append("tier: T").append(entry.tier()).append(' ').append(Tier.name(entry.tier())).append("\n\n");
        for (String h : entry.help()) {
            sb.append("- ").append(h).append('\n');
        }
        sb.append("\nno long-form page for ").append(code)
          .append("; add docs/").append(code).append(".md to this project to fill it in\n");
        return new Result(0, sb.toString());
    }

    private static String readProjectDoc(Path root, String code) {
        for (Path candidate : new Path[] {
                root.resolve("docs").resolve(code + ".md"),
                root.resolve(code + ".md")}) {
            try {
                if (Files.isRegularFile(candidate)) {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                }
            } catch (IOException ignored) {
                // fall through to the bundled copy
            }
        }
        return null;
    }

    private static String readBundledDoc(String code) {
        try (InputStream in = Explainer.class.getResourceAsStream("/jcheck/docs/" + code + ".md")) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Accepts {@code j0201}, {@code 201} and {@code J0201} alike. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        if (s.startsWith("J")) {
            s = s.substring(1);
        }
        if (!s.matches("\\d{1,4}")) {
            return null;
        }
        while (s.length() < 4) {
            s = "0" + s;
        }
        return "J" + s;
    }
}
