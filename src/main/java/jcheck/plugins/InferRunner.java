package jcheck.plugins;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import jcheck.catalog.Catalog;
import jcheck.catalog.CatalogEntry;
import jcheck.engine.SpanResolver;
import jcheck.model.Diagnostic;
import jcheck.model.Level;
import jcheck.model.Span;
import jcheck.model.Tier;
import jcheck.util.Json;

/**
 * Runs Facebook Infer over the project's sources and renders its findings as diagnostics.
 *
 * <p>Infer is a native binary rather than a jar, so it is located through {@code jcheck.toml},
 * {@code $JCHECK_INFER} or {@code PATH}, and it is told to keep its results under the jcheck cache
 * with {@code -o} so it never writes {@code infer-out} into the project. Its Java frontend wraps
 * javac, which is exactly the compile jcheck is already describing, so the findings line up with
 * the same sources.
 */
public final class InferRunner {

    /** Outcome of one Infer run. */
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

    private InferRunner() {
    }

    public static Result run(Path root, String configuredBinary, List<Path> sources,
                             List<Path> classpath, Catalog catalog) {
        List<String> notes = new ArrayList<>();
        Path infer = findInfer(root, configuredBinary);
        if (infer == null) {
            notes.add("Infer is not available: set [bytecode] infer_binary, $JCHECK_INFER, or put "
                + "infer on PATH. Infer did not run.");
            return new Result(List.of(), notes, false);
        }
        if (sources == null || sources.isEmpty()) {
            notes.add("no sources for Infer to analyze");
            return new Result(List.of(), notes, false);
        }

        Path outDir = null;
        Path classes;
        Path log;
        try {
            try {
                outDir = MavenResolver.defaultCacheDir().resolve("infer")
                    .resolve(Long.toHexString(root.toString().hashCode()));
                deleteRecursively(outDir);
                Files.createDirectories(outDir);
            } catch (IOException | RuntimeException e) {
                // A read-only cache is fine: keep Infer's output in a temp directory instead.
                outDir = Files.createTempDirectory("jcheck-infer-out-");
            }
            classes = Files.createTempDirectory("jcheck-infer-classes-");
            log = Files.createTempFile("jcheck-infer-", ".log");
        } catch (IOException e) {
            notes.add("cannot prepare an Infer run: " + e.getMessage());
            return new Result(List.of(), notes, false);
        }

        try {
            List<String> command = new ArrayList<>();
            command.add(infer.toString());
            command.add("run");
            command.add("-o");
            command.add(outDir.toString());
            command.add("--no-progress-bar");
            command.add("--");
            command.add("javac");
            command.add("-d");
            command.add(classes.toString());
            if (classpath != null && !classpath.isEmpty()) {
                command.add("-classpath");
                command.add(join(classpath));
            }
            for (Path source : sources) {
                command.add(source.toString());
            }

            ProcessBuilder pb = new ProcessBuilder(command).directory(root.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(log.toFile());
            Process process = pb.start();
            if (!process.waitFor(30, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                notes.add("Infer did not finish within 30 minutes; it did not run");
                return new Result(List.of(), notes, false);
            }
            Path report = outDir.resolve("report.json");
            if (!Files.isRegularFile(report)) {
                notes.add("Infer produced no report (exit " + process.exitValue() + "): "
                    + firstLine(log));
                return new Result(List.of(), notes, false);
            }
            return parse(report, root, sources, catalog, notes);
        } catch (IOException e) {
            notes.add("Infer could not be started: " + e.getMessage());
            return new Result(List.of(), notes, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notes.add("Infer was interrupted");
            return new Result(List.of(), notes, false);
        } finally {
            deleteRecursively(classes);
            deleteQuietly(log);
        }
    }

    private static Result parse(Path report, Path root, List<Path> sources, Catalog catalog,
                                List<String> notes) {
        Object parsed;
        try {
            parsed = Json.parse(Files.readString(report, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            notes.add("cannot read the Infer report: " + e.getMessage());
            return new Result(List.of(), notes, false);
        }
        List<Object> entries = Json.asArray(parsed);
        if (entries == null) {
            notes.add("the Infer report is not an array of findings");
            return new Result(List.of(), notes, false);
        }

        SpanResolver spans = new SpanResolver();
        List<Diagnostic> diagnostics = new ArrayList<>();
        int seq = 7_000_000;
        for (Object entry : entries) {
            Map<String, Object> bug = Json.asObject(entry);
            if (bug == null) {
                continue;
            }
            String bugType = str(bug.get("bug_type"));
            String qualifier = str(bug.get("qualifier"));
            String human = str(bug.get("bug_type_hum"));
            String message = !qualifier.isEmpty() ? qualifier
                : (!human.isEmpty() ? human : bugType);
            int line = (int) number(bug.get("line"));
            String file = str(bug.get("file"));
            Path source = resolveSource(root, sources, file);
            Span span = source == null || line <= 0 ? null : spanForLine(spans, source, line);

            CatalogEntry entryRow = catalog.lookup("infer", "InferFinding");
            String code = entryRow != null ? entryRow.code() : "J1401";
            Level level = "ERROR".equals(str(bug.get("severity"))) ? Level.ERROR : Level.WARNING;

            Diagnostic.Builder b = Diagnostic.builder(code)
                .source("infer")
                .sourceKey(bugType.isEmpty() ? "InferFinding" : bugType)
                .level(level)
                .tier(Tier.T5)
                .message(message)
                .primary(span)
                .seq(seq++);
            if (entryRow != null) {
                for (String h : entryRow.help) {
                    b.help(h);
                }
            }
            String procedure = str(bug.get("procedure"));
            if (!procedure.isEmpty()) {
                b.note("in " + procedure);
            }
            diagnostics.add(b.build());
        }
        notes.add("Infer: " + diagnostics.size() + " issue(s)");
        return new Result(diagnostics, notes, true);
    }

    /** The Infer binary: configured path, {@code $JCHECK_INFER}, then {@code PATH}. */
    public static Path findInfer(Path root, String configuredBinary) {
        if (configuredBinary != null && !configuredBinary.isBlank()) {
            Path p = Path.of(configuredBinary);
            Path resolved = p.isAbsolute() ? p : root.resolve(p);
            return Files.isExecutable(resolved) ? resolved : null;
        }
        String env = System.getenv("JCHECK_INFER");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env);
            return Files.isExecutable(p) ? p : null;
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            Path candidate = Path.of(dir).resolve("infer");
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path resolveSource(Path root, List<Path> sources, String file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        Path direct = Path.of(file);
        Path resolved = direct.isAbsolute() ? direct : root.resolve(file);
        if (Files.isRegularFile(resolved)) {
            return resolved;
        }
        for (Path s : sources) {
            if (s.toString().endsWith(file)) {
                return s;
            }
        }
        return null;
    }

    private static Span spanForLine(SpanResolver spans, Path file, int line) {
        String key = file.toString();
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            spans.put(key, content);
            int start = offsetOfLine(content, line);
            int nl = content.indexOf('\n', start);
            int end = nl < 0 ? content.length() : nl;
            return spans.span(key, null, start, end, -1, -1);
        } catch (IOException | RuntimeException e) {
            return spans.span(key, null, -1, -1, line, 1);
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

    private static String str(Object o) {
        return o instanceof String ? (String) o : "";
    }

    private static long number(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : -1;
    }

    private static String join(List<Path> paths) {
        List<String> parts = new ArrayList<>();
        for (Path p : paths) {
            parts.add(p.toString());
        }
        return String.join(java.io.File.pathSeparator, parts);
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

    private static void deleteQuietly(Path file) {
        try {
            if (file != null) {
                Files.deleteIfExists(file);
            }
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static void deleteRecursively(Path dir) {
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
