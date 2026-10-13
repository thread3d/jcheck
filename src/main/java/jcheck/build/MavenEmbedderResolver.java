package jcheck.build;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import jcheck.plugins.MavenResolver;
import jcheck.plugins.PluginClasspath;

/**
 * Resolves a Maven project's classpath through the Maven embedder instead of the {@code mvn} binary.
 *
 * <p>The embedder runs Maven in process: a small runner source ships as a resource, is compiled at
 * run time against the resolved {@code maven-embedder} jars, and asks Maven for
 * {@code dependency:build-classpath}. Maven's own components are loaded from the installation's
 * {@code lib/} because Sisu binds them by scanning those jars. Source roots and modules still come
 * from the project's POMs, which is exact for Maven's declarative layout.
 */
public final class MavenEmbedderResolver {

    /** What the embedder produced. */
    public static final class Model {
        public final List<Path> classpath;
        public final List<String> notes;
        public final boolean resolved;

        Model(List<Path> classpath, List<String> notes, boolean resolved) {
            this.classpath = List.copyOf(classpath);
            this.notes = List.copyOf(notes);
            this.resolved = resolved;
        }
    }

    private static final String RUNNER_RESOURCE = "/jcheck/build/MavenEmbedderRunner.java";
    private static final String RUNNER_CLASS = "jcheck.build.MavenEmbedderRunner";

    private MavenEmbedderResolver() {
    }

    public static Model resolve(Path root, String configuredHome, String version, boolean offline) {
        List<String> notes = new ArrayList<>();
        Path home = findInstallation(configuredHome);
        if (home == null) {
            notes.add("no Maven installation was found for the embedder; set [project] maven_home, "
                + "$JCHECK_MAVEN_INSTALLATION, $MAVEN_HOME, or put mvn on PATH");
            return new Model(List.of(), notes, false);
        }
        List<Path> jars;
        try {
            PluginClasspath.Result resolved = PluginClasspath.resolveCoordinates(
                List.of("org.apache.maven:maven-embedder:" + version), List.of(), root, offline, null);
            jars = resolved.processorPath;
            notes.addAll(resolved.notes);
        } catch (RuntimeException e) {
            notes.add("maven-embedder could not be resolved: " + e.getMessage());
            return new Model(List.of(), notes, false);
        }
        if (jars.isEmpty()) {
            notes.add("maven-embedder " + version + " could not be resolved");
            return new Model(List.of(), notes, false);
        }

        Path cache = cacheFile(root, version, home);
        Model cached = readCache(cache);
        if (cached != null) {
            return cached;
        }

        Path runnerClasses;
        Path output = null;
        Path log = null;
        try {
            runnerClasses = compileRunner(jars, version, home, notes);
            if (runnerClasses == null) {
                return new Model(List.of(), notes, false);
            }
            output = Files.createTempFile("jcheck-embedder-", ".cp");
            Files.deleteIfExists(output);
            log = Files.createTempFile("jcheck-embedder-", ".log");

            List<String> command = new ArrayList<>();
            command.add(javaBinary());
            command.add("-cp");
            command.add(runnerClasses + java.io.File.pathSeparator + join(jars));
            command.add(RUNNER_CLASS);
            command.add(root.toString());
            command.add(output.toString());
            command.add(home.toString());
            String repo = System.getenv("JCHECK_MAVEN_REPO");
            if (repo != null && !repo.isBlank()) {
                command.add("-Dmaven.repo.local=" + repo);
            }
            if (offline) {
                command.add("-o");
            }

            ProcessBuilder pb = new ProcessBuilder(command).directory(root.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(log.toFile());
            Process process = pb.start();
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                notes.add("the Maven embedder did not finish within 10 minutes");
                return new Model(List.of(), notes, false);
            }
            if (!Files.isRegularFile(output)) {
                notes.add("the Maven embedder reported no classpath (exit " + process.exitValue()
                    + "): " + firstLine(log));
                return new Model(List.of(), notes, false);
            }
            List<Path> classpath = DependencyResolver.splitClasspath(
                Files.readString(output, StandardCharsets.UTF_8));
            if (classpath.isEmpty()) {
                notes.add("the Maven embedder produced an empty classpath");
                return new Model(List.of(), notes, false);
            }
            Model model = new Model(classpath,
                List.of("classpath came from the Maven embedder (maven-embedder " + version + ")"),
                true);
            writeCache(cache, model);
            return model;
        } catch (IOException e) {
            notes.add("the Maven embedder could not be started: " + e.getMessage());
            return new Model(List.of(), notes, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notes.add("the Maven embedder run was interrupted");
            return new Model(List.of(), notes, false);
        } finally {
            deleteQuietly(output);
            deleteQuietly(log);
        }
    }

    /**
     * The Maven installation: the configured path, {@code $JCHECK_MAVEN_INSTALLATION},
     * {@code $MAVEN_HOME}, {@code $M2_HOME}, or the installation an {@code mvn} on PATH belongs to.
     */
    public static Path findInstallation(String configured) {
        List<String> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            candidates.add(configured);
        }
        for (String env : List.of("JCHECK_MAVEN_INSTALLATION", "MAVEN_HOME", "M2_HOME")) {
            String value = System.getenv(env);
            if (value != null && !value.isBlank()) {
                candidates.add(value);
            }
        }
        for (String candidate : candidates) {
            Path p = Path.of(candidate);
            if (Files.isDirectory(p.resolve("lib"))) {
                return p;
            }
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            Path mvn = Path.of(dir).resolve("mvn");
            if (Files.isExecutable(mvn)) {
                Path bin = mvn.toAbsolutePath().normalize().getParent();
                Path installation = bin == null ? null : bin.getParent();
                if (installation != null && Files.isDirectory(installation.resolve("lib"))) {
                    return installation;
                }
            }
        }
        return null;
    }

