package jcheck.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import jcheck.build.BuildModel;
import jcheck.daemon.Daemon;
import jcheck.daemon.DaemonClient;
import jcheck.explain.Explainer;
import jcheck.init.Initializer;
import jcheck.mcp.McpServer;

/** jcheck's single entry point. */
public final class Main {

    public static final String VERSION = "0.1.0";

    public static final Set<String> VALUE_FLAGS = Set.of(
        "root", "message-format", "max-diagnostics", "context-lines", "profile",
        "deny", "warn", "allow", "sources", "classpath", "release", "format",
        "watch-interval-ms");

    private Main() {
    }

    public static void main(String[] argv) {
        System.exit(run(argv));
    }

    /** Runs a command, prints its output, and returns the process exit code. */
    public static int run(String[] argv) {
        if (argv.length == 0) {
            System.out.print(usage());
            return CheckCommand.EXIT_TOOL_FAILURE;
        }
        String command = argv[0];
        String[] rest = new String[argv.length - 1];
        System.arraycopy(argv, 1, rest, 0, rest.length);

        switch (command) {
            case "help":
            case "--help":
            case "-h":
                System.out.print(usage());
                return CheckCommand.EXIT_CLEAN;
            case "version":
            case "--version":
            case "-V":
                System.out.println("jcheck " + VERSION + " (spec v0.1) "
                    + "running on JDK " + Runtime.version().feature());
                return CheckCommand.EXIT_CLEAN;
            case "check":
                return emit(runCheck(rest));
            case "fix":
                return emit(runFix(rest));
            case "test":
                return emit(TestCommand.run(buildRequest(Args.parse(rest, VALUE_FLAGS))));
            case "explain":
                return runExplain(rest);
            case "init":
                return runInit(rest);
            case "fmt":
                return emit(runFmt(rest));
            case "build":
                return emit(runBuild(rest));
            case "daemon":
                return runDaemon(rest);
            case "mcp":
                return runMcp(rest);
            default:
                System.err.println("jcheck: unknown command '" + command + "'");
                System.err.print(usage());
                return CheckCommand.EXIT_TOOL_FAILURE;
        }
    }

    private static int emit(CheckCommand.Outcome outcome) {
        if (outcome.stdout != null && !outcome.stdout.isEmpty()) {
            System.out.print(outcome.stdout);
        }
        if (outcome.stderr != null && !outcome.stderr.isEmpty()) {
            System.err.print(outcome.stderr);
        }
        return outcome.exitCode;
    }

    // ---- check ------------------------------------------------------------

    static CheckCommand.Outcome runCheck(String[] rest) {
        Args a = Args.parse(rest, VALUE_FLAGS);
        CheckRequest req = buildRequest(a);

        if (!a.has("no-daemon")) {
            Path root = Path.of(req.root).toAbsolutePath().normalize();
            boolean requireDaemon = a.has("daemon");
            if (requireDaemon && !DaemonClient.ping(root.toString())) {
                try {
                    Daemon.spawn(root, 15_000);
                } catch (IOException e) {
                    System.err.println("jcheck: could not start the daemon: " + e.getMessage());
                }
            }
            if (DaemonClient.ping(root.toString())) {
                try {
                    DaemonClient.Response r = DaemonClient.check(root.toString(), req);
                    return new CheckCommand.Outcome(r.exitCode, r.stdout, r.stderr);
                } catch (IOException e) {
                    System.err.println("jcheck: daemon connection failed, falling back in-process: "
                        + e.getMessage());
                }
            } else if (requireDaemon) {
                System.err.println("jcheck: daemon did not come up; running in-process");
            }
        }
        return CheckCommand.run(req);
    }

    public static CheckRequest buildRequest(Args a) {
        CheckRequest req = new CheckRequest();
        req.root = a.get("root", ".");
        req.format = a.get("message-format", a.get("format", "human"));
        req.maxDiagnostics = a.getInt("max-diagnostics", 0);
        req.contextLines = a.getInt("context-lines", -1);
        req.profile = a.has("profile") ? a.get("profile") : null;
        req.deny = a.getAllSplit("deny");
        req.warn = a.getAllSplit("warn");
        req.allow = a.getAllSplit("allow");
        req.denyWarnings = a.has("deny-warnings");
        req.reportSuppressions = a.has("report-suppressions");
        req.errorProne = a.has("error-prone");
        req.pluginOffline = a.has("offline");
        req.testLauncher = a.has("launcher");
        List<Path> sources = new ArrayList<>();
        for (String s : a.getAllSplit("sources")) {
            sources.add(Path.of(s));
        }
        req.sources = sources;
        List<Path> classpath = new ArrayList<>();
        for (String s : a.getAllSplit("classpath")) {
            classpath.add(Path.of(s));
        }
        req.classpath = classpath;
        if (a.has("release")) {
            int release = a.getInt("release", 0);
            req.release = release > 0 ? release : null;
        }
        return req;
    }

    // ---- fix --------------------------------------------------------------

