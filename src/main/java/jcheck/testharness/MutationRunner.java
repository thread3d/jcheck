package jcheck.testharness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import jcheck.build.BuildModel;
import jcheck.catalog.Catalog;
import jcheck.catalog.CatalogEntry;
import jcheck.config.JcheckConfig;
import jcheck.engine.SpanResolver;
import jcheck.model.Applicability;
import jcheck.model.Diagnostic;
import jcheck.model.Level;
import jcheck.model.Span;
import jcheck.model.Tier;
import jcheck.plugins.MavenResolver;
import jcheck.plugins.PluginClasspath;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Runs PIT mutation testing and renders surviving mutants as diagnostics.
 *
 * <p>PIT is the one check that measures the tests rather than the code: a mutant that survives means
 * a test asserted something that does not depend on the logic it claims to cover. The run is
 * scoped to the sources the caller named, so it can be aimed at changed files.
 *
 * <p>The project's JUnit Platform is placed ahead of PIT's in the classpath on purpose: PIT's JUnit
 * 5 plugin pulls its own {@code junit-platform-*} version, and letting an older one win produces a
 * {@code NoSuchMethodError} deep inside JUnit's discovery rather than anything PIT can explain.
 */
public final class MutationRunner {

    /** Outcome of one mutation run. */
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

    private static final Pattern PACKAGE_DECL =
        Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final List<String> TEST_CLASS_DIRS = List.of(
        "build/classes/java/test", "build/classes/kotlin/test", "target/test-classes",
        "out/test-classes");
    private static final List<String> MAIN_CLASS_DIRS = List.of(
        "build/classes/java/main", "build/classes/kotlin/main", "target/classes",
        "out/classes");

    private MutationRunner() {
    }

