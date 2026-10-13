package jcheck.plugins;

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
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import jcheck.config.JcheckConfig;

/**
 * Applies OpenRewrite recipes to a project's sources, in place.
 *
 * <p>OpenRewrite is a library rather than a tool, and jcheck deliberately has no OpenRewrite
 * dependency. The small runner source that ships as a resource is therefore compiled against the
 * resolved jars at run time and executed in a forked JVM, exactly as the jcheck JUnit extension is.
 * That keeps the version flexible and the compile-time footprint zero.
 *
 * <p>Recipes from {@code rewrite-static-analysis} reference the Kotlin AST in their
 * {@code isAcceptable} checks, so {@code rewrite-kotlin} is on the runner classpath whenever those
 * recipes are used; without it they fail with a {@code NoClassDefFoundError}.
 */
public final class OpenRewriteRunner {

    /** Outcome of one recipe run. */
    public static final class Result {
        /** Files the recipes changed, in the order they were reported. */
        public final List<String> changedFiles;
        public final List<String> notes;
        public final boolean ran;

        Result(List<String> changedFiles, List<String> notes, boolean ran) {
            this.changedFiles = List.copyOf(changedFiles);
            this.notes = List.copyOf(notes);
            this.ran = ran;
        }
    }

    private static final String RUNNER_RESOURCE = "/jcheck/rewrite/RewriteRunner.java";
    private static final String RUNNER_CLASS = "jcheck.rewrite.RewriteRunner";
    private static final Pattern CHANGE = Pattern.compile("^CHANGED\t(.+?)\t(.+)$");
    private static final Pattern VERSION = Pattern.compile("^\\d+\\.\\d+.*");

    private OpenRewriteRunner() {
    }

