package jcheck.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *
 * <p>Multi-module layouts are found by walking for {@code src/main/java} roots under a module
 * directory, so a Gradle or Maven build with {@code module-a/} and {@code module-b/} is analysed as
 * one project without configuring every module by hand.
 */
public final class BuildModel {

    /*
     * A source tree can legitimately contain a package named "build" (commons-io has
     * org.apache.commons.io.build), so build-output names are skipped only when they are the top
     * level of a scanned root. VCS, IDE and tooling metadata is skipped wherever it appears.
     */
    private static final Set<String> OUTPUT_DIRS = Set.of(
        "build", "target", "out", "bin", "dist", "generated");
    private static final Set<String> VCS_DIRS = Set.of(
        ".git", ".gradle", ".idea", ".jcheck", "node_modules");

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
    /** Sub-project directories whose own source roots were folded into {@link #sources}. */
    public final List<Path> modules;
    /** Limitations the user must see: unresolved dependency graphs, missing source roots. */
    public final List<String> notes;
    /** Purely informational resolution details; never rendered. */
    public final List<String> info;

    private BuildModel(Path root, String buildTool, List<Path> sources, List<Path> classpath,
                       List<Path> processorPath, Integer release, boolean classpathResolved,
                       List<Path> modules, List<String> notes, List<String> info) {
        this.root = root;
        this.buildTool = buildTool;
        this.sources = List.copyOf(sources);
        this.classpath = List.copyOf(classpath);
        this.processorPath = List.copyOf(processorPath);
        this.release = release;
        this.classpathResolved = classpathResolved;
        this.modules = List.copyOf(modules);
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

        List<Path> modules = new ArrayList<>();
        List<Path> toolSourceRoots = new ArrayList<>();

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
            toolSourceRoots.addAll(deps.sourceRoots);
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

        /*
         * Sources are resolved after the classpath so a Gradle build can contribute its own source
         * sets; a --sources list or [project].sources still wins over everything.
         */
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
            if (!toolSourceRoots.isEmpty()) {
                candidates.addAll(toolSourceRoots);
                info.add("source roots came from the build tool: "
                    + relativeAll(absRoot, toolSourceRoots));
            }
            for (String s : SOURCE_ROOT_CANDIDATES) {
                Path p = absRoot.resolve(s);
                if (Files.isDirectory(p) && !candidates.contains(p)) {
                    candidates.add(p);
                }
            }
            List<Path> moduleRoots = moduleSourceRoots(absRoot);
            PomModel pom = pomModel(absRoot);
            List<Path> allModuleRoots = new ArrayList<>(moduleRoots);
            for (Path p : pom.sourceRoots) {
                if (!allModuleRoots.contains(p)) {
                    allModuleRoots.add(p);
                }
            }
            for (Path p : allModuleRoots) {
                if (!candidates.contains(p)) {
                    candidates.add(p);
                }
            }
            for (Path p : moduleRoots) {
                Path module = sourceRootModule(p);
                if (module != null && !modules.contains(module)) {
                    modules.add(module);
                }
            }
            for (Path m : pom.modules) {
                if (!modules.contains(m)) {
                    modules.add(m);
                }
            }
            if (!allModuleRoots.isEmpty()) {
                info.add("module source roots: " + relativeAll(absRoot, allModuleRoots));
                notes.add(modules.size() + " module(s) detected under "
                    + relativeAll(absRoot, modules));
            }
            if (candidates.isEmpty()) {
                candidates.add(absRoot);
                notes.add("no src/main/java found; scanning the project root for .java files");
            } else {
                info.add("auto-detected source roots: " + relativeAll(absRoot, candidates));
            }
            sources = expand(absRoot, candidates);
        }

        Integer release = releaseOverride != null ? releaseOverride : config.release;
        if (config.jdk != null && release != null && config.jdk.intValue() != release.intValue()) {
            notes.add("[project].jdk=" + config.jdk + " but release=" + release + "; using release");
        }

