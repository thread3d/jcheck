package jcheck.plugins;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
 * Runs SpotBugs as T5 over the classes the check compile already produced.
 *
 * <p>SpotBugs is a bytecode tool, so it runs only when T0-T2 produced class files. It is invoked as
 * its own process and asked for SARIF, not XML: SARIF carries the human message and the source
 * region, which the {@code -xml:withMessages} path does not emit in this SpotBugs version. Bug
 * patterns map through the SpotBugs catalog to {@code J04xx} codes.
 */
public final class SpotBugsRunner {

    /** Outcome of one T5 run. */
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

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    private SpotBugsRunner() {
    }

    public static Result run(CheckOptions opts, Catalog catalog, SpanResolver spans) {
        List<String> notes = new ArrayList<>();
        if (opts.spotbugsJars.isEmpty()) {
            notes.add("SpotBugs is configured but its jars were not resolved; T5 did not run");
            return new Result(List.of(), notes, false);
        }
        Path classes = opts.scratchDir;
        if (classes == null || !hasClassFiles(classes)) {
            notes.add("SpotBugs is configured but the check produced no class files; T5 did not run");
            return new Result(List.of(), notes, false);
        }

        Path report;
        try {
            report = Files.createTempFile("jcheck-spotbugs-", ".sarif");
        } catch (IOException e) {
            notes.add("cannot create a file for the SpotBugs report: " + e.getMessage());
            return new Result(List.of(), notes, false);
        }
        Path log = null;

        try {
            List<String> command = new ArrayList<>();
            command.add(javaBinary());
            command.add("-cp");
            command.add(join(opts.spotbugsJars));
            command.add("edu.umd.cs.findbugs.LaunchAppropriateUI");
            command.add("-textui");
            command.add("-sarif");
            command.add("-output");
            command.add(report.toString());
            command.add("-effort:" + effort(opts.spotbugsEffort));
            command.add(thresholdFlag(opts.spotbugsThreshold));
            if (!opts.classpath.isEmpty()) {
                command.add("-auxclasspath");
                command.add(join(opts.classpath));
            }
            for (Path jar : opts.spotbugsJars) {
                if (jar.getFileName().toString().contains("findsecbugs")) {
                    command.add("-pluginList");
                    command.add(jar.toString());
                    break;
                }
            }
            command.add(classes.toString());

            log = Files.createTempFile("jcheck-spotbugs-", ".log");
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectError(log.toFile());
            pb.redirectOutput(log.toFile());
            Process process = pb.start();
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                notes.add("SpotBugs did not finish within 10 minutes; T5 did not run");
                return new Result(List.of(), notes, false);
            }
            if (!Files.isRegularFile(report) || Files.size(report) == 0) {
                notes.add("SpotBugs produced no report (exit " + process.exitValue() + "): "
                    + ForkedJavac.firstLine(readText(log)));
                return new Result(List.of(), notes, false);
            }
            return parse(report, opts, catalog, spans, notes);
        } catch (IOException e) {
            notes.add("SpotBugs could not be started: " + e.getMessage());
            return new Result(List.of(), notes, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notes.add("SpotBugs was interrupted");
            return new Result(List.of(), notes, false);
        } finally {
            deleteQuietly(report);
            deleteQuietly(log);
        }
    }