    static CheckCommand.Outcome runFix(String[] rest) {
        Args a = Args.parse(rest, VALUE_FLAGS);
        CheckRequest req = buildRequest(a);
        return FixCommand.run(req, a.has("maybe-incorrect"));
    }

    // ---- explain ----------------------------------------------------------

    static int runExplain(String[] rest) {
        Args a = Args.parse(rest, VALUE_FLAGS);
        List<String> positional = a.positional();
        if (positional.isEmpty()) {
            System.err.println("jcheck: usage: jcheck explain <code>");
            return CheckCommand.EXIT_TOOL_FAILURE;
        }
        Explainer.Result r = Explainer.explain(a.get("root", "."), positional.get(0));
        if (r.exitCode == 0) {
            System.out.print(r.text);
        } else {
            System.err.print(r.text);
        }
        return r.exitCode;
    }

    // ---- init -------------------------------------------------------------

    static int runInit(String[] rest) {
        Args a = Args.parse(rest, VALUE_FLAGS);
        Path root = Path.of(a.get("root", "."));
        Initializer.Result r = Initializer.run(root, a.has("agent"), a.has("nullmarked"));
        if (!r.stdout.isEmpty()) {
            System.out.print(r.stdout);
        }
        if (!r.stderr.isEmpty()) {
            System.err.print(r.stderr);
        }
        return r.exitCode;
    }

    // ---- fmt --------------------------------------------------------------

    static CheckCommand.Outcome runFmt(String[] rest) {
        Args a = Args.parse(rest, VALUE_FLAGS);
        Path root = Path.of(a.get("root", ".")).toAbsolutePath().normalize();
        boolean checkOnly = a.has("check");

        Path formatter = findOnPath("google-java-format");
        if (formatter != null) {
            List<String> cmd = new ArrayList<>();
            cmd.add(formatter.toString());
            cmd.add(checkOnly ? "--dry-run" : "--replace");
            for (Path p : javaFiles(root)) {
                cmd.add(p.toString());
            }
            return runProcess(cmd, root, "fmt");
        }

        String tool = BuildModel.detectBuildTool(root);
        Path executable = BuildModel.findBuildExecutable(tool);
        if ("gradle".equals(tool) && executable != null) {
            return runProcess(List.of(executable.toString(),
                checkOnly ? "spotlessCheck" : "spotlessApply"), root, "fmt");
        }
        if ("maven".equals(tool) && executable != null) {
            return runProcess(List.of(executable.toString(),
                checkOnly ? "spotless:check" : "spotless:apply"), root, "fmt");
        }

        return new CheckCommand.Outcome(CheckCommand.EXIT_TOOL_FAILURE, "",
            "jcheck fmt: neither google-java-format nor a Spotless task is available. "
            + "Install google-java-format on PATH, or configure Spotless in the build.\n");
    }

    // ---- build ------------------------------------------------------------

    static CheckCommand.Outcome runBuild(String[] rest) {
        Args a = Args.parse(rest, VALUE_FLAGS);
        Path root = Path.of(a.get("root", ".")).toAbsolutePath().normalize();

        CheckCommand.Outcome check = runCheck(rest);
        if (check.exitCode != CheckCommand.EXIT_CLEAN) {
            return new CheckCommand.Outcome(check.exitCode,
                check.stdout,
                check.stderr + "jcheck: `jcheck build` stopped because the check was not clean\n");
        }

        String tool = BuildModel.detectBuildTool(root);
        Path executable = BuildModel.findBuildExecutable(tool);
        if (executable == null) {
            return new CheckCommand.Outcome(CheckCommand.EXIT_TOOL_FAILURE, "",
                "jcheck build: " + tool + " is not installed or not on PATH\n");
        }
        CheckCommand.Outcome build = runProcess(List.of(executable.toString(), "build"), root, "build");
        return new CheckCommand.Outcome(build.exitCode, check.stdout + build.stdout, build.stderr);
    }

    // ---- daemon -----------------------------------------------------------

