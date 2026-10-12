package jcheck.testharness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import jcheck.model.Diagnostic;
import jcheck.model.Level;
import jcheck.model.Span;
import jcheck.model.Tier;

/**
 * Turns JUnit XML reports into the same diagnostic model the static checks use, so a failing
 * test reads like any other jcheck finding.
 *
 * <p>Project frames are lifted above framework frames, and the failing assertion line is used as
 * the primary span when the report or the stack trace points at one.
 */
public final class JUnitXmlParser {

    public static final String CODE = "J0901";
    private static final Pattern FRAME =
        Pattern.compile("\\(([\\w$]+\\.java):(\\d+)\\)");
    private static final Pattern PATH_FRAME =
        Pattern.compile("(?:\\(|at\\s+)([^\\s(]+\\.java):(\\d+)\\)?");

    private JUnitXmlParser() {
    }

    public static List<Diagnostic> parseDirectory(Path reportDir, Path root) {
        List<Diagnostic> out = new ArrayList<>();
        if (reportDir == null || !Files.isDirectory(reportDir)) {
            return out;
        }
        try (Stream<Path> files = Files.list(reportDir)) {
            List<Path> xmls = new ArrayList<>();
            files.filter(p -> p.toString().endsWith(".xml")).sorted().forEach(xmls::add);
            int seq = 0;
            for (Path xml : xmls) {
                out.addAll(parseFile(xml, root, seq));
                seq = out.size();
            }
        } catch (IOException ignored) {
            // an unreadable report directory yields no diagnostics
        }
        return out;
    }

    public static List<Diagnostic> parseFile(Path xml, Path root, int seqBase) {
        List<Diagnostic> out = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Element rootEl = builder.parse(xml.toFile()).getDocumentElement();
            NodeList cases = rootEl.getElementsByTagName("testcase");
            int seq = seqBase;
            for (int i = 0; i < cases.getLength(); i++) {
                Element tc = (Element) cases.item(i);
                Element failure = firstChild(tc, "failure");
                Element error = firstChild(tc, "error");
                Element kind = failure != null ? failure : error;
                if (kind == null) {
                    continue;
                }
                out.add(toDiagnostic(tc, kind, failure != null ? "failure" : "error", root, seq++));
            }
        } catch (Exception ignored) {
            // a malformed report must not fail the whole run
        }
        return out;
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element && tag.equals(((Element) n).getTagName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static Diagnostic toDiagnostic(Element testcase, Element problem, String kind,
                                           Path root, int seq) {
        String className = attr(testcase, "classname", "unknown");
        String testName = attr(testcase, "name", "unknown");
        String message = attr(problem, "message", "");
        String stack = problem.getTextContent() == null ? "" : problem.getTextContent();

        String summary = message.isEmpty() ? firstMeaningfulLine(stack) : firstLine(message);
        List<String> frames = orderFrames(stack, root);
        Span primary = spanFrom(testcase, problem, frames, className, root);

        Diagnostic.Builder b = Diagnostic.builder(CODE)
            .source("junit")
            .sourceKey("junit." + kind)
            .level(Level.ERROR)
            .tier(Tier.T5)
            .message(className + "." + testName + " " + kind + ": " + summary)
            .seq(seq);
        if (primary != null) {
            b.primary(primary);
            b.label(primary, "failing assertion");
        }
        for (String frame : frames) {
            b.note(frame);
        }
        b.help("run `jcheck test` locally to see the full output");
        return b.build();
    }

    private static Span spanFrom(Element testcase, Element problem, List<String> frames,
                                 String className, Path root) {
        String file = attr(problem, "file", "");
        int line = parseInt(attr(problem, "line", "0"));
        if (!file.isEmpty() && line > 0) {
            return new Span(file, line, 1, line, 1);
        }
        for (String frame : frames) {
            Matcher m = FRAME.matcher(frame);
            if (m.find()) {
                String simple = m.group(1);
                int ln = parseInt(m.group(2));
                Path resolved = resolveSource(root, className, simple);
                if (resolved != null && ln > 0) {
                    return new Span(relativize(root, resolved), ln, 1, ln, 1);
                }
            }
        }
        return null;
    }

    private static Path resolveSource(Path root, String className, String simpleFileName) {
        String[] candidates = {
            "src/test/java", "src/main/java", "src/test/kotlin", "src/main/kotlin",
        };
        String packagePath = className.contains(".")
            ? className.substring(0, className.lastIndexOf('.')).replace('.', '/')
            : "";
        for (String base : candidates) {
            Path dir = root.resolve(base);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            Path direct = packagePath.isEmpty()
                ? dir.resolve(simpleFileName)
                : dir.resolve(packagePath).resolve(simpleFileName);
            if (Files.isRegularFile(direct)) {
                return direct;
            }
        }
        return null;
    }

    /** Project frames first, framework frames after, each group in original order. */
    public static List<String> orderFrames(String stack, Path root) {
        List<String> project = new ArrayList<>();
        List<String> other = new ArrayList<>();
        for (String raw : stack.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || !line.contains("at ")) {
                continue;
            }
            if (mentionsProject(line, root)) {
                project.add(line);
            } else {
                other.add(line);
            }
        }
        project.addAll(other);
        return project;
    }

    private static final String[] FRAMEWORK_MARKERS = {
        "org.junit", "junit.framework", "java.base", "jdk.internal", "java.lang.reflect",
        "sun.reflect", "org.gradle", "org.apache.maven", "worker.org.gradle",
    };

    /**
     * True when a stack frame looks like the user's own code rather than a framework frame.
     *
     * <p>Gradle and Maven print a bare file name ({@code CartTest.java:12}) with no path, so
     * requiring the project root or {@code /src/} in the text would classify every project frame
     * as third-party. Anything that is not a known framework and does point at a source file
     * counts as project code.
     */
    private static boolean mentionsProject(String frame, Path root) {
        for (String marker : FRAMEWORK_MARKERS) {
            if (frame.contains(marker)) {
                return false;
            }
        }
        if (frame.contains(root.toString()) || frame.contains("/src/") || frame.contains("\\src\\")) {
            return true;
        }
        return PATH_FRAME.matcher(frame).find();
    }

    private static String relativize(Path root, Path file) {
        try {
            return root.relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    private static String attr(Element el, String name, String def) {
        String v = el.getAttribute(name);
        return v == null || v.isEmpty() ? def : v;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        String first = nl < 0 ? s : s.substring(0, nl);
        return first.strip();
    }

    private static String firstMeaningfulLine(String stack) {
        for (String line : stack.split("\n")) {
            String t = line.strip();
            if (!t.isEmpty()) {
                return firstLine(t);
            }
        }
        return "(no message)";
    }
}