    public static Result run(Path root, JcheckConfig config, List<Path> scopedSources, Catalog catalog) {
        List<String> notes = new ArrayList<>();

        List<Path> pitJars;
        List<Path> launcher;
        try {
            PluginClasspath.Result pit = PluginClasspath.resolveCoordinates(
                List.of("org.pitest:pitest-command-line:" + config.mutationVersion,
                    "org.pitest:pitest-junit5-plugin:" + config.mutationPluginVersion),
                config.pluginClasspath, root,
                config.pluginOffline, config.pluginRepo);
            pitJars = withoutTestFramework(pit.processorPath);
            notes.addAll(pit.notes);
            launcher = JUnitHarness.resolveLauncher(root, config, notes);
        } catch (RuntimeException e) {
            notes.add("PIT is configured but its jars could not be resolved: " + e.getMessage());
            return new Result(List.of(), notes, false);
        }
        if (pitJars.isEmpty()) {
            notes.add("PIT is configured but no jars were resolved; mutation testing did not run");
            return new Result(List.of(), notes, false);
        }
        if (launcher.isEmpty()) {
            notes.add("JUnit Platform could not be resolved for PIT; mutation testing did not run");
            return new Result(List.of(), notes, false);
        }

        BuildModel model = BuildModel.resolve(root, config, null, null, null, null);
        notes.addAll(model.notes);
        List<Path> mainClasses = existing(root, MAIN_CLASS_DIRS);
        List<Path> testClasses = existing(root, TEST_CLASS_DIRS);
        if (mainClasses.isEmpty() || testClasses.isEmpty()) {
            notes.add("PIT needs compiled main and test classes; run the build tool's test-compile "
                + "first (looked for " + String.join(", ", MAIN_CLASS_DIRS) + " and "
                + String.join(", ", TEST_CLASS_DIRS) + ")");
            return new Result(List.of(), notes, false);
        }

        List<Path> sources = scopedSources == null || scopedSources.isEmpty()
            ? mainSources(model.sources) : scopedSources;
        List<Path> sourceRoots = sourceRoots(root, sources);
        if (sourceRoots.isEmpty()) {
            notes.add("cannot determine source roots for PIT");
            return new Result(List.of(), notes, false);
        }

        Path reportDir;
        try {
            reportDir = Files.createTempDirectory("jcheck-pit-");
        } catch (IOException e) {
            notes.add("cannot create a report directory for PIT: " + e.getMessage());
            return new Result(List.of(), notes, false);
        }
        Path log = null;

        try {
            List<Path> testPath = new ArrayList<>(launcher);
            testPath.addAll(model.classpath);
            testPath.addAll(mainClasses);
            testPath.addAll(testClasses);
            String classPath = join(dedupe(testPath));

            List<String> command = new ArrayList<>();
            command.add(javaBinary());
            command.add("-cp");
            command.add(join(dedupe(concat(testPath, pitJars))));
            command.add("org.pitest.mutationtest.commandline.MutationCoverageReport");
            command.add("--reportDir");
            command.add(reportDir.toString());
            command.add("--sourceDirs");
            command.add(join(sourceRoots));
            command.add("--classPath");
            command.add(classPath);
            String targets = targetClasses(sources);
            if (!targets.isEmpty()) {
                command.add("--targetClasses");
                command.add(targets);
            }
            command.add("--outputFormats");
            command.add("XML");
            command.add("--timestampedReports");
            command.add("false");
            command.add("--failWhenNoMutations");
            command.add("false");
            command.add("--threads");
            command.add(String.valueOf(config.mutationThreads > 0
                ? config.mutationThreads : Math.max(1, Runtime.getRuntime().availableProcessors() / 2)));

            log = Files.createTempFile("jcheck-pit-", ".log");
            ProcessBuilder pb = new ProcessBuilder(command).directory(root.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(log.toFile());
            Process process = pb.start();
            if (!process.waitFor(30, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                notes.add("PIT did not finish within 30 minutes; mutation testing did not run");
                return new Result(List.of(), notes, false);
            }
            Path mutations = reportDir.resolve("mutations.xml");
            if (!Files.isRegularFile(mutations)) {
                notes.add("PIT produced no report (exit " + process.exitValue() + "): " + firstLine(log));
                return new Result(List.of(), notes, false);
            }
            return parse(mutations, sources, catalog, notes);
        } catch (IOException e) {
            notes.add("PIT could not be started: " + e.getMessage());
            return new Result(List.of(), notes, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notes.add("PIT was interrupted");
            return new Result(List.of(), notes, false);
        } finally {
            deleteQuietly(log);
            deleteRecursively(reportDir);
        }
    }

    private static Result parse(Path mutations, List<Path> sources, Catalog catalog, List<String> notes) {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            doc = f.newDocumentBuilder().parse(mutations.toFile());
        } catch (Exception e) {
            notes.add("cannot read the PIT report: " + e.getMessage());
            return new Result(List.of(), notes, false);
        }

        SpanResolver spans = new SpanResolver();
        List<Diagnostic> diagnostics = new ArrayList<>();
        int seq = 5_000_000;
        int total = 0;
        int killed = 0;
        NodeList nodes = doc.getElementsByTagName("mutation");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element mutation = (Element) nodes.item(i);
            String status = mutation.getAttribute("status");
            total++;
            if ("KILLED".equals(status)) {
                killed++;
                continue;
            }
            if (!"SURVIVED".equals(status) && !"NO_COVERAGE".equals(status)) {
                continue;
            }
            String mutatedClass = childText(mutation, "mutatedClass");
            String description = childText(mutation, "description");
            String sourceFile = childText(mutation, "sourceFile");
            int line = intText(mutation, "lineNumber", -1);
            Path file = resolveSource(sources, mutatedClass, sourceFile);
            Span span = file == null ? null : spanForLine(spans, file, line);

            CatalogEntry entry = catalog.lookup("jcheck", "MutantSurvived");
            String code = entry != null ? entry.code() : catalog.codeFor("jcheck", "MutantSurvived");
            String message = "NO_COVERAGE".equals(status)
                ? "no test covers this mutant: " + description
                : "mutant survived: " + description;

            Diagnostic.Builder b = Diagnostic.builder(code)
                .source("jcheck")
                .sourceKey("MutantSurvived")
                .level(Level.WARNING)
                .tier(Tier.T5)
                .message(message)
                .primary(span)
                .seq(seq++);
            if (entry != null) {
                for (String h : entry.help) {
                    b.help(h);
                }
            }
            if (!mutatedClass.isEmpty()) {
                b.note("in " + mutatedClass + "." + childText(mutation, "mutatedMethod")
                    + childText(mutation, "methodDescription"));
            }
            diagnostics.add(b.build());
        }
        notes.add("PIT: " + total + " mutant(s), " + killed + " killed, "
            + (total - killed) + " survived or uncovered");
        return new Result(diagnostics, notes, true);
    }

    /**
     * Production sources only. Building test code into the mutation targets would mutate the tests
     * themselves, and "removed call to assertEquals" is not a useful finding about the code.
     */
    static List<Path> mainSources(List<Path> sources) {
        List<Path> out = new ArrayList<>();
        for (Path p : sources) {
            String s = p.toString();
            if (s.contains("/src/test/") || s.contains("/src/testFixtures/")) {
                continue;
            }
            out.add(p);
        }
        return out;
    }

    static List<Path> withoutTestFramework(List<Path> jars) {
        List<Path> out = new ArrayList<>();
        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            if (name.startsWith("junit-platform-") || name.startsWith("junit-jupiter-")
                || name.startsWith("opentest4j-") || name.startsWith("apiguardian-")) {
                continue;
            }
            out.add(jar);
        }
        return out;
    }

