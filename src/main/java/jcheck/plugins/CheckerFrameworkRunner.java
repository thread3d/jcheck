package jcheck.plugins;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jcheck.catalog.Catalog;
import jcheck.catalog.CatalogEntry;
import jcheck.engine.CheckOptions;
import jcheck.engine.SpanResolver;
import jcheck.model.Diagnostic;
import jcheck.model.Level;
import jcheck.model.Span;
import jcheck.model.Tier;

/**
 * Runs the Checker Framework as T4, one configured checker at a time.
 *
 * <p>Running each checker separately is deliberate: the Checker Framework does not name the checker
 * that produced a diagnostic, only a message key such as {@code dereference.of.nullable}, and
 * guessing the checker back from that key would drift with every Checker Framework release.
 * Running one processor makes the mapping exact — everything that run reports belongs to that
 * checker.
 *
 * <p>Like Error Prone, it needs module exports, so it runs through {@link ForkedJavac}.
 */
public final class CheckerFrameworkRunner {

    /** Outcome of one T4 run. */
    public static final class Result {
        public final List<Diagnostic> diagnostics;
        public final List<String> notes;
        public final boolean ran;

        Result(List<Diagnostic> diagnostics, List<String> notes, boolean ran) {
            this.diagnostics = List.copyOf(diagnostics);
            this.notes = List.copyOf(notes);
            this.ran = ran;
        }
    }

    /** Catalog checker name to the processor classes that implement it. */
    private static final Map<String, String> PROCESSORS = processors();

    private static final Pattern KEY_PREFIX = Pattern.compile("^\\[([^\\]]+)]\\s*");

    private CheckerFrameworkRunner() {
    }

    private static Map<String, String> processors() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("nullness", "org.checkerframework.checker.nullness.NullnessChecker");
        m.put("initialization", "org.checkerframework.checker.initialization.InitializationChecker");
        m.put("resourceleak", "org.checkerframework.checker.resourceleak.ResourceLeakChecker");
        m.put("optional", "org.checkerframework.checker.optional.OptionalChecker");
        m.put("lock", "org.checkerframework.checker.lock.LockChecker");
        m.put("signature", "org.checkerframework.checker.signature.SignatureChecker");
        m.put("aliasing", "org.checkerframework.checker.aliasing.AliasingChecker");
        m.put("guardedby", "org.checkerframework.checker.guardedby.GuardedByChecker");
        m.put("interning", "org.checkerframework.checker.interning.InterningChecker");
        m.put("regex", "org.checkerframework.checker.regex.RegexChecker");
        m.put("formatter", "org.checkerframework.checker.formatter.FormatterChecker");
        m.put("i18n", "org.checkerframework.checker.i18n.I18nChecker");
        m.put("tainting", "org.checkerframework.checker.tainting.TaintingChecker");
        m.put("units", "org.checkerframework.checker.units.UnitsChecker");
        m.put("index", "org.checkerframework.checker.index.lowerbound.LowerBoundChecker,"
            + "org.checkerframework.checker.index.upperbound.UpperBoundChecker,"
            + "org.checkerframework.checker.index.searchindex.SearchIndexChecker");
        return Map.copyOf(m);
    }

    public static Result run(CheckOptions opts, Catalog catalog, SpanResolver spans) {
        List<String> notes = new ArrayList<>();
        if (opts.checkerJars.isEmpty()) {
            notes.add("the Checker Framework is configured but its jars were not resolved; "
                + "T4 did not run");
            return new Result(List.of(), notes, false);
        }
        List<String> names = opts.checkerNames.isEmpty() ? List.of("nullness") : opts.checkerNames;

        List<Path> classpath = new ArrayList<>(opts.classpath);
        for (Path jar : opts.checkerJars) {
            if (jar.getFileName().toString().startsWith("checker-qual-") && !classpath.contains(jar)) {
                classpath.add(jar);
            }
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        int seq = 3_000_000;
        boolean anyRan = false;
        for (String name : names) {
            String processors = PROCESSORS.get(name);
            if (processors == null) {
                notes.add("unknown checker '" + name + "' in [checkers.per-module]; skipped");
                continue;
            }
            ForkedJavac.Result run = ForkedJavac.run(options(opts, processors), classpath,
                opts.checkerJars, opts.sources);
            if (!run.ran) {
                notes.add("the Checker Framework (" + name + ") " + run.failure);
                continue;
            }
            anyRan = true;
            CatalogEntry entry = catalog.lookup("checker", name);
            for (Map<String, Object> o : run.diagnostics) {
                diagnostics.add(toDiagnostic(o, name, entry, catalog, spans, seq++));
            }
        }
        return new Result(diagnostics, notes, anyRan);
    }

    private static List<String> options(CheckOptions opts, String processors) {
        List<String> options = new ArrayList<>();
        options.add("-encoding");
        options.add("UTF-8");
        options.add("-Xmaxerrs");
        options.add(String.valueOf(opts.maxErrors));
        options.add("-Xmaxwarns");
        options.add(String.valueOf(opts.maxErrors));
        if (opts.scratchDir != null) {
            options.add("-d");
            options.add(opts.scratchDir.toString());
        }
        if (opts.release != null) {
            options.add("--release");
            options.add(String.valueOf(opts.release));
        }
        options.add("-processor");
        options.add(processors);
        return options;
    }

    private static Diagnostic toDiagnostic(Map<String, Object> o, String checkerName, CatalogEntry entry,
                                           Catalog catalog, SpanResolver spans, int seq) {
        String message = o.get("message") instanceof String ? (String) o.get("message") : "";
        String kind = o.get("kind") instanceof String ? (String) o.get("kind") : "";
        String file = o.get("source") instanceof String ? (String) o.get("source") : "";
        String code = entry != null ? entry.code() : catalog.codeFor("checker", checkerName);

        Level level = "ERROR".equals(kind) ? Level.ERROR
            : ("WARNING".equals(kind) || "MANDATORY_WARNING".equals(kind)) ? Level.WARNING
            : Level.NOTE;

        long start = num(o.get("start"));
        long end = num(o.get("end"));
        Span span = spanFor(spans, file, start, end, num(o.get("line")), num(o.get("column")));

        Diagnostic.Builder b = Diagnostic.builder(code)
            .source("checker")
            .sourceKey(checkerName)
            .level(level)
            .tier(Tier.T4)
            .primary(span)
            .raw(message)
            .seq(seq);

        Matcher m = KEY_PREFIX.matcher(message);
        String headline = message;
        if (m.find()) {
            b.note("checker key: " + m.group(1));
            headline = message.substring(m.end()).strip();
        }
        b.message(headline);
        if (entry != null) {
            for (String h : entry.help) {
                b.help(h);
            }
        }
        return b.build();
    }

    private static Span spanFor(SpanResolver spans, String file, long start, long end, long line, long col) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        if (start >= 0) {
            try {
                spans.put(file, java.nio.file.Files.readString(Path.of(file),
                    java.nio.charset.StandardCharsets.UTF_8));
            } catch (java.io.IOException | RuntimeException ignored) {
                // the child also reported line/column, which SpanResolver can use alone
            }
        }
        return spans.span(file, null, start, end, line, col);
    }

    private static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : -1;
    }
}