    private static Path cacheFile(Path root, String version, Path home) {
        return MavenResolver.defaultCacheDir().resolve("embedder")
            .resolve("maven-" + DependencyResolver.fingerprint(root) + ".txt");
    }

    private static Model readCache(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            List<Path> classpath = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    classpath.add(Path.of(line.strip()));
                }
            }
            if (classpath.isEmpty()) {
                return null;
            }
            return new Model(classpath, List.of("classpath came from the Maven embedder (cached)"),
                true);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void writeCache(Path file, Model model) {
        StringBuilder sb = new StringBuilder();
        for (Path p : model.classpath) {
            sb.append(p).append('\n');
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // an unwritable cache is not fatal; the classpath is still returned in memory
        }
    }

    private static Path compileRunner(List<Path> jars, String version, Path home,
                                      List<String> notes) throws IOException {
        String source = readResource();
        if (source == null) {
            notes.add("the Maven embedder runner source is missing from this build");
            return null;
        }
        String hash = hash(source + "|" + version + "|" + home + "|jdk="
            + Runtime.version().feature());
        Path classes = MavenResolver.defaultCacheDir().resolve("embedder")
            .resolve("classes-" + hash);
        if (Files.isRegularFile(classes.resolve(RUNNER_CLASS.replace('.', '/') + ".class"))) {
            return classes;
        }
        try {
            Files.createDirectories(classes.getParent());
        } catch (IOException | RuntimeException e) {
            classes = Files.createTempDirectory("jcheck-embedder-classes-");
        }
        deleteRecursively(classes);
        Files.createDirectories(classes);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            notes.add("no system Java compiler, so the Maven embedder runner cannot be compiled");
            return null;
        }
        List<String> diagnostics = new ArrayList<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(
                d -> diagnostics.add(d.getMessage(null)), null, StandardCharsets.UTF_8)) {
            fm.setLocationFromPaths(StandardLocation.CLASS_PATH, jars);
            JavaFileObject unit = new SimpleJavaFileObject(
                URI.create("string:///jcheck/build/MavenEmbedderRunner.java"),
                JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };
            boolean ok = compiler.getTask(null, fm, null,
                List.of("-d", classes.toString(), "-proc:none"), null, List.of(unit)).call();
            if (!ok) {
                notes.add("the Maven embedder runner did not compile against "
                    + version + ": " + String.join("; ", diagnostics));
                return null;
            }
        }
        return classes;
    }

    private static String readResource() throws IOException {
        try (InputStream in = MavenEmbedderResolver.class.getResourceAsStream(RUNNER_RESOURCE)) {
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