    static List<Path> sourceRoots(Path root, List<Path> sources) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (Path source : sources) {
            Path dir = source.getParent();
            Path candidate = dir;
            Path found = null;
            while (candidate != null && !candidate.equals(root)) {
                Path name = candidate.getFileName();
                if (name != null && "java".equals(name.toString())) {
                    found = candidate;
                    break;
                }
                candidate = candidate.getParent();
            }
            roots.add(found != null ? found : dir);
        }
        return new ArrayList<>(roots);
    }

    static String targetClasses(List<Path> sources) {
        Set<String> patterns = new LinkedHashSet<>();
        for (Path source : sources) {
            String name = source.getFileName().toString();
            if (!name.endsWith(".java")) {
                continue;
            }
            String simple = name.substring(0, name.length() - ".java".length());
            String pkg = packageOf(source);
            patterns.add(pkg.isEmpty() ? simple : pkg + "." + simple);
        }
        return String.join(",", patterns);
    }

    private static String packageOf(Path source) {
        try {
            Matcher m = PACKAGE_DECL.matcher(Files.readString(source, StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : "";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static Path resolveSource(List<Path> sources, String mutatedClass, String sourceFile) {
        if (!mutatedClass.isEmpty()) {
            String rel = mutatedClass.replace('.', '/') + ".java";
            for (Path s : sources) {
                if (s.toString().endsWith(rel)) {
                    return s;
                }
            }
        }
        if (!sourceFile.isEmpty()) {
            for (Path s : sources) {
                if (s.getFileName().toString().equals(sourceFile)) {
                    return s;
                }
            }
        }
        return null;
    }

    private static Span spanForLine(SpanResolver spans, Path file, int line) {
        String key = file.toString();
        if (line <= 0) {
            return null;
        }
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

    private static List<Path> concat(List<Path> a, List<Path> b) {
        List<Path> out = new ArrayList<>(a);
        out.addAll(b);
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

    private static String childText(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element && name.equals(((Element) n).getTagName())) {
                return n.getTextContent().trim();
            }
        }
        return "";
    }

    private static int intText(Element parent, String name, int fallback) {
        try {
            String v = childText(parent, name);
            return v.isEmpty() ? fallback : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
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
