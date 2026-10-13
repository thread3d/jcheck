package jcheck.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jcheck.catalog.Catalog;
import jcheck.model.CheckReport;
import jcheck.model.Diagnostic;
import jcheck.model.Edit;
import jcheck.model.Label;
import jcheck.model.Span;
import jcheck.model.Suggestion;
import jcheck.model.Tier;
import jcheck.model.TierReport;
import jcheck.plugins.ErrorProneRunner;

/**
 * The one place findings are merged: run the tiers, dedupe, drop cascades, apply the level
 * policy, order deterministically, and state which tiers actually ran.
 */
public final class Engine {

    private Engine() {
    }

    public static CheckReport run(CheckOptions opts) {
        return run(opts, Catalog.loadDefault());
    }

    public static CheckReport run(CheckOptions opts, Catalog catalog) {
        List<String> toolNotes = new ArrayList<>(opts.toolNotes);
        List<String> configErrors = new ArrayList<>();

        if (opts.sources.isEmpty()) {
            configErrors.add("no Java sources found; pass --sources or configure the project source roots");
            return new CheckReport(List.of(), List.of(), List.of(),
                tierReport(opts, false, Integer.MAX_VALUE, false), toolNotes, configErrors);
        }

        JavacRunner.Result run = JavacRunner.run(opts, catalog);
        toolNotes.addAll(run.notes);

        List<Diagnostic> all = new ArrayList<>(run.diagnostics);

        /*
         * Phase gating must be enforced here, not left to javac's task events. With
         * -XDshould-stop.ifError=FLOW javac keeps going after a syntax error and can still reach
         * ANALYZE for a file it only partially parsed, so a T3 check would happily report on
         * broken code. An error in T0-T2 means T3+ did not run, whatever javac recovered from.
         *
         * Only javac's own errors gate the higher tiers. A T2 finding from jcheck itself (J0003,
         * a policy annotation) does not affect attribution, so it must not be read as "the code
         * did not compile" and silence the pack.
         */
        List<Diagnostic> suppressedHigherTier = new ArrayList<>();
        int blockingTier = Integer.MAX_VALUE;
        for (Diagnostic d : all) {
            if (d.isError() && "javac".equals(d.source)) {
                blockingTier = Math.min(blockingTier, d.tier);
            }
        }
        if (blockingTier <= Tier.T2) {
            List<Diagnostic> keep = new ArrayList<>(all.size());
            for (Diagnostic d : all) {
                if (d.tier > Tier.T2) {
                    suppressedHigherTier.add(d);
                } else {
                    keep.add(d);
                }
            }
            all = keep;
        }

        /*
         * Error Prone and NullAway run in a forked JVM with the module exports they need. They are
         * only attempted when the compile got past flow analysis, because a plugin run over broken
         * code would report noise; the tier line then says T3 did not run and why.
         */
        boolean t3Ran = false;
        if (blockingTier > Tier.T2) {
            t3Ran = opts.nativeChecks;
            if (opts.errorProne) {
                ErrorProneRunner.Result plugins = ErrorProneRunner.run(opts, catalog, new SpanResolver());
                all.addAll(plugins.diagnostics);
                toolNotes.addAll(plugins.notes);
                t3Ran = t3Ran || plugins.ran;
            }
        }

        List<Diagnostic> ordered = new ArrayList<>(all);
        ordered.sort(Comparator.comparingInt(d -> d.seq));

        List<Diagnostic> deduped = Dedupe.apply(ordered, catalog);
        CascadeFilter.Result cascaded = CascadeFilter.apply(deduped);

        List<Diagnostic> dropped = new ArrayList<>(suppressedHigherTier);
        dropped.addAll(cascaded.dropped);

        List<Diagnostic> gated = new ArrayList<>();
        for (Diagnostic d : cascaded.kept) {
            Diagnostic g = opts.levelPolicy.apply(d);
            if (g != null) {
                gated.add(g);
            }
        }
        DiagnosticOrder.sort(gated);

        boolean anyErrors = false;
        for (Diagnostic d : gated) {
            if (d.isError()) {
                anyErrors = true;
                break;
            }
        }

        TierReport tiers = tierReport(opts, anyErrors, blockingTier, t3Ran);
        return new CheckReport(
            relativize(gated, opts.rootDir),
            relativize(dropped, opts.rootDir),
            scanSuppressions(opts),
            tiers,
            toolNotes,
            configErrors);
    }