        return new BuildModel(absRoot, tool, sources, new ArrayList<>(classpath), processorPath,
            release, classpathResolved, modules, notes, info);
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
        Path normalRoot = root.normalize();
        for (Path c : candidates) {
            Path p = c.isAbsolute() ? c.normalize() : root.resolve(c).normalize();
            /*
             * Only the bare project-root fallback prunes build-output names: an explicit source root
             * may legitimately contain a package called "build" (commons-io's
             * org.apache.commons.io.build), and dropping it silently is a false negative.
             */
            boolean pruneOutput = p.equals(normalRoot);
            if (Files.isDirectory(p)) {
                out.addAll(javaFiles(p, pruneOutput));
            } else if (Files.isRegularFile(p) && p.toString().endsWith(".java")) {
                out.add(p);
            }
        }
        return out;
    }

    private static List<Path> javaFiles(Path dir, boolean pruneOutput) {
        try (Stream<Path> stream = Files.walk(dir)) {
            List<Path> out = new ArrayList<>();
            stream.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !hasSkippedSegment(dir, p, pruneOutput))
                .sorted()
                .forEach(out::add);
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean hasSkippedSegment(Path base, Path file, boolean pruneOutput) {
        Path rel;
        try {
            rel = base.relativize(file);
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (Path part : rel) {
            String name = part.toString();
            if (name.startsWith(".") || VCS_DIRS.contains(name)) {
                return true;
            }
            if (pruneOutput && OUTPUT_DIRS.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Source roots of sub-projects: any directory within five levels that has its own
     * {@code src/main/java} (or the other candidates). Build outputs, VCS metadata and hidden
     * directories are pruned so a large tree is not walked for nothing.
     */
    static List<Path> moduleSourceRoots(Path root) {
        List<Path> roots = new ArrayList<>();
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 5,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (dir.equals(root)) {
                            return FileVisitResult.CONTINUE;
                        }
                        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                        /*
                         * Here the walk sees the whole project, so a "build" directory at any depth
                         * is a build output (module-a/build/classes/...), unlike a package inside a
                         * declared source root.
                         */
                        if (name.startsWith(".") || VCS_DIRS.contains(name)
                            || OUTPUT_DIRS.contains(name)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        for (String candidate : SOURCE_ROOT_CANDIDATES) {
                            Path p = dir.resolve(candidate);
                            if (Files.isDirectory(p) && !roots.contains(p)) {
                                roots.add(p);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
        } catch (IOException ignored) {
            // an unreadable subtree simply contributes no module roots
        }
        return roots;
    }

    /** The module directory a walked {@code module/src/main/java} source root belongs to. */
    private static Path sourceRootModule(Path sourceRoot) {
        Path p = sourceRoot;
        for (int i = 0; i < 3 && p != null; i++) {
            p = p.getParent();
        }
        return p;
    }

    /** What a Maven {@code pom.xml} declares: the modules and their source directories. */
    static final class PomModel {
        final List<Path> sourceRoots = new ArrayList<>();
        final List<Path> modules = new ArrayList<>();
    }

    /**
     * Reads {@code pom.xml} for {@code <modules>} and each module's {@code <sourceDirectory>} /
     * {@code <testSourceDirectory>} (defaults {@code src/main/java} and {@code src/test/java}),
     * recursing into nested modules. This finds the layouts a directory walk cannot: a module with
     * {@code <sourceDirectory>src/java</sourceDirectory>}, or a module whose {@code pom.xml} declares
     * further modules.
     */
    static PomModel pomModel(Path root) {
        PomModel model = new PomModel();
        Path pom = root.resolve("pom.xml");
        if (Files.isRegularFile(pom)) {
            collectPom(root, pom, model, 0);
        }
        return model;
    }

    private static void collectPom(Path moduleDir, Path pom, PomModel model, int depth) {
        if (depth > 4) {
            return;
        }
        String xml;
        try {
            xml = Files.readString(pom, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        boolean isRoot = depth == 0;
        for (String tag : List.of("sourceDirectory", "testSourceDirectory")) {
            String fallback = "sourceDirectory".equals(tag) ? "src/main/java" : "src/test/java";
            String declared = tagValue(xml, tag, fallback);
            if (declared.contains("${")) {
                continue;
            }
            Path p = moduleDir.resolve(declared).normalize();
            if (Files.isDirectory(p) && !model.sourceRoots.contains(p)) {
                model.sourceRoots.add(p);
            }
        }
        if (!isRoot && !model.modules.contains(moduleDir)) {
            model.modules.add(moduleDir);
        }
        for (String module : tagValues(xml, "module")) {
            String name = module.trim();
            if (name.isEmpty() || name.contains("${")) {
                continue;
            }
            Path child = moduleDir.resolve(name).normalize();
            Path childPom = child.resolve("pom.xml");
            if (Files.isRegularFile(childPom)) {
                collectPom(child, childPom, model, depth + 1);
            } else if (Files.isDirectory(child.resolve("src/main/java"))) {
                if (!model.modules.contains(child)) {
                    model.modules.add(child);
                }
                Path p = child.resolve("src/main/java");
                if (!model.sourceRoots.contains(p)) {
                    model.sourceRoots.add(p);
                }
            }
        }
    }

    private static String tagValue(String xml, String tag, String fallback) {
        Matcher m = Pattern.compile("<" + tag + ">\\s*([^<]+?)\\s*</" + tag + ">").matcher(xml);
        return m.find() ? m.group(1) : fallback;
    }

    private static List<String> tagValues(String xml, String tag) {
        List<String> values = new ArrayList<>();
        Matcher m = Pattern.compile("<" + tag + ">\\s*([^<]+?)\\s*</" + tag + ">").matcher(xml);
        while (m.find()) {
            values.add(m.group(1));
        }
        return values;
    }

    private static String relativeAll(Path root, List<Path> paths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Path p = paths.get(i);
            /*
             * A build tool can report a canonical path (/private/tmp) where the root was given
             * through a symlink (/tmp); relativizing then yields a "../.." chain, so fall back to
             * the absolute path rather than printing a misleading relative one.
             */
            sb.append(p.startsWith(root) ? root.relativize(p) : p);
        }
        return sb.toString();
    }

    public boolean isEmpty() {
        return sources.isEmpty();
    }
}
