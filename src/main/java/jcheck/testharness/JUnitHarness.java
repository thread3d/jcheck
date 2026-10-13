package jcheck.testharness;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import jcheck.build.BuildModel;
import jcheck.config.JcheckConfig;
import jcheck.plugins.MavenResolver;

/**
 * Runs a project's tests through the JUnit Platform Console Launcher with jcheck's extension
 * attached.
 *
 * <p>The extension source ships as a resource and is compiled here against the project's own JUnit
 * Platform, then registered through JUnit's extension autodetection. That is why jcheck itself has
 * no JUnit dependency and why the extension always matches the platform version on the test
 * classpath.
 */
public final class JUnitHarness {

    /** Outcome of one launcher run. */
    public static final class Result {
        public final int exitCode;
        public final List<String> notes;
        /** False when the harness itself could not run, as opposed to tests failing. */
        public final boolean available;

        Result(int exitCode, List<String> notes, boolean available) {
            this.exitCode = exitCode;
            this.notes = List.copyOf(notes);
            this.available = available;
        }
    }

    private static Result unavailable(List<String> notes) {
        return new Result(2, notes, false);
    }

    /** Default console launcher, resolved when the project does not point at its own. */
    public static final String DEFAULT_LAUNCHER =
        "org.junit.platform:junit-platform-console-standalone:1.10.2";

    private static final String EXTENSION_RESOURCE = "/jcheck/junit/JcheckExtension.java";
    private static final String EXTENSION_CLASS = "jcheck.junit.JcheckExtension";
    private static final String EXTENSION_SERVICE =
        "META-INF/services/org.junit.jupiter.api.extension.Extension";

    private static final List<String> TEST_CLASS_DIRS = List.of(
        "build/classes/java/test", "build/classes/kotlin/test", "target/test-classes",
        "out/test-classes");

    private JUnitHarness() {
    }

