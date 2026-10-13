package jcheck.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jcheck.plugins.MavenResolver;

/**
 * Asks the project's build tool for its dependency classpath, so third-party types resolve the way
 * the build itself resolves them.
 *
 * <p>Gradle is asked through an init script that prints the compile/runtime/annotation-processor
 * configurations between markers; Maven is asked through {@code dependency:build-classpath}. The
 * answer is cached under the jcheck cache directory and keyed by the build files' fingerprints, so
 * a warm daemon does not pay for it twice.
 *
 * <p>When the tool is missing or fails, the result is empty and carries the reason; the caller
 * keeps its heuristic classpath and reports the limitation rather than pretending the graph was
 * read.
 */
public final class DependencyResolver {

    /** What the build tool reported. */
    public static final class Result {
        public final List<Path> classpath;
        public final List<Path> processorPath;
        /** Source roots the build tool declared (Gradle source sets; empty for Maven). */
        public final List<Path> sourceRoots;
        public final List<String> notes;
        public final boolean resolved;

        Result(List<Path> classpath, List<Path> processorPath, List<Path> sourceRoots,
               List<String> notes, boolean resolved) {
            this.classpath = List.copyOf(classpath);
            this.processorPath = List.copyOf(processorPath);
            this.sourceRoots = List.copyOf(sourceRoots);
            this.notes = List.copyOf(notes);
            this.resolved = resolved;
        }
    }

    private static final String BEGIN = "JCHECK_CLASSPATH_BEGIN";
    private static final String END = "JCHECK_CLASSPATH_END";
    private static final String PROCESSOR = "JCHECK_PROCESSOR";
    private static final String SOURCES = "JCHECK_SOURCES";

    private DependencyResolver() {
    }

    public static Result resolve(Path root, String tool, Path executable) {
        List<String> notes = new ArrayList<>();
        if (executable == null) {
            notes.add(tool + " is not installed, so its dependency graph was not read; "
                + "third-party types may appear unresolved. Pass --classpath to close that gap.");
            return new Result(List.of(), List.of(), List.of(), notes, false);
        }

        Path cached = cacheFile(root, tool);
        Result fromCache = readCache(cached);
        if (fromCache != null) {
            return fromCache;
        }

        Result result = "gradle".equals(tool) ? gradle(root, executable) : maven(root, executable);
        if (result.resolved) {
            writeCache(cached, result);
        }
        notes.addAll(result.notes);
        return new Result(result.classpath, result.processorPath, result.sourceRoots, notes,
            result.resolved);
    }

    // ---- gradle ------------------------------------------------------------

    private static Result gradle(Path root, Path executable) {
        Path initScript = null;
        try {
            initScript = Files.createTempFile("jcheck-gradle-", ".gradle");
            Files.writeString(initScript, INIT_SCRIPT, StandardCharsets.UTF_8);
            List<String> command = List.of(executable.toString(), "-q", "--console=plain",
                "-I", initScript.toString(), "jcheckClasspath");
            ProcessBuilder pb = new ProcessBuilder(command).directory(root.toFile());
            String gradleHome = System.getenv("JCHECK_GRADLE_HOME");
            if (gradleHome != null && !gradleHome.isBlank()) {
                pb.environment().put("GRADLE_USER_HOME", gradleHome);
            }
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return failure("gradle did not finish within 5 minutes");
            }
            Parsed parsed = parseGradleOutput(output);
            if (process.exitValue() != 0 || parsed == null) {
                return failure("gradle could not report its classpath: " + firstLine(output));
            }
            return new Result(parsed.classpath, parsed.processorPath, parsed.sourceRoots,
                List.of(), true);
        } catch (IOException e) {
            return failure("gradle could not be started: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure("gradle was interrupted");
        } finally {
            deleteQuietly(initScript);
        }
    }

    /** The init script adds the task to every project and prints the resolved files as paths. */
    private static final String INIT_SCRIPT =
        "allprojects { p ->\n"
        + "  p.tasks.register('jcheckClasspath') {\n"
        + "    doLast {\n"
        + "      def seen = new LinkedHashSet<String>()\n"
        + "      ['compileClasspath','runtimeClasspath','testCompileClasspath',"
        + "'testRuntimeClasspath'].each { n ->\n"
        + "        try { p.configurations.getByName(n).files.each { seen.add(it.absolutePath) } }"
        + " catch (Exception ignored) { }\n"
        + "      }\n"
        + "      println '" + BEGIN + "'\n"
        + "      seen.each { println it }\n"
        + "      println '" + PROCESSOR + "'\n"
        + "      def processors = new LinkedHashSet<String>()\n"
        + "      ['annotationProcessor','errorprone'].each { n ->\n"
        + "        try { p.configurations.getByName(n).files.each { processors.add(it.absolutePath) } }"
        + " catch (Exception ignored) { }\n"
        + "      }\n"
        + "      processors.each { println it }\n"
        + "      println '" + SOURCES + "'\n"
        + "      def sourceDirs = new LinkedHashSet<String>()\n"
        + "      try {\n"
        + "        p.sourceSets.each { ss ->\n"
        + "          ss.java.srcDirs.each { d -> if (d.exists()) sourceDirs.add(d.absolutePath) }\n"
        + "          try { ss.kotlin.srcDirs.each { d -> if (d.exists()) sourceDirs.add(d.absolutePath) } } catch (Exception ignored) { }\n"
        + "        }\n"
        + "      } catch (Exception ignored) { }\n"
        + "      sourceDirs.each { println it }\n"
        + "      println '" + END + "'\n"
        + "    }\n"
        + "  }\n"
        + "}\n";

