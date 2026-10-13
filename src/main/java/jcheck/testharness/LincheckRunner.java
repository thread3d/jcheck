package jcheck.testharness;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
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
import jcheck.plugins.MavenResolver;
import jcheck.plugins.PluginClasspath;

/**
 * Runs the project's Lincheck stress tests and reports linearizability failures.
 *
 * <p>Lincheck is a library rather than a harness: a test class declares {@code @Operation} methods
 * and the framework explores interleavings against a sequential specification. jcheck resolves the
 * library, compiles the project's Java test sources when the build tool has not, and runs a small
 * forked runner that calls {@code LinChecker.check} per test class. Kotlin tests are supported once
 * the project's own build has compiled them, because javac cannot read Kotlin sources.
 */
public final class LincheckRunner {

    /** Outcome of one Lincheck run. */
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

    private static final String RUNNER_RESOURCE = "/jcheck/lincheck/LincheckFork.java";
    private static final String RUNNER_CLASS = "jcheck.lincheck.LincheckFork";
    private static final Pattern FAILURE = Pattern.compile("^LINCHECK_FAILED\t([^\\t]+)\t(.*)$");
    private static final Pattern UNSUPPORTED =
        Pattern.compile("^LINCHECK_UNSUPPORTED\t([^\\t]+)\t(\\d+)$");
    private static final Pattern DONE =
        Pattern.compile("^LINCHECK_DONE\t(\\d+)\t(\\d+)\t(\\d+)$");

    /**
     * The newest class-file major version Lincheck 2.39 can instrument: its bundled ASM is 9.6,
     * which reads up to Java 22 (major 66). Newer bytecode is refused rather than run, because
     * Lincheck silently skips instrumentation it cannot read and would then report a clean pass it
     * never verified.
     */
    private static final int LINCHECK_MAX_CLASS_VERSION = 66;
    private static final int LINCHECK_MAX_RELEASE = 22;

    private static final List<String> MAIN_CLASS_DIRS = List.of(
        "build/classes/java/main", "build/classes/kotlin/main", "target/classes", "out/classes");
    private static final List<String> TEST_CLASS_DIRS = List.of(
        "build/classes/java/test", "build/classes/kotlin/test", "target/test-classes",
        "out/test-classes");

    private LincheckRunner() {
    }