    public static Result run(Path root, JcheckConfig config, List<String> notes) {
        Path launcher = launcherJar(root, config, notes);
        if (launcher == null) {
            return unavailable(notes);
        }

        Path extensionClasses;
        try {
            extensionClasses = compileExtension(launcher, notes);
        } catch (IOException e) {
            notes.add("cannot compile the jcheck JUnit extension: " + e.getMessage());
            return unavailable(notes);
        }
        if (extensionClasses == null) {
            return unavailable(notes);
        }

        BuildModel model = BuildModel.resolve(root, config, null, null, null, null);
        notes.addAll(model.notes);

        List<Path> classpath = new ArrayList<>();
        classpath.add(extensionClasses);
        classpath.add(launcher);
        classpath.addAll(model.classpath);

        List<Path> scanRoots = new ArrayList<>();
        for (String candidate : TEST_CLASS_DIRS) {
            Path p = root.resolve(candidate);
            if (Files.isDirectory(p)) {
                scanRoots.add(p);
                if (!classpath.contains(p)) {
                    classpath.add(p);
                }
            }
        }
        if (scanRoots.isEmpty()) {
            notes.add("no compiled test classes were found (looked for "
                + String.join(", ", TEST_CLASS_DIRS) + "); run the build tool's test-compile first");
            return unavailable(notes);
        }

        Path reportDir = root.resolve(config.reportDir == null || config.reportDir.isBlank()
            ? "build/test-results/test" : config.reportDir);
        try {
            Files.createDirectories(reportDir);
        } catch (IOException e) {
            notes.add("cannot create the report directory " + reportDir + ": " + e.getMessage());
            return unavailable(notes);
        }

        List<String> command = new ArrayList<>();
        command.add(javaBinary());
        command.add("-Djunit.jupiter.extensions.autodetection.enabled=true");
        command.add("-Djcheck.junit.timeout.seconds=" + config.testTimeoutSeconds);
        command.add("-Djcheck.junit.thread_leaks=" + config.threadLeaks);
        command.add("-Djcheck.junit.fd_leaks=" + config.fdLeaks);
        if (config.randomTestOrder) {
            long seed = System.nanoTime();
            command.add("-Djunit.jupiter.testmethod.order.default="
                + "org.junit.jupiter.api.MethodOrderer$Random");
            command.add("-Djunit.jupiter.execution.order.random.seed=" + seed);
            notes.add("random test order, seed " + seed);
        }
        command.add("-cp");
        command.add(join(classpath));
        command.add("org.junit.platform.console.ConsoleLauncher");
        command.add("execute");
        for (Path scanRoot : scanRoots) {
            command.add("--scan-classpath=" + scanRoot);
        }
        command.add("--reports-dir=" + reportDir);
        command.add("--details=summary");
        command.add("--disable-ansi-colors");

        notes.add("ran tests through the JUnit Platform Console Launcher with jcheck-junit attached");
        try {
            ProcessBuilder pb = new ProcessBuilder(command).directory(root.toFile()).inheritIO();
            return new Result(pb.start().waitFor(), notes, true);
        } catch (IOException e) {
            notes.add("cannot start the JUnit Platform Console Launcher: " + e.getMessage());
            return unavailable(notes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notes.add("the test run was interrupted");
            return unavailable(notes);
        }
    }

    /** The JUnit Platform Console Launcher, from jcheck.toml or resolved; empty when unavailable. */
    public static List<Path> resolveLauncher(Path root, JcheckConfig config, List<String> notes) {
        Path jar = launcherJar(root, config, notes);
        return jar == null ? List.of() : List.of(jar);
    }

    private static Path launcherJar(Path root, JcheckConfig config, List<String> notes) {
        if (config.consoleLauncher != null && !config.consoleLauncher.isBlank()) {
            Path p = Path.of(config.consoleLauncher);
            Path resolved = p.isAbsolute() ? p : root.resolve(p);
            if (Files.isRegularFile(resolved)) {
                notes.add("console launcher from jcheck.toml: " + config.consoleLauncher);
                return resolved;
            }
            notes.add("console launcher " + config.consoleLauncher + " was not found");
            return null;
        }
        try {
            MavenResolver resolver = MavenResolver.standard(config.pluginOffline);
            List<Path> jars = resolver.resolve(List.of(DEFAULT_LAUNCHER));
            notes.addAll(resolver.notes());
            if (jars.isEmpty()) {
                notes.add("could not resolve the JUnit Platform Console Launcher; set "
                    + "[tests] console_launcher in jcheck.toml or install one");
                return null;
            }
            return jars.get(0);
        } catch (RuntimeException e) {
            notes.add("could not resolve the JUnit Platform Console Launcher: " + e.getMessage());
            return null;
        }
    }

    /**
     * Compiles the bundled extension source against the launcher jar, caching the result under a
     * hash of the source so an upgraded jcheck recompiles and a warm daemon does not.
     */
    private static Path compileExtension(Path launcherJar, List<String> notes) throws IOException {
        String source = readResource();
        if (source == null) {
            notes.add("the jcheck JUnit extension source is missing from this build");
            return null;
        }
        Path classes = MavenResolver.defaultCacheDir().resolve("junit").resolve("classes-" + hash(source));
        Path marker = classes.resolve(EXTENSION_SERVICE);
        if (Files.isRegularFile(marker) && Files.isRegularFile(
                classes.resolve(EXTENSION_CLASS.replace('.', '/') + ".class"))) {
            return classes;
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            notes.add("no system Java compiler, so the jcheck JUnit extension cannot be compiled");
            return null;
        }
        deleteRecursively(classes);
        Files.createDirectories(classes);
        List<String> diagnostics = new ArrayList<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(
                d -> diagnostics.add(d.getMessage(null)), null, StandardCharsets.UTF_8)) {
            fm.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of(launcherJar));
            JavaFileObject unit = new SimpleJavaFileObject(
                URI.create("string:///jcheck/junit/JcheckExtension.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };
            boolean ok = compiler.getTask(null, fm, null,
                List.of("-d", classes.toString(), "-proc:none"), null, List.of(unit)).call();
            if (!ok) {
                notes.add("the jcheck JUnit extension did not compile against the launcher: "
                    + String.join("; ", diagnostics));
                return null;
            }
        }
        Path service = classes.resolve(EXTENSION_SERVICE);
        Files.createDirectories(service.getParent());
        Files.writeString(service, EXTENSION_CLASS + "\n", StandardCharsets.UTF_8);
        return classes;
    }

    private static String readResource() throws IOException {
        try (InputStream in = JUnitHarness.class.getResourceAsStream(EXTENSION_RESOURCE)) {
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