    static int runDaemon(String[] rest) {
        Args a = Args.parse(rest, VALUE_FLAGS);
        List<String> positional = a.positional();
        String action = positional.isEmpty() ? "status" : positional.get(0);
        Path root = Path.of(a.get("root", ".")).toAbsolutePath().normalize();
        String rootPath = root.toString();

        switch (action) {
            case "start":
                if (DaemonClient.ping(rootPath)) {
                    System.out.println("jcheck daemon: already running for " + rootPath);
                    return CheckCommand.EXIT_CLEAN;
                }
                try {
                    boolean ok = Daemon.spawn(root, 15_000);
                    if (ok) {
                        System.out.println("jcheck daemon: started for " + rootPath);
                        System.out.println("  socket: " + Daemon.socketPath(rootPath));
                        return CheckCommand.EXIT_CLEAN;
                    }
                    System.err.println("jcheck daemon: did not become ready; see " + Daemon.logPath(rootPath));
                    return CheckCommand.EXIT_TOOL_FAILURE;
                } catch (IOException e) {
                    System.err.println("jcheck daemon: " + e.getMessage());
                    return CheckCommand.EXIT_TOOL_FAILURE;
                }
            case "stop":
                if (!DaemonClient.ping(rootPath)) {
                    System.out.println("jcheck daemon: not running for " + rootPath);
                    return CheckCommand.EXIT_CLEAN;
                }
                boolean stopped = DaemonClient.shutdown(rootPath);
                System.out.println(stopped
                    ? "jcheck daemon: stopped"
                    : "jcheck daemon: shutdown request failed");
                return stopped ? CheckCommand.EXIT_CLEAN : CheckCommand.EXIT_TOOL_FAILURE;
            case "run":
                try {
                    Daemon.serve(rootPath);
                    return CheckCommand.EXIT_CLEAN;
                } catch (IOException e) {
                    System.err.println("jcheck daemon: " + e.getMessage());
                    return CheckCommand.EXIT_TOOL_FAILURE;
                }
            case "status":
            default:
                if (DaemonClient.ping(rootPath)) {
                    System.out.println("jcheck daemon: running for " + rootPath);
                    System.out.println("  socket: " + Daemon.socketPath(rootPath));
                    return CheckCommand.EXIT_CLEAN;
                }
                System.out.println("jcheck daemon: not running for " + rootPath);
                return CheckCommand.EXIT_CLEAN;
        }
    }

    // ---- mcp --------------------------------------------------------------

    /**
     * {@code jcheck mcp}: a resources-only MCP server on stdio.
     *
     * <p>Stays in the foreground and owns stdin/stdout, because the stdio transport is the
     * protocol channel. All logging goes to stderr.
     */
    static int runMcp(String[] rest) {
        Args a = Args.parse(rest, VALUE_FLAGS);
        Path root = Path.of(a.get("root", ".")).toAbsolutePath().normalize();
        long pollMillis = a.getInt("watch-interval-ms", 1000);
        return McpServer.serveStdio(root, pollMillis);
    }

    // ---- helpers ----------------------------------------------------------

    static CheckCommand.Outcome runProcess(List<String> cmd, Path dir, String label) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO();
            int code = pb.start().waitFor();
            return new CheckCommand.Outcome(
                code == 0 ? CheckCommand.EXIT_CLEAN : code, "", "");
        } catch (IOException e) {
            return new CheckCommand.Outcome(CheckCommand.EXIT_TOOL_FAILURE, "",
                "jcheck " + label + ": cannot run " + cmd.get(0) + ": " + e.getMessage() + "\n");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CheckCommand.Outcome(CheckCommand.EXIT_TOOL_FAILURE, "",
                "jcheck " + label + ": interrupted\n");
        }
    }

    static Path findOnPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            Path candidate = Path.of(dir).resolve(name);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    static List<Path> javaFiles(Path root) {
        List<Path> out = new ArrayList<>();
        for (String base : new String[] {"src/main/java", "src/test/java"}) {
            Path dir = root.resolve(base);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .forEach(out::add);
            } catch (IOException ignored) {
                // skip unreadable trees
            }
        }
        return out;
    }

    static String usage() {
        return String.join("\n",
            "jcheck " + VERSION + " - a Rust-style feedback layer for Java",
            "",
            "usage: jcheck <command> [flags]",
            "",
            "commands:",
            "  check     fast path: in-process javac plus analyzers, no artifacts",
            "  build     delegates to the build tool after a clean check",
            "  test      runs tests via the build tool and renders JUnit XML as diagnostics",
            "  fix       applies machine-applicable suggestions, then re-checks",
            "  fmt       google-java-format or Spotless, whichever the project uses",
            "  explain   long-form explanation for a diagnostic code",
            "  init      writes jcheck.toml, optional package stubs and agent instructions",
            "  daemon    start | stop | status | run",
            "  mcp       resources-only MCP server on stdio (for MCP-capable agent hosts)",
            "  version   prints the version",
            "",
            "flags:",
            "  --message-format=human|json|llm|sarif   output format (default human)",
            "  --max-diagnostics N                     cap rendered diagnostics",
            "  --context-lines N                       source context lines for llm output",
            "  --profile strict|standard|lenient       policy profile",
            "  --deny/--warn/--allow <code>            per-code level override (repeatable)",
            "  --deny-warnings                         treat warnings as errors",
            "  --report-suppressions                   list every suppression found",
            "  --error-prone                           attach Error Prone and NullAway for this run",
            "  --offline                               never download plugin jars; fail if not cached",
            "  --launcher                              run tests via the JUnit Platform Console Launcher",
            "  --root <dir>                            project root (default .)",
            "  --sources/--classpath <paths>           override build model inputs",
            "  --release N                             compile against a release level",
            "  --daemon                                require the warm daemon",
            "  --no-daemon                             run in this process",
            "  --watch-interval-ms N                   MCP source-change poll interval (default 1000)",
            "",
            "exit codes: 0 clean, 1 diagnostics at error level, 2 tool or configuration failure",
            "");
    }
}