    public static Result run(Path root, JcheckConfig config, List<Path> sources, List<Path> classpath) {
        List<String> notes = new ArrayList<>();
        if (sources == null || sources.isEmpty()) {
            notes.add("no sources to rewrite");
            return new Result(List.of(), notes, false);
        }
        if (config.rewriteRecipes.isEmpty()) {
            notes.add("no OpenRewrite recipes are configured");
            return new Result(List.of(), notes, false);
        }

        List<Path> jars;
        try {
            jars = resolveJars(root, config, notes);
        } catch (RuntimeException e) {
            notes.add("OpenRewrite jars could not be resolved: " + e.getMessage());
            return new Result(List.of(), notes, false);
        }
        if (jars.isEmpty()) {
            notes.add("OpenRewrite jars could not be resolved");
            return new Result(List.of(), notes, false);
        }

        Path runnerClasses;
        try {
            runnerClasses = compileRunner(root, jars, config.rewriteVersion, notes);
        } catch (IOException e) {
            notes.add("cannot compile the OpenRewrite runner: " + e.getMessage());
            return new Result(List.of(), notes, false);
        }
        if (runnerClasses == null) {
            return new Result(List.of(), notes, false);
        }

        Path pathList;
        Path log;
        try {
            pathList = Files.createTempFile("jcheck-rewrite-", ".txt");
            StringBuilder sb = new StringBuilder();
            for (Path p : sources) {
                sb.append(p.toAbsolutePath()).append('\n');
            }
            Files.writeString(pathList, sb.toString(), StandardCharsets.UTF_8);
            log = Files.createTempFile("jcheck-rewrite-", ".log");
        } catch (IOException e) {
            notes.add("cannot prepare an OpenRewrite run: " + e.getMessage());
            return new Result(List.of(), notes, false);
        }

        try {
            List<String> command = new ArrayList<>();
            command.add(javaBinary());
            command.add("-cp");
            command.add(runnerClasses + java.io.File.pathSeparator + join(jars));
            command.add(RUNNER_CLASS);
            command.add(String.join(",", config.rewriteRecipes));
            command.add(root.toString());
            command.add(pathList.toString());
            command.add(classpath == null ? "" : join(classpath));

            ProcessBuilder pb = new ProcessBuilder(command).directory(root.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(log.toFile());
            Process process = pb.start();
            if (!process.waitFor(30, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                notes.add("OpenRewrite did not finish within 30 minutes");
                return new Result(List.of(), notes, false);
            }
            List<String> changed = new ArrayList<>();
            for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                Matcher m = CHANGE.matcher(line.strip());
                if (m.matches() && !changed.contains(m.group(1))) {
                    changed.add(m.group(1));
                }
            }
            notes.add("OpenRewrite: " + changed.size() + " file(s) changed by "
                + config.rewriteRecipes.size() + " recipe(s)");
            if (changed.isEmpty() && process.exitValue() != 0) {
                notes.add("OpenRewrite exited " + process.exitValue() + ": " + firstLine(log));
                return new Result(List.of(), notes, false);
            }
            return new Result(changed, notes, true);
        } catch (IOException e) {
            notes.add("OpenRewrite could not be started: " + e.getMessage());
            return new Result(List.of(), notes, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notes.add("OpenRewrite was interrupted");
            return new Result(List.of(), notes, false);
        } finally {
            deleteQuietly(pathList);
            deleteQuietly(log);
        }
    }

    /** The static-analysis recipes plus the matching Java parser and, when needed, the Kotlin one. */
    static List<Path> resolveJars(Path root, JcheckConfig config, List<String> notes) {
        PluginClasspath.Result base = PluginClasspath.resolveCoordinates(
            List.of("org.openrewrite.recipe:rewrite-static-analysis:" + config.rewriteVersion),
            config.pluginClasspath, root, config.pluginOffline, config.pluginRepo);
        notes.addAll(base.notes);

        String rewriteVersion = versionOf(base.processorPath, "rewrite-java-");
        if (rewriteVersion == null) {
            notes.add("cannot determine the OpenRewrite version from the resolved jars");
            return List.of();
        }
        List<String> coordinates = new ArrayList<>();
        coordinates.add("org.openrewrite:rewrite-java-" + parserArtifact() + ":" + rewriteVersion);
        if (config.rewriteKotlin) {
            coordinates.add("org.openrewrite:rewrite-kotlin:" + rewriteVersion);
        }
        PluginClasspath.Result extra = PluginClasspath.resolveCoordinates(coordinates,
            config.pluginClasspath, root, config.pluginOffline, config.pluginRepo);
        notes.addAll(extra.notes);

        LinkedHashSet<Path> all = new LinkedHashSet<>(base.processorPath);
        all.addAll(extra.processorPath);
        return new ArrayList<>(all);
    }

    /** The parser artifact matching the running JDK. */
    static String parserArtifact() {
        return Runtime.version().feature() <= 20 ? "17" : "21";
    }

    /** The {@code rewrite-java-<version>.jar} version, or null. */
    static String versionOf(List<Path> jars, String prefix) {
        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            if (name.startsWith(prefix) && name.endsWith(".jar")) {
                String candidate = name.substring(prefix.length(), name.length() - ".jar".length());
                if (VERSION.matcher(candidate).matches()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * Compiles the bundled runner against the resolved jars, caching by source hash and version so
     * an upgraded jcheck or OpenRewrite recompiles and a warm daemon does not.
     */
    private static Path compileRunner(Path root, List<Path> jars, String version, List<String> notes)
            throws IOException {
        String source = readResource();
        if (source == null) {
            notes.add("the OpenRewrite runner source is missing from this build");
            return null;
        }
        String hash = hash(source + "|" + version + "|" + parserArtifact()
            + "|jdk=" + Runtime.version().feature());
        Path classes = MavenResolver.defaultCacheDir().resolve("rewrite").resolve("classes-" + hash);
        Path marker = classes.resolve(RUNNER_CLASS.replace('.', '/') + ".class");
        if (Files.isRegularFile(marker)) {
            return classes;
        }
        try {
            Files.createDirectories(classes.getParent());
        } catch (IOException | RuntimeException e) {
            classes = Files.createTempDirectory("jcheck-rewrite-classes-");
        }
        deleteRecursively(classes);
        Files.createDirectories(classes);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            notes.add("no system Java compiler, so the OpenRewrite runner cannot be compiled");
            return null;
        }
        List<String> diagnostics = new ArrayList<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(
                d -> diagnostics.add(d.getMessage(null)), null, StandardCharsets.UTF_8)) {
            fm.setLocationFromPaths(StandardLocation.CLASS_PATH, jars);
            JavaFileObject unit = new SimpleJavaFileObject(
                URI.create("string:///jcheck/rewrite/RewriteRunner.java"),
                JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };
            boolean ok = compiler.getTask(null, fm, null,
                List.of("-d", classes.toString(), "-proc:none"), null, List.of(unit)).call();
            if (!ok) {
                notes.add("the OpenRewrite runner did not compile against the resolved jars: "
                    + String.join("; ", diagnostics));
                return null;
            }
        }
        return classes;
    }

    private static String readResource() throws IOException {
        try (InputStream in = OpenRewriteRunner.class.getResourceAsStream(RUNNER_RESOURCE)) {
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