    public static Result run(Path root, JcheckConfig config, Catalog catalog) {
        List<String> notes = new ArrayList<>();
        List<Path> jars;
        try {
            PluginClasspath.Result resolved = PluginClasspath.resolveCoordinates(
                List.of("org.jetbrains.kotlinx:lincheck-jvm:" + config.lincheckVersion),
                config.pluginClasspath, root, config.pluginOffline, config.pluginRepo);
            jars = resolved.processorPath;
            notes.addAll(resolved.notes);
        } catch (RuntimeException e) {
            notes.add("Lincheck could not be resolved: " + e.getMessage());
            return new Result(List.of(), notes, false);
        }
        if (jars.isEmpty()) {
            notes.add("Lincheck could not be resolved; its tests did not run");
            return new Result(List.of(), notes, false);
        }

        BuildModel model = BuildModel.resolve(root, config, null, null, null, null);
        notes.addAll(model.notes);
        List<Path> mainClasses = existing(root, MAIN_CLASS_DIRS);
        List<Path> testRoots = new ArrayList<>(existing(root, TEST_CLASS_DIRS));
        List<Path> javaTests = testSources(model.sources, ".java");
        List<Path> kotlinTests = testSources(BuildModel.kotlinSources(root, config), ".kt");

        Path scratch = null;
        try {
            if (testRoots.isEmpty() && !javaTests.isEmpty()) {
                scratch = Files.createTempDirectory("jcheck-lincheck-classes-");
                List<String> diagnostics = new ArrayList<>();
                if (compileTests(jars, model, mainClasses, javaTests, scratch,
                        lincheckRelease(config, notes), diagnostics)) {
                    testRoots.add(scratch);
                } else {
                    notes.add("the Java Lincheck test sources did not compile: "
                        + String.join("; ", diagnostics));
                    return new Result(List.of(), notes, false);
                }
            }
            if (testRoots.isEmpty()) {
                if (!kotlinTests.isEmpty()) {
                    notes.add("the Lincheck tests are Kotlin and are not compiled: run the project's "
                        + "own build first (jcheck build), then jcheck lincheck");
                } else {
                    notes.add("no compiled test classes or Java Lincheck test sources were found");
                }
                return new Result(List.of(), notes, false);
            }

            Path runnerClasses;
            try {
                runnerClasses = compileRunner(jars, config.lincheckVersion, notes);
            } catch (IOException e) {
                notes.add("cannot compile the Lincheck runner: " + e.getMessage());
                return new Result(List.of(), notes, false);
            }
            if (runnerClasses == null) {
                return new Result(List.of(), notes, false);
            }

            Path log;
            try {
                log = Files.createTempFile("jcheck-lincheck-", ".log");
            } catch (IOException e) {
                notes.add("cannot prepare a Lincheck run: " + e.getMessage());
                return new Result(List.of(), notes, false);
            }

            try {
                List<Path> classpath = new ArrayList<>();
                classpath.add(runnerClasses);
                classpath.addAll(jars);
                classpath.addAll(model.classpath);
                classpath.addAll(mainClasses);
                classpath.addAll(testRoots);

                List<String> command = new ArrayList<>();
                command.add(javaBinary());
                command.add("-cp");
                command.add(join(dedupe(classpath)));
                command.add(RUNNER_CLASS);
                command.add(String.valueOf(Math.max(1, config.lincheckIterations)));
                command.add(config.lincheckMode);
                command.add(String.valueOf(LINCHECK_MAX_CLASS_VERSION));
                for (Path testRoot : testRoots) {
                    command.add(testRoot.toString());
                }

                ProcessBuilder pb = new ProcessBuilder(command).directory(root.toFile());
                pb.redirectErrorStream(true);
                pb.redirectOutput(log.toFile());
                Process process = pb.start();
                if (!process.waitFor(30, TimeUnit.MINUTES)) {
                    process.destroyForcibly();
                    notes.add("Lincheck did not finish within 30 minutes; its tests did not run");
                    return new Result(List.of(), notes, false);
                }
                return parse(Files.readString(log, StandardCharsets.UTF_8), javaTests, kotlinTests,
                    new SpanResolver(), catalog, config.lincheckMode, notes);
            } catch (IOException e) {
                notes.add("Lincheck could not be started: " + e.getMessage());
                return new Result(List.of(), notes, false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                notes.add("the Lincheck run was interrupted");
                return new Result(List.of(), notes, false);
            } finally {
                deleteQuietly(log);
            }
        } catch (IOException e) {
            notes.add("cannot prepare a Lincheck run: " + e.getMessage());
            return new Result(List.of(), notes, false);
        } finally {
            deleteRecursively(scratch);
        }
    }

    /** Parses the fork's output; public so the refusal path can be tested without running Lincheck. */
    public static Result parse(String output, List<Path> javaTests, List<Path> kotlinTests,
                               SpanResolver spans, Catalog catalog, String mode,
                               List<String> notes) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        int seq = 8_100_000;
        int failures = 0;
        int total = 0;
        int unsupported = 0;
        List<String> unsupportedClasses = new ArrayList<>();
        for (String line : output.split("\n", -1)) {
            String stripped = line.strip();
            Matcher unsupportedLine = UNSUPPORTED.matcher(stripped);
            if (unsupportedLine.matches()) {
                unsupported++;
                unsupportedClasses.add(unsupportedLine.group(1) + " (class file "
                    + unsupportedLine.group(2) + ")");
                continue;
            }
            Matcher done = DONE.matcher(stripped);
            if (done.matches()) {
                failures = Integer.parseInt(done.group(1));
                total = Integer.parseInt(done.group(2));
                continue;
            }
            Matcher failure = FAILURE.matcher(stripped);
            if (!failure.matches()) {
                continue;
            }
            String testClass = failure.group(1);
            String summary = failure.group(2).strip();
            Path file = resolveSource(javaTests, kotlinTests, testClass);
            Span span = file == null ? null : spanForClass(spans, file, testClass);

            CatalogEntry entry = catalog.lookup("lincheck", "LincheckFailure");
            String code = entry != null ? entry.code()
                : catalog.codeFor("lincheck", "LincheckFailure");
            Diagnostic.Builder b = Diagnostic.builder(code)
                .source("lincheck")
                .sourceKey("LincheckFailure")
                .level(Level.ERROR)
                .tier(Tier.T5)
                .message("concurrency test failed: " + summary)
                .primary(span)
                .seq(seq++);
            if (entry != null) {
                for (String help : entry.help) {
                    b.help(help);
                }
            }
            b.note("in " + testClass + " (" + mode + ")");
            diagnostics.add(b.build());
        }
        if (unsupported > 0) {
            notes.add("Lincheck could not instrument " + unsupported + " test class(es): "
                + String.join(", ", unsupportedClasses) + ". It handles class files up to major "
                + "version " + LINCHECK_MAX_CLASS_VERSION + " (Java " + LINCHECK_MAX_RELEASE
                + "); recompile the tests with --release " + LINCHECK_MAX_RELEASE + " or lower");
        }
        if (failures == 0 && total == 0) {
            notes.add("Lincheck ran no test class; nothing declares @Operation");
        } else {
            notes.add("lincheck: " + failures + " failing test(s) of " + total + " (" + mode + ")");
        }
        return new Result(diagnostics, notes, unsupported == 0);
    }

