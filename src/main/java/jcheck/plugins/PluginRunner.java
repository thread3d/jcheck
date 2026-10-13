package jcheck.plugins;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeMap;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * The child-JVM half of a plugin run.
 *
 * <p>Error Prone and its relatives need {@code --add-exports}/{@code --add-opens} on the JVM that
 * runs javac. Those cannot be added to a running JVM, so jcheck forks this class with them and
 * this process does the compiling. It depends on nothing but the JDK and writes one JSON object
 * per diagnostic to a file, so the parent never has to parse a human message to recover a span.
 *
 * <p>Request is a {@link Properties} file: {@code option.N} keys in numeric order, {@code
 * classpath}/{@code processorpath} as path-separated lists, and {@code source.N} entries.
 */
public final class PluginRunner {

    private PluginRunner() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: PluginRunner <request.properties> <output.jsonl>");
            System.exit(2);
        }
        int exit;
        try {
            exit = run(Path.of(args[0]), Path.of(args[1]));
        } catch (Throwable t) {
            System.err.println("jcheck plugin runner failed: " + t);
            t.printStackTrace(System.err);
            exit = 3;
        }
        System.exit(exit);
    }

    private static int run(Path request, Path output) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(request)) {
            props.load(in);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            System.err.println("jcheck plugin runner: no system Java compiler");
            return 3;
        }
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        StandardJavaFileManager fm = compiler.getStandardFileManager(collector, null, StandardCharsets.UTF_8);
        boolean ok;
        try {
            String classpath = props.getProperty("classpath", "");
            if (!classpath.isEmpty()) {
                fm.setLocationFromPaths(StandardLocation.CLASS_PATH, paths(classpath));
            }
            String processorPath = props.getProperty("processorpath", "");
            if (!processorPath.isEmpty()) {
                fm.setLocationFromPaths(StandardLocation.ANNOTATION_PROCESSOR_PATH, paths(processorPath));
            }
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(sourcePaths(props));
            try {
                ok = compiler.getTask(null, fm, collector, options(props), null, units).call();
            } catch (RuntimeException e) {
                System.err.println("jcheck plugin runner: compilation threw: " + e);
                e.printStackTrace(System.err);
                ok = false;
            }
        } finally {
            try {
                fm.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
        writeDiagnostics(output, collector.getDiagnostics());
        return ok ? 0 : 1;
    }

    private static List<String> options(Properties props) {
        TreeMap<Integer, String> indexed = new TreeMap<>();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("option.")) {
                try {
                    indexed.put(Integer.parseInt(key.substring("option.".length())), props.getProperty(key));
                } catch (NumberFormatException ignored) {
                    // a malformed option index is skipped rather than aborting the run
                }
            }
        }
        return new ArrayList<>(indexed.values());
    }

    private static List<Path> sourcePaths(Properties props) {
        TreeMap<Integer, Path> indexed = new TreeMap<>();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("source.")) {
                try {
                    indexed.put(Integer.parseInt(key.substring("source.".length())),
                        Path.of(props.getProperty(key)));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        return new ArrayList<>(indexed.values());
    }

    private static List<Path> paths(String joined) {
        List<Path> out = new ArrayList<>();
        for (String part : joined.split(java.io.File.pathSeparator)) {
            if (!part.isEmpty()) {
                out.add(Path.of(part));
            }
        }
        return out;
    }

    private static void writeDiagnostics(Path output, List<Diagnostic<? extends JavaFileObject>> diagnostics)
            throws IOException {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        StringBuilder sb = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
            sb.append('{');
            field(sb, "kind", d.getKind().name());
            sb.append(',');
            field(sb, "code", String.valueOf(d.getCode()));
            sb.append(',');
            field(sb, "source", d.getSource() == null ? "" : d.getSource().getName());
            sb.append(',');
            number(sb, "line", d.getLineNumber());
            sb.append(',');
            number(sb, "column", d.getColumnNumber());
            sb.append(',');
            number(sb, "start", d.getStartPosition());
            sb.append(',');
            number(sb, "end", d.getEndPosition());
            sb.append(',');
            field(sb, "message", d.getMessage(null));
            sb.append('}').append('\n');
        }
        Files.writeString(output, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void field(StringBuilder sb, String name, String value) {
        sb.append('"').append(name).append("\":\"").append(escape(value)).append('"');
    }

    private static void number(StringBuilder sb, String name, long value) {
        sb.append('"').append(name).append("\":").append(value);
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
