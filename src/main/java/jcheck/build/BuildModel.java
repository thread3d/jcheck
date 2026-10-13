package jcheck.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import jcheck.config.JcheckConfig;

/**
 * Resolves what to compile and against what.
 *
 * <p>Gradle and Maven are detected so the tool can say which build it is talking to, and
 * {@code jcheck build}/{@code jcheck test} delegate to them. The full Tooling API dependency
 * graph is not driven yet, so the classpath comes from explicit configuration, {@code lib/}
 * and known build output directories. Every such limitation is reported in {@link #notes}
 * rather than silently producing a wrong result.
 */
public final class BuildModel {

    private static final Set<String> SKIP_DIRS = Set.of(
        "build", "target", "out", ".git", ".gradle", ".idea", "node_modules",
        "bin", "dist", "generated", ".jcheck");

    private static final List<String> SOURCE_ROOT_CANDIDATES = List.of(
        "src/main/java", "src/test/java", "src/main/kotlin");

    private static final List<String> CLASSPATH_CANDIDATES = List.of(
        "build/classes/java/main", "build/classes/java/test", "build/resources/main",
        "target/classes", "target/test-classes", "out/classes", "out/test-classes");

    public final Path root;
    public final String buildTool;
    public final List<Path> sources;
    public final List<Path> classpath;
    public final List<Path> processorPath;
    public final Integer release;
    /** True when the dependency classpath came from the build tool or an explicit override. */
    public final boolean classpathResolved;
    /** Limitations the user must see: unresolved dependency graphs, missing source roots. */
    public final List<String> notes;
    /** Purely informational resolution details; never rendered. */
    public final List<String> info;

    private BuildModel(Path root, String buildTool, List<Path> sources, List<Path> classpath,
                       List<Path> processorPath, Integer release, boolean classpathResolved,
                       List<String> notes, List<String> info) {
        this.root = root;
        this.buildTool = buildTool;
        this.sources = List.copyOf(sources);
        this.classpath = List.copyOf(classpath);
        this.processorPath = List.copyOf(processorPath);
        this.release = release;
        this.classpathResolved = classpathResolved;
        this.notes = List.copyOf(notes);
        this.info = List.copyOf(info);
    }

    public static BuildModel resolve(Path root, JcheckConfig config,
                                     List<Path> sourceOverrides, List<Path> classpathOverrides,
                                     List<Path> processorOverrides, Integer releaseOverride) {
        List<String> notes = new ArrayList<>();
        List<String> info = new ArrayList<>();
        sourceOverrides = sourceOverrides == null ? List.of() : sourceOverrides;
        classpathOverrides = classpathOverrides == null ? List.of() : classpathOverrides;
        processorOverrides = processorOverrides == null ? List.of() : processorOverrides;
        Path absRoot = root.toAbsolutePath().normalize();
        String tool = detectBuildTool(absRoot);

        List<Path> sources;
        if (!sourceOverrides.isEmpty()) {
            sources = expand(absRoot, sourceOverrides);
            info.add("source list came from --sources");
        } else if (!config.sourceRoots.isEmpty()) {
            List<Path> roots = new ArrayList<>();
            for (String s : config.sourceRoots) {
                roots.add(absRoot.resolve(s));
            }
            sources = expand(absRoot, roots);
            info.add("source roots came from [project].sources");
        } else {
            List<Path> candidates = new ArrayList<>();
            for (String s : SOURCE_ROOT_CANDIDATES) {
                Path p = absRoot.resolve(s);
                if (Files.isDirectory(p)) {
                    candidates.add(p);
                }
            }
            if (candidates.isEmpty()) {
                candidates.add(absRoot);
                notes.add("no src/main/java found; scanning the project root for .java files");
            } else {
                info.add("auto-detected source roots: " + relativeAll(absRoot, candidates));
            }
            sources = expand(absRoot, candidates);
        }

        Set<Path> classpath = new LinkedHashSet<>();
        for (Path p : classpathOverrides) {
            classpath.add(absRoot.resolve(p).normalize());
        }
        if (!classpathOverrides.isEmpty()) {
            info.add("classpath extended from --classpath");
        }
        Path lib = absRoot.resolve("lib");
        if (Files.isDirectory(lib)) {
            try (Stream<Path> s = Files.list(lib)) {
                s.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(classpath::add);
            } catch (IOException ignored) {
                // an unreadable lib/ is not fatal
            }
        }
        for (String c : CLASSPATH_CANDIDATES) {
            Path p = absRoot.resolve(c);
            if (Files.isDirectory(p)) {
                classpath.add(p);
            }
        }
        List<Path> processorPath = new ArrayList<>();
        for (Path p : processorOverrides) {
            processorPath.add(absRoot.resolve(p).normalize());
        }

        /*
         * When the project has a build tool, ask it for the real dependency classpath instead of
         * guessing. A failure here is stated as a note and the heuristic classpath above is kept.
         */
        boolean classpathResolved = !classpathOverrides.isEmpty();
        if (("gradle".equals(tool) || "maven".equals(tool)) && classpathOverrides.isEmpty()
            && config.resolveClasspath) {
            DependencyResolver.Result deps = DependencyResolver.resolve(absRoot, tool,
                findBuildExecutable(absRoot, tool));
            classpath.addAll(deps.classpath);
            for (Path p : deps.processorPath) {
                if (!processorPath.contains(p)) {
                    processorPath.add(p);
                }
            }
            notes.addAll(deps.notes);
            classpathResolved = deps.resolved;
            if (deps.resolved) {
                info.add("classpath resolved from " + tool);
            }
        } else if (("gradle".equals(tool) || "maven".equals(tool)) && classpathOverrides.isEmpty()) {
            notes.add("build-tool classpath resolution is disabled in jcheck.toml; "
                + "third-party types may appear unresolved");
        }

        Integer release = releaseOverride != null ? releaseOverride : config.release;
        if (config.jdk != null && release != null && config.jdk.intValue() != release.intValue()) {
            notes.add("[project].jdk=" + config.jdk + " but release=" + release + "; using release");
        }

        return new BuildModel(absRoot, tool, sources, new ArrayList<>(classpath), processorPath,
            release, classpathResolved, notes, info);
    }