    private static Result parse(Path report, CheckOptions opts, Catalog catalog, SpanResolver spans,
                                List<String> notes) {
        Map<String, Object> root;
        try {
            root = Json.parseObject(Files.readString(report, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            notes.add("cannot read the SpotBugs report: " + e.getMessage());
            return new Result(List.of(), notes, false);
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        int seq = 4_000_000;
        int uncatalogued = 0;
        List<Object> runs = Json.asArray(root.get("runs"));
        if (runs != null) {
            for (Object runObject : runs) {
                Map<String, Object> run = Json.asObject(runObject);
                List<Object> results = run == null ? null : Json.asArray(run.get("results"));
                if (results == null) {
                    continue;
                }
                for (Object resultObject : results) {
                    Map<String, Object> result = Json.asObject(resultObject);
                    if (result == null) {
                        continue;
                    }
                    Diagnostic d = toDiagnostic(result, opts, catalog, spans, seq++);
                    if (d == null) {
                        continue;
                    }
                    if (!catalog.isMapped("spotbugs", d.sourceKey)) {
                        uncatalogued++;
                    }
                    diagnostics.add(d);
                }
            }
        }
        if (uncatalogued > 0) {
            notes.add(uncatalogued + " SpotBugs pattern(s) are not in the catalog; "
                + "they were kept with a stable J9xxx code");
        }
        return new Result(diagnostics, notes, true);
    }

    private static Diagnostic toDiagnostic(Map<String, Object> result, CheckOptions opts, Catalog catalog,
                                           SpanResolver spans, int seq) {
        String type = Json.optString(result, "ruleId");
        if (type == null || type.isEmpty()) {
            return null;
        }
        String levelName = Json.optString(result, "level");
        String message = Json.optString(Json.asObject(result.get("message")), "text");

        CatalogEntry entry = catalog.lookup("spotbugs", type);
        String code = entry != null ? entry.code() : catalog.codeFor("spotbugs", type);
        if (message == null || message.isEmpty()) {
            message = entry != null ? PLACEHOLDER.matcher(entry.headline()).replaceAll("").strip() : type;
            if (message.isEmpty()) {
                message = type;
            }
        }

        Level level = "error".equals(levelName) ? Level.ERROR : Level.WARNING;

        String uri = "";
        int startLine = -1;
        int endLine = -1;
        List<Object> locations = Json.asArray(result.get("locations"));
        if (locations != null && !locations.isEmpty()) {
            Map<String, Object> location = Json.asObject(locations.get(0));
            Map<String, Object> physical = location == null ? null
                : Json.asObject(location.get("physicalLocation"));
            Map<String, Object> artifact = physical == null ? null
                : Json.asObject(physical.get("artifactLocation"));
            Map<String, Object> region = physical == null ? null : Json.asObject(physical.get("region"));
            uri = artifact == null ? "" : String.valueOf(artifact.getOrDefault("uri", ""));
            startLine = (int) number(region == null ? null : region.get("startLine"), -1);
            endLine = (int) number(region == null ? null : region.get("endLine"), startLine);
        }

        Path file = resolveSource(opts.sources, uri);
        Span span = file == null ? null : spanForLines(spans, file, startLine, endLine);

        Diagnostic.Builder b = Diagnostic.builder(code)
            .source("spotbugs")
            .sourceKey(type)
            .level(level)
            .tier(Tier.T5)
            .message(message)
            .primary(span)
            .seq(seq);
        if (entry != null) {
            for (String h : entry.help) {
                b.help(h);
            }
        }
        return b.build();
    }

    private static Span spanForLines(SpanResolver spans, Path file, int startLine, int endLine) {
        String key = file.toString();
        if (startLine <= 0) {
            return null;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            spans.put(key, content);
            int start = offsetOfLine(content, startLine);
            int end = endOfLine(content, endLine < startLine ? startLine : endLine);
            return spans.span(key, null, start, end, -1, -1);
        } catch (IOException | RuntimeException e) {
            return spans.span(key, null, -1, -1, startLine, 1);
        }
    }

    private static int offsetOfLine(String content, int line) {
        if (line <= 1) {
            return 0;
        }
        int offset = 0;
        for (int current = 1; current < line; current++) {
            int nl = content.indexOf('\n', offset);
            if (nl < 0) {
                return content.length();
            }
            offset = nl + 1;
        }
        return offset;
    }

    private static int endOfLine(String content, int line) {
        int offset = offsetOfLine(content, line);
        int nl = content.indexOf('\n', offset);
        return nl < 0 ? content.length() : nl;
    }

    private static Path resolveSource(List<Path> sources, String uri) {
        if (uri == null || uri.isEmpty()) {
            return null;
        }
        String clean = uri.startsWith("file:") ? Path.of(java.net.URI.create(uri)).toString() : uri;
        for (Path s : sources) {
            if (s.toString().endsWith(clean)) {
                return s;
            }
        }
        String name = clean.substring(clean.lastIndexOf('/') + 1);
        for (Path s : sources) {
            if (s.getFileName().toString().equals(name)) {
                return s;
            }
        }
        return null;
    }

    private static boolean hasClassFiles(Path dir) {
        try (var walk = Files.walk(dir)) {
            return walk.anyMatch(p -> p.toString().endsWith(".class"));
        } catch (IOException e) {
            return false;
        }
    }

    private static long number(Object o, long fallback) {
        return o instanceof Number ? ((Number) o).longValue() : fallback;
    }

    private static String effort(String value) {
        switch (value == null ? "max" : value) {
            case "min":
                return "min";
            case "default":
                return "default";
            default:
                return "max";
        }
    }

    private static String thresholdFlag(String value) {
        switch (value == null ? "medium" : value) {
            case "high":
                return "-high";
            case "low":
                return "-low";
            default:
                return "-medium";
        }
    }

    private static String join(List<Path> paths) {
        List<String> parts = new ArrayList<>();
        for (Path p : paths) {
            parts.add(p.toString());
        }
        return String.join(java.io.File.pathSeparator, parts);
    }

    private static String javaBinary() {
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe).toString();
    }

    private static String readText(Path file) {
        try {
            return file == null ? "" : Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            if (file != null) {
                Files.deleteIfExists(file);
            }
        } catch (IOException ignored) {
            // best effort
        }
    }
}
