package jcheck.testharness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import jcheck.build.BuildModel;
import jcheck.catalog.Catalog;
import jcheck.catalog.CatalogEntry;
import jcheck.config.JcheckConfig;
import jcheck.engine.SpanResolver;
import jcheck.model.Diagnostic;
import jcheck.model.Level;
import jcheck.model.Span;
import jcheck.model.Tier;
import jcheck.plugins.PluginClasspath;

/**
 * Runs the OpenJDK Concurrency Stress Tests (jcstress) over a project's {@code @JCStressTest}
 * classes and reports failing outcomes.
 *
 * <p>jcstress is a harness rather than a library: its annotation processor generates a
 * {@code META-INF/TestList} at compile time, and the runner reads it. jcheck compiles the project's
 * test sources with that processor when no list is already present, then runs the tests and turns
 * every forbidden or interesting outcome into a diagnostic.
 */
public final class JcstressRunner {

    /** Outcome of one jcstress run. */
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

    private static final String PROCESSOR = "org.openjdk.jcstress.infra.processors.JCStressTestProcessor";
    private static final Pattern FAILURE =
        Pattern.compile("^([\\w.$]+)\\s*\\[[^\\]]*]:\\s*(Observed (?:forbidden|interesting) state:.*)$");
    private static final Pattern CLASS_DECL =
        Pattern.compile("^.*\\b(?:class|record|enum)\\s+%s\\b.*$", Pattern.MULTILINE);

    private static final List<String> MAIN_CLASS_DIRS = List.of(
        "build/classes/java/main", "build/classes/kotlin/main", "target/classes", "out/classes");
    private static final List<String> TEST_CLASS_DIRS = List.of(
        "build/classes/java/test", "build/classes/kotlin/test", "target/test-classes",
        "out/test-classes");

    private JcstressRunner() {
    }