    public static String detectBuildTool(Path root) {
        if (Files.isRegularFile(root.resolve("build.gradle"))
            || Files.isRegularFile(root.resolve("build.gradle.kts"))
            || Files.isRegularFile(root.resolve("settings.gradle"))
            || Files.isRegularFile(root.resolve("settings.gradle.kts"))) {
            return "gradle";
        }
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            return "maven";
        }
        return "flat";
    }

    /** Locates the build tool executable, or null when it is not installed. */
    public static Path findBuildExecutable(String buildTool) {
        return findBuildExecutable(Path.of("."), buildTool);
    }

    /**
     * Locates the build tool, preferring the project's wrapper ({@code gradlew}/{@code mvnw}) so the
     * version the project pinned is the version that resolves its graph.
     */
    public static Path findBuildExecutable(Path root, String buildTool) {
        String name;
        String wrapper;
        switch (buildTool) {
            case "gradle":
                name = "gradle";
                wrapper = "gradlew";
                break;
            case "maven":
                name = "mvn";
                wrapper = "mvnw";
                break;
            default:
                return null;
        }
        Path wrapped = root.resolve(wrapper);
        if (Files.isExecutable(wrapped)) {
            return wrapped;
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(dir).resolve(name);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<Path> expand(Path root, List<Path> candidates) {
        List<Path> out = new ArrayList<>();
        for (Path c : candidates) {
            Path p = c.isAbsolute() ? c.normalize() : root.resolve(c).normalize();
            if (Files.isDirectory(p)) {
                out.addAll(javaFiles(p));
            } else if (Files.isRegularFile(p) && p.toString().endsWith(".java")) {
                out.add(p);
            }
        }
        return out;
    }

    private static List<Path> javaFiles(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            List<Path> out = new ArrayList<>();
            stream.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !hasSkippedSegment(dir, p))
                .sorted()
                .forEach(out::add);
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean hasSkippedSegment(Path base, Path file) {
        Path rel;
        try {
            rel = base.relativize(file);
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (Path part : rel) {
            if (SKIP_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static String relativeAll(Path root, List<Path> paths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(root.relativize(paths.get(i)));
        }
        return sb.toString();
    }

    public boolean isEmpty() {
        return sources.isEmpty();
    }
}
