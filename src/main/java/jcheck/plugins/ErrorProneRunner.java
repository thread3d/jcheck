package jcheck.plugins;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
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
 * Runs Error Prone (and NullAway with it) as T3 alongside the built-in pack.
 *
 * <p>The compile happens in a forked JVM via {@link ForkedJavac}; this class builds the Error Prone
 * argument string, turns each returned JSON diagnostic into the shared model, and maps it through
 * the catalog ({@code J04xx} Error Prone, {@code J05xx} NullAway).
 *
 * <p>Nothing here is claimed unless it ran: when resolution or the fork fails, the result says
 * {@code ran == false} and the engine states that on the tier line rather than presenting a quiet
 * T3 as a clean one.
 */
public final class ErrorProneRunner {

    /** Outcome of one plugin run. */
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

    private static final Pattern CHECK_PREFIX = Pattern.compile("^\\[([^\\]]+)]\\s*");
    private static final Pattern SEE_LINK = Pattern.compile("\\(see\\s+(https?://\\S+?)\\s*\\)");
    private static final Pattern PACKAGE_DECL =
        Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    private ErrorProneRunner() {
    }

    public static Result run(CheckOptions opts, Catalog catalog, SpanResolver spans) {
        List<String> notes = new ArrayList<>();
        List<Path> jars = opts.pluginJars;
        if (jars.isEmpty()) {
            notes.add("Error Prone is enabled but no plugin jars were resolved; "
                + "T3 ran the built-in jcheck pack only");
            return new Result(List.of(), notes, false);
        }

        /*
         * NullAway refuses to run without a package scope, and the pinned version has no
         * OnlyNullMarked option, so when the project did not name packages we derive them from the
         * files being compiled — the same set the user would have written by hand.
         */
        boolean nullaway = opts.nullnessMode != null && opts.nullnessMode.startsWith("nullaway");
        List<String> packages = opts.nullnessPackages;
        if (nullaway && (packages == null || packages.isEmpty())) {
            packages = packagesOf(opts.sources);
        }
        if (nullaway && packages.isEmpty()) {
            notes.add("NullAway has no packages to check (no [nullness] packages and the sources "
                + "declare no package); NullAway did not run");
            nullaway = false;
        }

        List<Path> classpath = new ArrayList<>(opts.classpath);
        for (Path jar : jars) {
            if (jar.getFileName().toString().startsWith("jspecify-") && !classpath.contains(jar)) {
                classpath.add(jar);
            }
        }

        ForkedJavac.Result run = ForkedJavac.run(javacOptions(opts, packages, nullaway), classpath, jars,
            opts.sources);
        if (!run.ran) {
            notes.add("Error Prone " + run.failure + "; T3 ran the built-in jcheck pack only");
            return new Result(List.of(), notes, false);
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        int seq = 2_000_000;
        int uncatalogued = 0;
        for (Map<String, Object> o : run.diagnostics) {
            Diagnostic d = toDiagnostic(o, catalog, spans, seq++);
            if (d == null) {
                continue;
            }
            if (!catalog.isMapped(d.source, d.sourceKey)) {
                uncatalogued++;
            }
            diagnostics.add(d);
        }
        if (uncatalogued > 0) {
            notes.add(uncatalogued + " Error Prone/NullAway key(s) are not in the catalog yet; "
                + "they were kept with a stable J9xxx code");
        }
        return new Result(diagnostics, notes, true);
    }

    private static List<String> javacOptions(CheckOptions opts, List<String> packages, boolean nullaway) {
        List<String> options = new ArrayList<>();
        options.add("-encoding");
        options.add("UTF-8");
        options.add("-XDcompilePolicy=simple");
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
        options.add("-Xplugin:" + pluginFlags(opts, packages, nullaway));
        return options;
    }

    /** The Error Prone argument string: per-project opt-outs, then NullAway. */
    private static String pluginFlags(CheckOptions opts, List<String> packages, boolean nullaway) {
        List<String> flags = new ArrayList<>();
        for (String check : opts.errorProneDisabled) {
            if (check != null && !check.isBlank()) {
                flags.add("-Xep:" + check.trim() + ":OFF");
            }
        }
        if (nullaway) {
            flags.add("-Xep:NullAway:ERROR");
            flags.add("-XepOpt:NullAway:JSpecifyMode=true");
            flags.add("-XepOpt:NullAway:AnnotatedPackages=" + String.join(",", packages));
        }
        return "ErrorProne " + String.join(" ", flags);
    }

    /** Distinct package names declared by the sources being checked, in stable order. */
    static List<String> packagesOf(List<Path> sources) {
        TreeSet<String> names = new TreeSet<>();
        for (Path p : sources) {
            try {
                Matcher m = PACKAGE_DECL.matcher(Files.readString(p, StandardCharsets.UTF_8));
                if (m.find()) {
                    names.add(m.group(1));
                }
            } catch (IOException | RuntimeException ignored) {
                // a source that cannot be read simply contributes no package
            }
        }
        return new ArrayList<>(names);
    }

    private static Diagnostic toDiagnostic(Map<String, Object> o, Catalog catalog, SpanResolver spans, int seq) {
        String message = str(o.get("message"));
        String kind = str(o.get("kind"));
        String file = str(o.get("source"));
        String check = checkName(message);
        String source;
        String key;
        if (check.equals("NullAway") || check.startsWith("NullAway:")) {
            source = "nullaway";
            key = nullAwayKey(message);
        } else {
            source = "errorprone";
            key = check.isEmpty() ? "ErrorProne" : check;
        }
        CatalogEntry entry = catalog.lookup(source, key);
        String code = entry != null ? entry.code() : catalog.codeFor(source, key);

        Level level;
        if ("ERROR".equals(kind)) {
            level = Level.ERROR;
        } else if ("WARNING".equals(kind) || "MANDATORY_WARNING".equals(kind)) {
            level = Level.WARNING;
        } else {
            level = Level.NOTE;
        }

        Span span = spanFor(spans, file, num(o.get("start")), num(o.get("end")),
            num(o.get("line")), num(o.get("column")));
        Diagnostic.Builder b = Diagnostic.builder(code)
            .source(source)
            .sourceKey(key)
            .level(level)
            .tier(Tier.T3)
            .raw(message)
            .primary(span)
            .seq(seq);
        if (entry != null) {
            for (String h : entry.help) {
                b.help(h);
            }
        }
        applyMessage(b, message);
        return b.build();
    }

    /**
     * Error Prone's message is a headline, sometimes a "Did you mean" suggestion, and a
     * documentation link on its own line. Keeping that as one blob would put newlines inside the
     * rendered headline; the suggestion belongs in the help lines and the link in a note.
     */
    private static void applyMessage(Diagnostic.Builder b, String raw) {
        String headline = "";
        for (String line : raw.split("\n", -1)) {
            String t = line.strip();
            if (t.isEmpty()) {
                continue;
            }
            Matcher link = SEE_LINK.matcher(t);
            if (link.find()) {
                b.note("see " + link.group(1));
                continue;
            }
            if (headline.isEmpty()) {
                headline = CHECK_PREFIX.matcher(t).replaceFirst("").strip();
            } else {
                b.help(t);
            }
        }
        b.message(headline.isEmpty() ? raw.strip() : headline);
    }

    /** The check name Error Prone puts in brackets at the start of the message. */
    static String checkName(String message) {
        if (message == null) {
            return "";
        }
        Matcher m = CHECK_PREFIX.matcher(message);
        return m.find() ? m.group(1) : "";
    }

    /** NullAway reports everything as {@code [NullAway]}; the wording names the sub-category. */
    static String nullAwayKey(String message) {
        String m = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("dereferenced") || m.contains("dereference")) {
            return "NullAway:Dereference";
        }
        if (m.contains("assign") && m.contains("field")) {
            return "NullAway:Assignment";
        }
        if (m.contains("return") && m.contains("method")) {
            return "NullAway:MethodReturn";
        }
        if (m.contains("field") && m.contains("initializ")) {
            return "NullAway:Field";
        }
        if (m.contains("passing") || m.contains("parameter")) {
            return "NullAway:Parameter";
        }
        if (m.contains("array")) {
            return "NullAway:Array";
        }
        if (m.contains("optional")) {
            return "NullAway:Optional";
        }
        return "NullAway";
    }

    private static Span spanFor(SpanResolver spans, String file, long start, long end, long line, long col) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        if (start >= 0) {
            try {
                spans.put(file, Files.readString(Path.of(file), StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException ignored) {
                // fall back to the line/column the child also reported
            }
        }
        return spans.span(file, null, start, end, line, col);
    }

    private static String str(Object o) {
        return o instanceof String ? (String) o : "";
    }

    private static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : -1;
    }
}