    public static Result run(Path root, JcheckConfig config, Catalog catalog) {
        List<String> notes = new ArrayList<>();
        List<Path> jars;
        try {
            PluginClasspath.Result cp = PluginClasspath.resolveCoordinates(
                List.of("org.openjdk.jcstress:jcstress-core:" + config.jcstressVersion),
                config.pluginClasspath, root, config.pluginOffline, config.pluginRepo);
            jars = cp.processorPath;
            notes.addAll(cp.notes);
        } catch (RuntimeException e) {
            notes.add("jcstress could not be resolved: " + e.getMessage());
            return new Result(List.of(), notes, false);
        }
        if (jars.isEmpty()) {
            notes.add("jcstress could not be resolved; stress tests did not run");
            return new Result(List.of(), notes, false);
        }

        BuildModel model = BuildModel.resolve(root, config, null, null, null, null);
        notes.addAll(model.notes);
        List<Path> mainClasses = existing(root, MAIN_CLASS_DIRS);
        List<Path> testClasses = existing(root, TEST_CLASS_DIRS);
        List<Path> testSources = testSources(model.sources);
        if (testSources.isEmpty() && testClasses.isEmpty()) {
            notes.add("no test sources or classes for jcstress to run");
            return new Result(List.of(), notes, false);
        }

        Path scratch;
        Path reportDir;
        Path log;
        try {
            scratch = Files.createTempDirectory("jcheck-jcstress-classes-");
            reportDir = Files.createTempDirectory("jcheck-jcstress-report-");
            log = Files.createTempFile("jcheck-jcstress-", ".log");
        } catch (IOException e) {
            notes.add("cannot prepare a jcstress run: " + e.getMessage());
            return new Result(List.of(), notes, false);
        }

        try {
            List<Path> runtimeClasses = new ArrayList<>(testClasses);
            if (!hasTestList(testClasses) && !testSources.isEmpty()) {
                List<String> diagnostics = new ArrayList<>();
                if (!compileWithProcessor(jars, model.classpath, mainClasses, testSources, scratch,
                        diagnostics)) {
                    notes.add("jcstress test sources did not compile with its annotation processor: "
                        + String.join("; ", diagnostics));
                    return new Result(List.of(), notes, false);
                }
                runtimeClasses.add(0, scratch);
            }
            if (!hasTestList(runtimeClasses)) {
                notes.add("jcstress found no META-INF/TestList; compile the tests with the jcstress "
                    + "annotation processor first");
                return new Result(List.of(), notes, false);
            }

            List<Path> classpath = new ArrayList<>();
            classpath.addAll(jars);
            classpath.addAll(model.classpath);
            classpath.addAll(mainClasses);
            classpath.addAll(runtimeClasses);
            List<String> command = new ArrayList<>();
            command.add(javaBinary());
            command.add("-cp");
            command.add(join(dedupe(classpath)));
            command.add("org.openjdk.jcstress.Main");
            command.add("-m");
            command.add(config.jcstressMode);
            command.add("-r");
            command.add(reportDir.toString());
            command.add("-c");
            command.add(String.valueOf(Math.max(1, Runtime.getRuntime().availableProcessors() / 2)));

            ProcessBuilder pb = new ProcessBuilder(command).directory(root.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(log.toFile());
            Process process = pb.start();
            if (!process.waitFor(30, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                notes.add("jcstress did not finish within 30 minutes; stress tests did not run");
                return new Result(List.of(), notes, false);
            }
            Parsed parsed = parse(Files.readString(log, StandardCharsets.UTF_8), testSources,
                new SpanResolver(), catalog);
            notes.addAll(parsed.notes);
            notes.add("jcstress (" + config.jcstressMode + ") exit " + process.exitValue());
            return new Result(parsed.diagnostics, notes, true);
        } catch (IOException e) {
            notes.add("jcstress could not be started: " + e.getMessage());
            return new Result(List.of(), notes, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notes.add("jcstress was interrupted");
            return new Result(List.of(), notes, false);
        } finally {
            deleteRecursively(scratch);
            deleteRecursively(reportDir);
            deleteQuietly(log);
        }
    }

    private static final class Parsed {
        final List<Diagnostic> diagnostics;
        final List<String> notes;

        Parsed(List<Diagnostic> diagnostics, List<String> notes) {
            this.diagnostics = diagnostics;
            this.notes = notes;
        }
    }

    /** jcstress prints one line per failing fork; the same test and state is reported once. */
    static Parsed parse(String output, List<Path> testSources, SpanResolver spans, Catalog catalog) {
        Map<String, String> failures = new LinkedHashMap<>();
        for (String line : output.split("\n", -1)) {
            Matcher m = FAILURE.matcher(line.strip());
            if (m.matches()) {
                failures.putIfAbsent(m.group(1) + "|" + m.group(2), m.group(1) + "\u0000" + m.group(2));
            }
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        int seq = 8_000_000;
        for (String value : failures.values()) {
            String[] parts = value.split("\u0000", 2);
            String testClass = parts[0];
            String observed = parts[1];
            Path file = resolveSource(testSources, testClass);
            Span span = file == null ? null : spanForClass(spans, file, testClass);

            CatalogEntry entry = catalog.lookup("jcstress", "StressTestFailure");
            String code = entry != null ? entry.code() : catalog.codeFor("jcstress", "StressTestFailure");
            Diagnostic.Builder b = Diagnostic.builder(code)
                .source("jcstress")
                .sourceKey("StressTestFailure")
                .level(Level.ERROR)
                .tier(Tier.T5)
                .message("concurrency stress test failed: " + observed)
                .primary(span)
                .seq(seq++);
            if (entry != null) {
                for (String h : entry.help) {
                    b.help(h);
                }
            }
            b.note("in " + testClass);
            diagnostics.add(b.build());
        }
        List<String> notes = new ArrayList<>();
        notes.add("jcstress: " + failures.size() + " failing test outcome(s)");
        return new Parsed(diagnostics, notes);
    }

    private static boolean compileWithProcessor(List<Path> jars, List<Path> classpath,
                                                List<Path> mainClasses, List<Path> sources,
                                                Path output, List<String> diagnostics) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            diagnostics.add("no system Java compiler");
            return false;
        }
        List<Path> cp = new ArrayList<>(jars);
        cp.addAll(classpath);
        cp.addAll(mainClasses);
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(collector, null,
                StandardCharsets.UTF_8)) {
            fm.setLocationFromPaths(StandardLocation.CLASS_PATH, dedupe(cp));
            fm.setLocationFromPaths(StandardLocation.ANNOTATION_PROCESSOR_PATH, jars);
            boolean ok = compiler.getTask(null, fm, collector,
                List.of("-d", output.toString(), "-processor", PROCESSOR),
                null, fm.getJavaFileObjectsFromPaths(sources)).call();
            if (!ok) {
                for (javax.tools.Diagnostic<? extends JavaFileObject> d : collector.getDiagnostics()) {
                    diagnostics.add(d.getMessage(null));
                    if (diagnostics.size() >= 5) {
                        break;
                    }
                }
            }
            return ok;
        } catch (IOException | RuntimeException e) {
            diagnostics.add(e.getMessage());
            return false;
        }
    }

    static List<Path> testSources(List<Path> sources) {
        List<Path> out = new ArrayList<>();
        for (Path p : sources) {
            String s = p.toString();
            if (s.contains("/src/test/") || s.contains("/src/testFixtures/")) {
                out.add(p);
            }
        }
        return out;
    }

    private static boolean hasTestList(List<Path> dirs) {
        for (Path dir : dirs) {
            if (Files.isRegularFile(dir.resolve("META-INF/TestList"))) {
                return true;
            }
        }
        return false;
    }

    private static Path resolveSource(List<Path> sources, String testClass) {
        String rel = testClass.replace('.', '/') + ".java";
        for (Path s : sources) {
            if (s.toString().endsWith(rel)) {
                return s;
            }
        }
        return null;
    }

    private static Span spanForClass(SpanResolver spans, Path file, String testClass) {
        String key = file.toString();
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            spans.put(key, content);
            String simple = testClass.substring(testClass.lastIndexOf('.') + 1);
            int line = 1;
            String[] lines = content.split("\n", -1);
            Pattern decl = Pattern.compile("\\b(?:class|record|enum)\\s+" + Pattern.quote(simple) + "\\b");
            for (int i = 0; i < lines.length; i++) {
                if (decl.matcher(lines[i]).find()) {
                    line = i + 1;
                    break;
                }
            }
            int start = offsetOfLine(content, line);
            int nl = content.indexOf('\n', start);
            int end = nl < 0 ? content.length() : nl;
            return spans.span(key, null, start, end, -1, -1);
        } catch (IOException | RuntimeException e) {
            return spans.span(key, null, -1, -1, 1, 1);
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

    private static List<Path> existing(Path root, List<String> candidates) {
        List<Path> out = new ArrayList<>();
        for (String c : candidates) {
            Path p = root.resolve(c);
            if (Files.isDirectory(p)) {
                out.add(p);
            }
        }
        return out;
    }

    private static List<Path> dedupe(List<Path> paths) {
        return new ArrayList<>(new LinkedHashSet<>(paths));
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
