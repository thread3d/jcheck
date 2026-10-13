package jcheck.plugins;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import jcheck.util.Json;

/**
 * Runs javac in a forked JVM with the module exports analyzers such as Error Prone and the Checker
 * Framework need.
 *
 * <p>Those flags are JVM arguments, so a process that is already running cannot acquire them; the
 * fork is also what keeps an analyzer crash away from the daemon. The child
 * ({@link PluginRunner}) writes one JSON object per diagnostic, so the parent never has to parse a
 * human message to recover a span.
 */
public final class ForkedJavac {

    /** Raw outcome of one fork. */
    public static final class Result {
        public final List<Map<String, Object>> diagnostics;
        public final boolean ran;
        /** Why the run is not usable, or null when it is. */
        public final String failure;
        public final String stderr;

        Result(List<Map<String, Object>> diagnostics, boolean ran, String failure, String stderr) {
            this.diagnostics = List.copyOf(diagnostics);
            this.ran = ran;
            this.failure = failure;
            this.stderr = stderr;
        }
    }

    /** The exports Error Prone documents for JDK 16+, which the Checker Framework also needs. */
    private static final List<String> EXPORTS = List.of(
        "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED");

    private ForkedJavac() {
    }

    /**
     * Runs javac with the given options in a child JVM.
     *
     * @param javacOptions options passed through verbatim, in order
     * @param classpath    the checked code's classpath
     * @param processorPath jars javac loads plugins and processors from
     * @param sources      the source files to compile
     */
    public static Result run(List<String> javacOptions, List<Path> classpath, List<Path> processorPath,
                             List<Path> sources) {
        Path work;
        try {
            work = Files.createTempDirectory("jcheck-fork-");
        } catch (IOException e) {
            return failure("cannot create a scratch directory: " + e.getMessage());
        }
        try {
            Path request = work.resolve("request.properties");
            Path output = work.resolve("diagnostics.jsonl");
            Path stdout = work.resolve("stdout.txt");
            Path stderr = work.resolve("stderr.txt");
            Files.writeString(request, requestProperties(javacOptions, classpath, processorPath, sources),
                StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(command(request, output));
            pb.redirectOutput(stdout.toFile());
            pb.redirectError(stderr.toFile());
            Process process = pb.start();
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return failure("the analyzer did not finish within 5 minutes");
            }
            String errorText = readText(stderr);
            if (!Files.isRegularFile(output)) {
                return failure("the analyzer produced no diagnostics file (exit " + process.exitValue()
                    + "): " + firstLine(errorText));
            }
            List<Map<String, Object>> parsed = readLines(output);
            if (parsed.isEmpty() && process.exitValue() != 0) {
                return failure("the analyzer failed without reporting a diagnostic (exit "
                    + process.exitValue() + "): " + firstLine(errorText));
            }
            return new Result(parsed, true, null, errorText);
        } catch (IOException e) {
            return failure("could not be started: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure("was interrupted");
        } finally {
            deleteRecursively(work);
        }
    }

    private static Result failure(String reason) {
        return new Result(List.of(), false, reason, "");
    }

    private static String requestProperties(List<String> javacOptions, List<Path> classpath,
                                            List<Path> processorPath, List<Path> sources) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < javacOptions.size(); i++) {
            sb.append("option.").append(i).append('=').append(escape(javacOptions.get(i))).append('\n');
        }
        sb.append("classpath=").append(join(classpath)).append('\n');
        sb.append("processorpath=").append(join(processorPath)).append('\n');
        for (int i = 0; i < sources.size(); i++) {
            sb.append("source.").append(i).append('=').append(escape(sources.get(i).toString())).append('\n');
        }
        return sb.toString();
    }

    private static List<String> command(Path request, Path output) {
        List<String> command = new ArrayList<>();
        command.add(javaBinary());
        command.addAll(EXPORTS);
        command.add("-cp");
        command.add(selfClasspath());
        command.add(PluginRunner.class.getName());
        command.add(request.toString());
        command.add(output.toString());
        return command;
    }

    private static String selfClasspath() {
        try {
            Path self = Path.of(ForkedJavac.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
            return self.toString();
        } catch (Exception e) {
            return System.getProperty("java.class.path", ".");
        }
    }

    private static String javaBinary() {
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe).toString();
    }

    private static List<Map<String, Object>> readLines(Path output) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : Files.readAllLines(output, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                out.add(Json.parseObject(line));
            } catch (RuntimeException e) {
                // A malformed line is skipped; the caller reports how many diagnostics it saw.
            }
        }
        return out;
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        for (String line : text.split("\n", -1)) {
            if (!line.isBlank()) {
                return line.length() > 200 ? line.substring(0, 200) + "..." : line;
            }
        }
        return "";
    }

    private static String join(List<Path> paths) {
        List<String> parts = new ArrayList<>();
        for (Path p : paths) {
            parts.add(p.toString());
        }
        return String.join(java.io.File.pathSeparator, parts);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    static void deleteRecursively(Path dir) {
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