    /**
     * The release Lincheck tests are compiled at: the project's own where it is set, capped at what
     * Lincheck can instrument so a modern JDK does not turn into a silent no-op.
     */
    private static int lincheckRelease(JcheckConfig config, List<String> notes) {
        int feature = Runtime.version().feature();
        int wanted = config.release != null ? config.release : feature;
        int release = Math.min(wanted, LINCHECK_MAX_RELEASE);
        if (release != wanted) {
            notes.add("compiled the Lincheck tests at release " + release + " because Lincheck "
                + config.lincheckVersion + " cannot instrument class files newer than Java "
                + LINCHECK_MAX_RELEASE);
        }
        return release;
    }

    private static boolean compileTests(List<Path> jars, BuildModel model, List<Path> mainClasses,
                                        List<Path> sources, Path output, int release,
                                        List<String> diagnostics) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            diagnostics.add("no system Java compiler");
            return false;
        }
        List<Path> classpath = new ArrayList<>(jars);
        classpath.addAll(model.classpath);
        classpath.addAll(mainClasses);
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(collector, null,
                StandardCharsets.UTF_8)) {
            fm.setLocationFromPaths(StandardLocation.CLASS_PATH, dedupe(classpath));
            boolean ok = compiler.getTask(null, fm, collector,
                List.of("-d", output.toString(), "-proc:none", "-encoding", "UTF-8",
                    "--release", String.valueOf(release)),
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

    /** Compiles the bundled fork against the Lincheck jars, cached by source, version and JDK. */
    private static Path compileRunner(List<Path> jars, String version, List<String> notes)
            throws IOException {
        String source = readResource();
        if (source == null) {
            notes.add("the Lincheck runner source is missing from this build");
            return null;
        }
        String hash = hash(source + "|" + version + "|jdk=" + Runtime.version().feature());
        Path classes = MavenResolver.defaultCacheDir().resolve("lincheck")
            .resolve("classes-" + hash);
        if (Files.isRegularFile(classes.resolve(RUNNER_CLASS.replace('.', '/') + ".class"))) {
            return classes;
        }
        try {
            Files.createDirectories(classes.getParent());
        } catch (IOException | RuntimeException e) {
            classes = Files.createTempDirectory("jcheck-lincheck-runner-");
        }
        deleteRecursively(classes);
        Files.createDirectories(classes);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            notes.add("no system Java compiler, so the Lincheck runner cannot be compiled");
            return null;
        }
        List<String> diagnostics = new ArrayList<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(
                d -> diagnostics.add(d.getMessage(null)), null, StandardCharsets.UTF_8)) {
            fm.setLocationFromPaths(StandardLocation.CLASS_PATH, jars);
            JavaFileObject unit = new SimpleJavaFileObject(
                URI.create("string:///jcheck/lincheck/LincheckFork.java"),
                JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };
            boolean ok = compiler.getTask(null, fm, null,
                List.of("-d", classes.toString(), "-proc:none"), null, List.of(unit)).call();
            if (!ok) {
                notes.add("the Lincheck runner did not compile against lincheck " + version + ": "
                    + String.join("; ", diagnostics));
                return null;
            }
        }
        return classes;
    }

    static List<Path> testSources(List<Path> sources, String extension) {
        List<Path> out = new ArrayList<>();
        for (Path p : sources) {
            String s = p.toString();
            if ((s.contains("/src/test/") || s.contains("/src/testFixtures/"))
                && s.endsWith(extension)) {
                out.add(p);
            }
        }
        return out;
    }

    private static Path resolveSource(List<Path> javaTests, List<Path> kotlinTests, String testClass) {
        String relative = testClass.replace('.', '/');
        for (Path candidate : javaTests) {
            if (candidate.toString().endsWith(relative + ".java")) {
                return candidate;
            }
        }
        for (Path candidate : kotlinTests) {
            if (candidate.toString().endsWith(relative + ".kt")) {
                return candidate;
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
            String[] lines = content.split("\n", -1);
            Pattern decl = Pattern.compile("\\b(?:class|object)\\s+" + Pattern.quote(simple) + "\\b");
            int line = 1;
            for (int i = 0; i < lines.length; i++) {
                if (decl.matcher(lines[i]).find()) {
                    line = i + 1;
                    break;
                }
            }
            int start = offsetOfLine(content, line);
            int newline = content.indexOf('\n', start);
            int end = newline < 0 ? content.length() : newline;
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
            int newline = content.indexOf('\n', offset);
            if (newline < 0) {
                return content.length();
            }
            offset = newline + 1;
        }
        return offset;
    }

    private static List<Path> existing(Path root, List<String> candidates) {
        List<Path> out = new ArrayList<>();
        for (String candidate : candidates) {
            Path p = root.resolve(candidate);
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

    private static String readResource() throws IOException {
        try (InputStream in = LincheckRunner.class.getResourceAsStream(RUNNER_RESOURCE)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 12);
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
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