    /** The classpaths and source roots a build tool reported. */
    public static final class Parsed {
        public final List<Path> classpath;
        public final List<Path> processorPath;
        public final List<Path> sourceRoots;

        Parsed(List<Path> classpath, List<Path> processorPath, List<Path> sourceRoots) {
            this.classpath = classpath;
            this.processorPath = processorPath;
            this.sourceRoots = sourceRoots;
        }
    }

    /** Extracts the sections; null when the begin/end markers are absent. */
    public static Parsed parseGradleOutput(String output) {
        List<Path> classpath = new ArrayList<>();
        List<Path> processors = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        List<Path> target = null;
        boolean sawBegin = false;
        for (String raw : output.split("\n", -1)) {
            String line = raw.strip();
            if (BEGIN.equals(line)) {
                sawBegin = true;
                target = classpath;
                continue;
            }
            if (PROCESSOR.equals(line)) {
                target = processors;
                continue;
            }
            if (SOURCES.equals(line)) {
                target = sources;
                continue;
            }
            if (END.equals(line)) {
                return sawBegin ? new Parsed(classpath, processors, sources) : null;
            }
            if (target != null && !line.isEmpty()) {
                target.add(Path.of(line));
            }
        }
        return null;
    }

    // ---- maven -------------------------------------------------------------

    private static Result maven(Path root, Path executable) {
        Path out = null;
        try {
            out = Files.createTempFile("jcheck-maven-", ".cp");
            Files.deleteIfExists(out);
            List<String> command = new ArrayList<>(List.of(executable.toString(), "-q",
                "dependency:build-classpath",
                "-Dmdep.outputFile=" + out,
                "-Dmdep.includeScope=test"));
            String mavenRepo = System.getenv("JCHECK_MAVEN_REPO");
            if (mavenRepo != null && !mavenRepo.isBlank()) {
                command.add("-Dmaven.repo.local=" + mavenRepo);
            }
            ProcessBuilder pb = new ProcessBuilder(command).directory(root.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return failure("maven did not finish within 5 minutes");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(out)) {
                return failure("maven could not report its classpath: " + firstLine(output));
            }
            return new Result(splitClasspath(Files.readString(out, StandardCharsets.UTF_8)),
                List.of(), List.of(), List.of(), true);
        } catch (IOException e) {
            return failure("maven could not be started: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure("maven was interrupted");
        } finally {
            deleteQuietly(out);
        }
    }

    public static List<Path> splitClasspath(String text) {
        List<Path> out = new ArrayList<>();
        for (String part : text.split(java.io.File.pathSeparator)) {
            String p = part.strip();
            if (!p.isEmpty()) {
                out.add(Path.of(p));
            }
        }
        return out;
    }

    // ---- cache -------------------------------------------------------------

    private static Result failure(String note) {
        return new Result(List.of(), List.of(), List.of(), List.of(note), false);
    }

    /** A stable key for the current build files: path plus size and mtime. */
    public static String fingerprint(Path root) {
        List<String> entries = new ArrayList<>();
        String[] candidates = {
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "gradle.properties", "gradle.lockfile", "pom.xml", "gradle/libs.versions.toml",
        };
        for (String name : candidates) {
            Path p = root.resolve(name);
            if (Files.isRegularFile(p)) {
                try {
                    entries.add(name + ":" + Files.size(p) + ":" + Files.getLastModifiedTime(p).toMillis());
                } catch (IOException e) {
                    entries.add(name + ":?");
                }
            }
        }
        entries.add(root.toString());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(String.join("\n", entries).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(entries.hashCode());
        }
    }

    private static Path cacheFile(Path root, String tool) {
        return MavenResolver.defaultCacheDir().resolve("classpath")
            .resolve(tool + "-" + fingerprint(root) + ".txt");
    }

    private static Result readCache(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            List<Path> classpath = new ArrayList<>();
            List<Path> processors = new ArrayList<>();
            List<Path> sources = new ArrayList<>();
            List<Path> target = classpath;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if ("processor".equals(line)) {
                    target = processors;
                } else if ("sources".equals(line)) {
                    target = sources;
                } else if (!line.isBlank()) {
                    target.add(Path.of(line.strip()));
                }
            }
            if (classpath.isEmpty()) {
                return null;
            }
            return new Result(classpath, processors, sources, List.of(), true);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void writeCache(Path file, Result result) {
        StringBuilder sb = new StringBuilder();
        for (Path p : result.classpath) {
            sb.append(p).append('\n');
        }
        sb.append("processor\n");
        for (Path p : result.processorPath) {
            sb.append(p).append('\n');
        }
        sb.append("sources\n");
        for (Path p : result.sourceRoots) {
            sb.append(p).append('\n');
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // an unwritable cache is not fatal; the classpath is still returned in memory
        }
    }

    private static String firstLine(String text) {
        for (String line : text.split("\n", -1)) {
            if (!line.isBlank()) {
                return line.length() > 200 ? line.substring(0, 200) + "..." : line;
            }
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
}
