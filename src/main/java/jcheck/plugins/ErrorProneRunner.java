package jcheck.plugins;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
import jcheck.util.Json;

/**
 * Runs Error Prone (and NullAway with it) as T3 alongside the built-in pack.
 *
 * <p>Error Prone needs {@code --add-exports} on the JVM running javac, which a running process
 * cannot add to itself. So the plugin runs in a forked JVM ({@link PluginRunner}) with those flags,
 * returns one JSON object per diagnostic, and this class turns them into the same
 * {@link Diagnostic} model everything else uses. The fork is also what keeps a plugin crash from
 * taking the daemon down.
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

    /** The module exports Error Prone documents for JDK 16+; verified against JDK 20. */
    private static final List<String> EXPORTS = List.of(
        "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED");

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
        List<Path> compileExtra = new ArrayList<>();
        for (Path jar : jars) {
            if (jar.getFileName().toString().startsWith("jspecify-")) {
                compileExtra.add(jar);
            }
        }

        /*
         * NullAway refuses to run without a package scope, and the pinned version has no
         * OnlyNullMarked option, so when the project did not name packages we derive them from the
         * files being compiled. That is the same set the user would have written by hand.
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

        Path work;
        try {
            work = Files.createTempDirectory("jcheck-errorprone-");
        } catch (IOException e) {
            notes.add("cannot create a scratch directory for Error Prone: " + e.getMessage());
            return new Result(List.of(), notes, false);
        }

        try {
            Path request = work.resolve("request.properties");
            Path output = work.resolve("diagnostics.jsonl");
            Path stdout = work.resolve("stdout.txt");
            Path stderr = work.resolve("stderr.txt");
            Files.writeString(request, requestProperties(opts, jars, compileExtra, packages, nullaway),
                StandardCharsets.UTF_8);

            List<String> command = command(opts, request, output);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectOutput(stdout.toFile());
            pb.redirectError(stderr.toFile());
            Process process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                notes.add("Error Prone did not finish within 5 minutes; T3 ran the built-in pack only");
                return new Result(List.of(), notes, false);
            }
            if (!Files.isRegularFile(output)) {
                notes.add("Error Prone produced no diagnostics file (exit " + process.exitValue() + "); "
                    + firstLine(stderr));
                return new Result(List.of(), notes, false);
            }
            Result parsed = parse(output, catalog, spans, notes);
            if (parsed.diagnostics.isEmpty() && process.exitValue() != 0) {
                notes.add("Error Prone failed without reporting a diagnostic (exit "
                    + process.exitValue() + "): " + firstLine(stderr));
                return new Result(List.of(), notes, false);
            }
            return parsed;
        } catch (IOException e) {
            notes.add("Error Prone could not be started: " + e.getMessage());
            return new Result(List.of(), notes, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notes.add("Error Prone was interrupted");
            return new Result(List.of(), notes, false);
        } finally {
            deleteRecursively(work);
        }
    }

    private static String requestProperties(CheckOptions opts, List<Path> jars, List<Path> compileExtra,
                                            List<String> packages, boolean nullaway) {
        StringBuilder sb = new StringBuilder();
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
        for (int i = 0; i < options.size(); i++) {
            sb.append("option.").append(i).append('=').append(escapeProperty(options.get(i))).append('\n');
        }

        sb.append("classpath=").append(join(opts.classpath, compileExtra)).append('\n');
        sb.append("processorpath=").append(join(jars, List.of())).append('\n');
        for (int i = 0; i < opts.sources.size(); i++) {
            sb.append("source.").append(i).append('=').append(escapeProperty(opts.sources.get(i).toString()))
              .append('\n');
        }
        return sb.toString();
    }

    /** The Error Prone argument string: built-in policy, then NullAway, then per-project opt-outs. */
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

    private static final Pattern PACKAGE_DECL =
        Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    /** Distinct package names declared by the sources being checked, in stable order. */
    static List<String> packagesOf(List<Path> sources) {
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
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

    private static List<String> command(CheckOptions opts, Path request, Path output) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javaBinary());
        command.addAll(EXPORTS);
        command.add("-cp");
        command.add(selfClasspath());
        command.add(PluginRunner.class.getName());
        command.add(request.toString());
        command.add(output.toString());
        return command;
    }

    private static String selfClasspath() {
        try {
            Path self = Path.of(ErrorProneRunner.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
            return self.toString();
        } catch (Exception e) {
            return System.getProperty("java.class.path", ".");
        }
    }

    private static String javaBinary() {
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe).toString();
    }

    private static Result parse(Path output, Catalog catalog, SpanResolver spans, List<String> notes)
            throws IOException {
        List<Diagnostic> diagnostics = new ArrayList<>();
        int seq = 2_000_000;
        int uncatalogued = 0;
        for (String line : Files.readAllLines(output, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> o;
            try {
                o = Json.parseObject(line);
            } catch (RuntimeException e) {
                notes.add("unreadable Error Prone diagnostic: " + e.getMessage());
                continue;
            }
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
        if (m.contains("field") && (m.contains("initializ") || m.contains("not initialized"))) {
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

    private static String join(List<Path> a, List<Path> b) {
        List<String> all = new ArrayList<>();
        for (Path p : a) {
            all.add(p.toString());
        }
        for (Path p : b) {
            if (!a.contains(p)) {
                all.add(p.toString());
            }
        }
        return String.join(java.io.File.pathSeparator, all);
    }

    private static String escapeProperty(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String firstLine(Path file) {
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    return line.length() > 200 ? line.substring(0, 200) + "..." : line;
                }
            }
        } catch (IOException ignored) {
            // nothing to add
        }
        return "";
    }

    static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
