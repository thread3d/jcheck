package jcheck.build;

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
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import jcheck.plugins.MavenResolver;

/**
 * Reads a Gradle project's model through the Gradle Tooling API.
 *
 * <p>Unlike running the {@code gradle} binary, the Tooling API returns the project's own view of
 * itself: every subproject, its main and test source directories, and the resolved external
 * classpath. The caller therefore gets real modules and real source sets instead of a classpath
 * string and a directory walk.
 *
 * <p>jcheck has no Gradle dependency: a small runner source ships as a resource, is compiled at run
 * time against the jars in the Gradle installation's {@code lib/}, and runs in a forked JVM. The
 * model is cached under the jcheck cache directory, keyed by the build files' fingerprint.
 */
public final class GradleToolingResolver {

    /** What the Tooling API reported. */
    public static final class Model {
        public final List<Path> classpath;
        public final List<Path> sourceRoots;
        /** Project directories, one per (sub)project. */
        public final List<Path> modules;
        public final List<String> notes;
        public final boolean resolved;

        Model(List<Path> classpath, List<Path> sourceRoots, List<Path> modules,
              List<String> notes, boolean resolved) {
            this.classpath = List.copyOf(classpath);
            this.sourceRoots = List.copyOf(sourceRoots);
            this.modules = List.copyOf(modules);
            this.notes = List.copyOf(notes);
            this.resolved = resolved;
        }
    }

    private static final String RUNNER_RESOURCE = "/jcheck/build/ToolingModelRunner.java";
    private static final String RUNNER_CLASS = "jcheck.build.ToolingModelRunner";

    private GradleToolingResolver() {
    }

    public static Model resolve(Path root, String configuredInstallation) {
        List<String> notes = new ArrayList<>();
        Path installation = findInstallation(configuredInstallation);
        if (installation == null) {
            notes.add("no Gradle installation was found for the Tooling API; set [project] "
                + "gradle_home or $JCHECK_GRADLE_INSTALLATION, or jcheck runs the project's Gradle "
                + "instead");
            return new Model(List.of(), List.of(), List.of(), notes, false);
        }
        List<Path> jars = libraryJars(installation);
        if (jars.isEmpty()) {
            notes.add("the Gradle installation at " + installation
                + " has no lib/*.jar, so the Tooling API was skipped");
            return new Model(List.of(), List.of(), List.of(), notes, false);
        }

        Path cache = cacheFile(root);
        Model cached = readCache(cache);
        if (cached != null) {
            return cached;
        }

        Path runnerClasses;
        try {
            runnerClasses = compileRunner(root, jars, installation, notes);
        } catch (IOException e) {
            notes.add("cannot compile the Gradle Tooling API runner: " + e.getMessage());
            return new Model(List.of(), List.of(), List.of(), notes, false);
        }
        if (runnerClasses == null) {
            return new Model(List.of(), List.of(), List.of(), notes, false);
        }

        Path log = null;
        try {
            log = Files.createTempFile("jcheck-tooling-", ".log");
            List<String> command = new ArrayList<>();
            command.add(javaBinary());
            command.add("-cp");
            command.add(runnerClasses + java.io.File.pathSeparator + join(jars));
            command.add(RUNNER_CLASS);
            command.add(root.toString());
            command.add(installation.toString());
            ProcessBuilder pb = new ProcessBuilder(command).directory(root.toFile());
            String gradleHome = System.getenv("JCHECK_GRADLE_HOME");
            if (gradleHome != null && !gradleHome.isBlank()) {
                pb.environment().put("GRADLE_USER_HOME", gradleHome);
            }
            pb.redirectErrorStream(true);
            pb.redirectOutput(log.toFile());
            Process process = pb.start();
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                notes.add("the Gradle Tooling API did not answer within 10 minutes");
                return new Model(List.of(), List.of(), List.of(), notes, false);
            }
            Model model = parse(Files.readString(log, StandardCharsets.UTF_8));
            if (!model.resolved) {
                notes.add("the Gradle Tooling API returned no project model: " + firstLine(log));
                return new Model(List.of(), List.of(), model.modules, notes, false);
            }
            notes.addAll(model.notes);
            writeCache(cache, model);
            return model;
        } catch (IOException e) {
            notes.add("the Gradle Tooling API runner could not be started: " + e.getMessage());
            return new Model(List.of(), List.of(), List.of(), notes, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notes.add("the Gradle Tooling API run was interrupted");
            return new Model(List.of(), List.of(), List.of(), notes, false);
        } finally {
            deleteQuietly(log);
        }
    }

    /** Parses the runner's tab-separated records; {@code resolved} is false without a project. */
    public static Model parse(String output) {
        List<Path> classpath = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        List<Path> modules = new ArrayList<>();
        for (String raw : output.split("\n", -1)) {
            String line = raw.strip();
            int tab = line.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            String kind = line.substring(0, tab);
            String rest = line.substring(tab + 1);
            switch (kind) {
                case "PROJECT" -> {
                    int second = rest.indexOf('\t');
                    String dir = second < 0 ? "" : rest.substring(second + 1).strip();
                    if (!dir.isEmpty() && !"null".equals(dir)) {
                        add(modules, dir);
                    }
                }
                case "SOURCE" -> add(sources, rest.strip());
                case "CLASSPATH" -> add(classpath, rest.strip());
                default -> {
                    // ignore anything else the runner or Gradle logged
                }
            }
        }
        List<String> notes = new ArrayList<>();
        if (!modules.isEmpty()) {
            notes.add("classpath and source roots came from the Gradle Tooling API ("
                + modules.size() + " project(s))");
        }
        return new Model(classpath, sources, modules, notes, !modules.isEmpty());
    }

