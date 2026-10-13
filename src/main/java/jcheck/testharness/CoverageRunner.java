package jcheck.testharness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * JaCoCo coverage of the lines a test run actually executed.
 *
 * <p>The agent runs inside the test JVM (added by {@link JUnitHarness}); this class extracts the
 * agent from its artifact and, after the run, asks JaCoCo's CLI for an XML report over the
 * production classes. Lines that no test executed, and lines only partly executed, become
 * diagnostics, so "the tests pass" and "the code is tested" stop being the same sentence.
 */
public final class CoverageRunner {

    /** Coverage lines for one run. */
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

    private static final List<String> MAIN_CLASS_DIRS = List.of(
        "build/classes/java/main", "build/classes/kotlin/main", "target/classes", "out/classes");

    private CoverageRunner() {
    }

    /** The runnable agent jar, extracted from the JaCoCo agent artifact; null when unavailable. */
    public static Path agent(Path root, JcheckConfig config, List<String> notes) {
        try {
            PluginClasspath.Result cp = PluginClasspath.resolveCoordinates(
                List.of("org.jacoco:org.jacoco.agent:" + config.coverageVersion),
                config.pluginClasspath, root, config.pluginOffline, config.pluginRepo);
            Path artifact = cp.processorPath.isEmpty() ? null : cp.processorPath.get(0);
            if (artifact == null) {
                notes.add("the JaCoCo agent could not be resolved; coverage did not run");
                return null;
            }
            Path target = MavenResolver.defaultCacheDir().resolve("jacoco")
                .resolve("jacocoagent-" + config.coverageVersion + ".jar");
            if (Files.isRegularFile(target)) {
                return target;
            }
            Files.createDirectories(target.getParent());
            try (ZipFile zip = new ZipFile(artifact.toFile())) {
                var entry = zip.getEntry("jacocoagent.jar");
                if (entry == null) {
                    notes.add("the JaCoCo agent artifact contains no jacocoagent.jar");
                    return null;
                }
                try (var in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return target;
        } catch (IOException | RuntimeException e) {
            notes.add("the JaCoCo agent is unavailable: " + e.getMessage());
            return null;
        }
    }

    /** Produces coverage diagnostics from an execution-data file. */
    public static Result report(Path root, JcheckConfig config, Path exec, List<Path> sources,
                                Catalog catalog) {
        List<String> notes = new ArrayList<>();
        if (exec == null || !Files.isRegularFile(exec)) {
            notes.add("no JaCoCo execution data was produced; coverage did not run");
            return new Result(List.of(), notes, false);
        }
        Path cli;
        try {
            PluginClasspath.Result cp = PluginClasspath.resolveCoordinates(
                List.of("org.jacoco:org.jacoco.cli:" + config.coverageVersion + ":nodeps"),
                config.pluginClasspath, root, config.pluginOffline, config.pluginRepo);
            cli = cp.processorPath.isEmpty() ? null : cp.processorPath.get(0);
        } catch (RuntimeException e) {
            notes.add("the JaCoCo CLI could not be resolved: " + e.getMessage());
            return new Result(List.of(), notes, false);
        }
        if (cli == null) {
            notes.add("the JaCoCo CLI could not be resolved; coverage did not run");
            return new Result(List.of(), notes, false);
        }
        List<Path> mainClasses = existing(root, MAIN_CLASS_DIRS);
        if (mainClasses.isEmpty()) {
            notes.add("JaCoCo needs compiled main classes; none were found");
            return new Result(List.of(), notes, false);
        }
        List<Path> roots = MutationRunner.sourceRoots(root, sources);
        if (roots.isEmpty()) {
            notes.add("cannot determine source roots for JaCoCo");
            return new Result(List.of(), notes, false);
        }

        Path xml;
        Path log;
        try {
            xml = Files.createTempFile("jcheck-jacoco-", ".xml");
            log = Files.createTempFile("jcheck-jacoco-", ".log");
        } catch (IOException e) {
            notes.add("cannot create a JaCoCo report path: " + e.getMessage());
            return new Result(List.of(), notes, false);
        }
        try {
            List<String> command = new ArrayList<>();
            command.add(javaBinary());
            command.add("-jar");
            command.add(cli.toString());
            command.add("report");
            command.add(exec.toString());
            command.add("--classfiles");
            command.add(join(mainClasses));
            command.add("--sourcefiles");
            command.add(join(roots));
            command.add("--xml");
            command.add(xml.toString());
            ProcessBuilder pb = new ProcessBuilder(command).directory(root.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(log.toFile());
            Process process = pb.start();
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                notes.add("the JaCoCo report did not finish within 5 minutes");
                return new Result(List.of(), notes, false);
            }
            if (!Files.isRegularFile(xml) || Files.size(xml) == 0) {
                notes.add("the JaCoCo report was not produced (exit " + process.exitValue() + "): "
                    + firstLine(log));
                return new Result(List.of(), notes, false);
            }
            return parse(xml, sources, catalog, notes);
        } catch (IOException e) {
            notes.add("JaCoCo could not be started: " + e.getMessage());
            return new Result(List.of(), notes, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notes.add("JaCoCo was interrupted");
            return new Result(List.of(), notes, false);
        } finally {
            deleteQuietly(xml);
            deleteQuietly(log);
        }
    }

    private static Result parse(Path xml, List<Path> sources, Catalog catalog, List<String> notes) {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            // JaCoCo emits a DOCTYPE, so it cannot be disallowed; external entities stay off.
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setExpandEntityReferences(false);
            f.setXIncludeAware(false);
            doc = f.newDocumentBuilder().parse(xml.toFile());
        } catch (Exception e) {
            notes.add("cannot read the JaCoCo report: " + e.getMessage());
            return new Result(List.of(), notes, false);
        }

        SpanResolver spans = new SpanResolver();
        List<Diagnostic> diagnostics = new ArrayList<>();
        int seq = 6_000_000;
        int uncovered = 0;
        int partial = 0;
        NodeList packages = doc.getElementsByTagName("package");
        for (int p = 0; p < packages.getLength(); p++) {
            Element pkg = (Element) packages.item(p);
            String packageName = pkg.getAttribute("name");
            for (Element sourceFile : children(pkg, "sourcefile")) {
                String name = sourceFile.getAttribute("name");
                String relative = packageName.isEmpty() ? name : packageName.replace('.', '/') + "/" + name;
                Path file = resolveSource(sources, relative, name);
                if (file == null) {
                    continue;
                }
                for (Element line : children(sourceFile, "line")) {
                    int missed = intAttr(line, "mi", 0);
                    int covered = intAttr(line, "ci", 0);
                    if (missed == 0) {
                        continue;
                    }
                    int nr = intAttr(line, "nr", -1);
                    if (nr <= 0) {
                        continue;
                    }
                    boolean fullyUncovered = covered == 0;
                    if (fullyUncovered) {
                        uncovered++;
                    } else {
                        partial++;
                    }
                    Span span = spanForLine(spans, file, nr);
                    CatalogEntry entry = catalog.lookup("jcheck", "UncoveredLine");
                    String code = entry != null ? entry.code() : catalog.codeFor("jcheck", "UncoveredLine");
                    Diagnostic.Builder b = Diagnostic.builder(code)
                        .source("jcheck")
                        .sourceKey("UncoveredLine")
                        .level(Level.WARNING)
                        .tier(Tier.T5)
                        .message(fullyUncovered
                            ? "line is not covered by any test"
                            : "line is only partially covered")
                        .primary(span)
                        .seq(seq++);
                    if (entry != null) {
                        for (String h : entry.help) {
                            b.help(h);
                        }
                    }
                    diagnostics.add(b.build());
                }
            }
        }
        notes.add("JaCoCo: " + uncovered + " uncovered and " + partial + " partially covered line(s)");
        return new Result(diagnostics, notes, true);
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element && name.equals(((Element) n).getTagName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static Path resolveSource(List<Path> sources, String relative, String name) {
        for (Path s : sources) {
            if (s.toString().endsWith(relative)) {
                return s;
            }
        }
        for (Path s : sources) {
            if (s.getFileName().toString().equals(name)) {
                return s;
            }
        }
        return null;
    }

    private static Span spanForLine(SpanResolver spans, Path file, int line) {
        String key = file.toString();
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
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        for (String c : candidates) {
            Path p = root.resolve(c);
            if (Files.isDirectory(p)) {
                out.add(p);
            }
        }
        return new ArrayList<>(out);
    }

    private static int intAttr(Element e, String name, int fallback) {
        try {
            String v = e.getAttribute(name);
            return v.isEmpty() ? fallback : Integer.parseInt(v);
        } catch (NumberFormatException ex) {
            return fallback;
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
}