    private static TierReport tierReport(CheckOptions opts, boolean anyErrors, int blockingTier,
                                         boolean t3Ran) {
        List<TierReport.Entry> entries = new ArrayList<>();
        for (int t = 0; t <= Tier.MAX; t++) {
            boolean enabled = opts.tierEnabled(t);
            boolean ran;
            String note = "";
            if (!enabled) {
                ran = false;
                note = "not enabled";
            } else if (blockingTier <= Tier.T2 && t > blockingTier) {
                ran = false;
                note = "compile errors";
            } else if (t == Tier.T3 && !t3Ran) {
                ran = false;
                note = "plugin unavailable";
            } else {
                ran = true;
            }
            entries.add(new TierReport.Entry(t, ran, note));
        }
        return new TierReport(entries, anyErrors);
    }

    /** Strips the project root prefix from every span so output is stable across machines. */
    static List<Diagnostic> relativize(List<Diagnostic> in, String root) {
        if (root == null || root.isEmpty()) {
            return in;
        }
        String prefix = root.endsWith("/") ? root : root + "/";
        List<Diagnostic> out = new ArrayList<>(in.size());
        for (Diagnostic d : in) {
            out.add(relativize(d, prefix));
        }
        return out;
    }

    private static Diagnostic relativize(Diagnostic d, String prefix) {
        List<Label> labels = new ArrayList<>(d.labels.size());
        for (Label l : d.labels) {
            labels.add(new Label(strip(l.span(), prefix), l.text()));
        }
        List<Suggestion> suggestions = new ArrayList<>(d.suggestions.size());
        for (Suggestion s : d.suggestions) {
            List<Edit> edits = new ArrayList<>(s.edits().size());
            for (Edit e : s.edits()) {
                edits.add(new Edit(strip(e.span(), prefix), e.replacement()));
            }
            suggestions.add(new Suggestion(s.applicability(), s.message(), edits));
        }
        List<Diagnostic> related = new ArrayList<>(d.related.size());
        for (Diagnostic r : d.related) {
            related.add(relativize(r, prefix));
        }
        return d.toBuilder()
            .primary(strip(d.primary, prefix))
            .setLabels(labels)
            .setSuggestions(suggestions)
            .setRelated(related)
            .build();
    }

    private static Span strip(Span s, String prefix) {
        if (s == null || s.file() == null) {
            return s;
        }
        if (s.file().startsWith(prefix)) {
            return new Span(s.file().substring(prefix.length()),
                s.lineStart(), s.colStart(), s.lineEnd(), s.colEnd());
        }
        return s;
    }

    private static final Pattern SUPPRESS_WARNINGS =
        Pattern.compile("@SuppressWarnings\\s*\\(\\s*\\{?\\s*\"([^\"]+)\"");
    private static final Pattern SUPPRESS_JCHECK =
        Pattern.compile("@SuppressJcheck\\s*\\(\\s*code\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern UNSAFE_REASON =
        Pattern.compile("@Unsafe\\s*\\(\\s*reason\\s*=\\s*\"([^\"]*)\"");

    /** Lists every suppression in the checked sources, so a reviewer or agent can audit them. */
    static List<String> scanSuppressions(CheckOptions opts) {
        List<String> out = new ArrayList<>();
        for (Path p : opts.sources) {
            String content;
            try {
                content = Files.readString(p);
            } catch (IOException e) {
                continue;
            }
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String where = relativizePath(p, opts.rootDir) + ":" + (i + 1);
                Matcher m = SUPPRESS_WARNINGS.matcher(lines[i]);
                while (m.find()) {
                    out.add(where + ": @SuppressWarnings(\"" + m.group(1) + "\")");
                }
                m = SUPPRESS_JCHECK.matcher(lines[i]);
                while (m.find()) {
                    out.add(where + ": @SuppressJcheck(code = \"" + m.group(1) + "\")");
                }
                m = UNSAFE_REASON.matcher(lines[i]);
                while (m.find()) {
                    String reason = m.group(1);
                    out.add(where + ": @Unsafe" + (reason.isBlank()
                        ? " (missing reason)" : " (reason: " + reason + ")"));
                }
            }
        }
        return out;
    }

    private static String relativizePath(Path p, String root) {
        String s = p.toString();
        if (root == null || root.isEmpty()) {
            return s;
        }
        String prefix = root.endsWith("/") ? root : root + "/";
        return s.startsWith(prefix) ? s.substring(prefix.length()) : s;
    }
}