    private static void add(List<Path> paths, String value) {
        if (value.isEmpty()) {
            return;
        }
        Path p = Path.of(value);
        if (!paths.contains(p)) {
            paths.add(p);
        }
    }

    /**
     * The Gradle installation: the configured path, {@code $JCHECK_GRADLE_INSTALLATION},
     * {@code $GRADLE_HOME}, or the installation a {@code gradle} on PATH belongs to.
     */
    public static Path findInstallation(String configured) {
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured);
            if (Files.isDirectory(p.resolve("lib"))) {
                return p;
            }
        }
        for (String env : List.of("JCHECK_GRADLE_INSTALLATION", "GRADLE_HOME")) {
            String value = System.getenv(env);
            if (value != null && !value.isBlank()) {
                Path p = Path.of(value);
                if (Files.isDirectory(p.resolve("lib"))) {
                    return p;
                }
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
            Path candidate = Path.of(dir).resolve("gradle");
            if (Files.isExecutable(candidate)) {
                Path bin = candidate.toAbsolutePath().normalize().getParent();
                Path installation = bin == null ? null : bin.getParent();
                if (installation != null && Files.isDirectory(installation.resolve("lib"))) {
                    return installation;
                }
            }
        }
        return null;
    }

    private static List<Path> libraryJars(Path installation) {
        Path lib = installation.resolve("lib");
        List<Path> jars = new ArrayList<>();
        try (var stream = Files.list(lib)) {
            stream.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(jars::add);
        } catch (IOException e) {
            return List.of();
        }
        return jars;
    }

    private static Path cacheFile(Path root) {
        return MavenResolver.defaultCacheDir().resolve("tooling")
            .resolve("gradle-" + DependencyResolver.fingerprint(root) + ".txt");
    }

    private static Model readCache(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            List<Path> classpath = new ArrayList<>();
            List<Path> sources = new ArrayList<>();
            List<Path> modules = new ArrayList<>();
            List<Path> target = classpath;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                switch (line) {
                    case "sources" -> target = sources;
                    case "modules" -> target = modules;
                    default -> {
                        if (!line.isBlank()) {
                            target.add(Path.of(line.strip()));
                        }
                    }
                }
            }
            if (modules.isEmpty()) {
                return null;
            }
            return new Model(classpath, sources, modules, List.of(), true);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void writeCache(Path file, Model model) {
        StringBuilder sb = new StringBuilder();
        for (Path p : model.classpath) {
            sb.append(p).append('\n');
        }
        sb.append("sources\n");
        for (Path p : model.sourceRoots) {
            sb.append(p).append('\n');
        }
        sb.append("modules\n");
        for (Path p : model.modules) {
            sb.append(p).append('\n');
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // an unwritable cache is not fatal; the model is still returned in memory
        }
    }

    private static Path compileRunner(Path root, List<Path> jars, Path installation,
                                      List<String> notes) throws IOException {
        String source = readResource();
        if (source == null) {
            notes.add("the Gradle Tooling API runner source is missing from this build");
            return null;
        }
        String hash = hash(source + "|" + installation + "|jdk=" + Runtime.version().feature());
        Path classes = MavenResolver.defaultCacheDir().resolve("tooling").resolve("classes-" + hash);
        if (Files.isRegularFile(classes.resolve(RUNNER_CLASS.replace('.', '/') + ".class"))) {
            return classes;
        }
        try {
            Files.createDirectories(classes.getParent());
        } catch (IOException | RuntimeException e) {
            classes = Files.createTempDirectory("jcheck-tooling-classes-");
        }
        deleteRecursively(classes);
        Files.createDirectories(classes);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            notes.add("no system Java compiler, so the Gradle Tooling API runner cannot be compiled");
            return null;
        }
        List<String> diagnostics = new ArrayList<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(
                d -> diagnostics.add(d.getMessage(null)), null, StandardCharsets.UTF_8)) {
            fm.setLocationFromPaths(StandardLocation.CLASS_PATH, jars);
            JavaFileObject unit = new SimpleJavaFileObject(
                URI.create("string:///jcheck/build/ToolingModelRunner.java"),
                JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };
            boolean ok = compiler.getTask(null, fm, null,
                List.of("-d", classes.toString(), "-proc:none"), null, List.of(unit)).call();
            if (!ok) {
                notes.add("the Gradle Tooling API runner did not compile against "
                    + installation + ": " + String.join("; ", diagnostics));
                return null;
            }
        }
        return classes;
    }

    private static String readResource() throws IOException {
        try (InputStream in = GradleToolingResolver.class.getResourceAsStream(RUNNER_RESOURCE)) {
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
