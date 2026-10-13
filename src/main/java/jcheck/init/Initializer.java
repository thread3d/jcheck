package jcheck.init;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import jcheck.build.BuildModel;

/**
 * {@code jcheck init}: detect the build tool and JDK, write {@code jcheck.toml}, optionally add
 * {@code @NullMarked} package stubs and the agent instructions snippet.
 *
 * <p>Existing files are never overwritten. Steps that need third-party dependencies are opt-in
 * and say exactly which dependency the project must add.
 */
public final class Initializer {

    public static final class Result {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        public Result(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    private static final String AGENT_BEGIN = "<!-- jcheck:begin -->";
    private static final String AGENT_END = "<!-- jcheck:end -->";

    private Initializer() {
    }

    public static Result run(Path root, boolean agent, boolean nullmarked) {
        Path absRoot = root.toAbsolutePath().normalize();
        List<String> out = new ArrayList<>();
        List<String> err = new ArrayList<>();

        String tool = BuildModel.detectBuildTool(absRoot);
        int jdk = Runtime.version().feature();
        out.add("jcheck init: detected build tool " + tool + ", JDK " + jdk);

        Path config = absRoot.resolve("jcheck.toml");
        if (Files.exists(config)) {
            out.add("  kept existing jcheck.toml");
        } else {
            try {
                Files.writeString(config, template(tool, jdk), StandardCharsets.UTF_8);
                out.add("  wrote jcheck.toml");
            } catch (IOException e) {
                return new Result(2, String.join("\n", out) + "\n",
                    "jcheck: cannot write jcheck.toml: " + e.getMessage() + "\n");
            }
        }

        if (nullmarked) {
            List<Path> sourceRoots = new ArrayList<>();
            Path main = absRoot.resolve("src/main/java");
            if (Files.isDirectory(main)) {
                sourceRoots.add(main);
            } else {
                sourceRoots.add(absRoot);
            }
            List<Path> created = writePackageInfo(absRoot, sourceRoots);
            if (created.isEmpty()) {
                out.add("  no @NullMarked stubs needed");
            } else {
                for (Path p : created) {
                    out.add("  wrote " + absRoot.relativize(p));
                }
                out.add("  add the dependency: org.jspecify:jspecify (compileOnly)");
            }
        } else {
            out.add("  skipped @NullMarked package stubs (pass --nullmarked to add them)");
        }

        if (agent) {
            Path agents = absRoot.resolve("AGENTS.md");
            try {
                if (Files.exists(agents)) {
                    String existing = Files.readString(agents, StandardCharsets.UTF_8);
                    if (existing.contains(AGENT_BEGIN)) {
                        out.add("  agent instructions already present in AGENTS.md");
                    } else {
                        Files.writeString(agents, existing + "\n" + agentSnippet(), StandardCharsets.UTF_8);
                        out.add("  appended agent instructions to AGENTS.md");
                    }
                } else {
                    Files.writeString(agents, agentSnippet(), StandardCharsets.UTF_8);
                    out.add("  wrote AGENTS.md with agent instructions");
                }
            } catch (IOException e) {
                err.add("jcheck: cannot update AGENTS.md: " + e.getMessage());
            }
        }

        out.add("next: `jcheck check --message-format=llm`");
        return new Result(err.isEmpty() ? 0 : 2, String.join("\n", out) + "\n", String.join("\n", err));
    }

    private static List<Path> writePackageInfo(Path root, List<Path> sourceRoots) {
        List<Path> created = new ArrayList<>();
        for (Path sourceRoot : sourceRoots) {
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(sourceRoot)) {
                List<Path> dirs = new ArrayList<>();
                walk.filter(Files::isDirectory)
                    .filter(p -> p.equals(sourceRoot) || !p.getFileName().toString().startsWith("."))
                    .forEach(dirs::add);
                for (Path dir : dirs) {
                    if (!containsJava(dir) || Files.exists(dir.resolve("package-info.java"))) {
                        continue;
                    }
                    String packageName = packageNameOf(sourceRoot, dir);
                    if (packageName.isEmpty()) {
                        continue;
                    }
                    Path file = dir.resolve("package-info.java");
                    Files.writeString(file, packageInfo(packageName), StandardCharsets.UTF_8);
                    created.add(file);
                }
            } catch (IOException ignored) {
                // skip unreadable trees
            }
        }
        return created;
    }

    private static boolean containsJava(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.anyMatch(p -> p.toString().endsWith(".java")
                && !p.getFileName().toString().equals("package-info.java"));
        } catch (IOException e) {
            return false;
        }
    }

    private static String packageNameOf(Path sourceRoot, Path dir) {
        Path rel;
        try {
            rel = sourceRoot.relativize(dir);
        } catch (IllegalArgumentException e) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Path part : rel) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(part.toString());
        }
        return sb.toString();
    }

    private static String packageInfo(String packageName) {
        return "/**\n"
            + " * Package-level nullness policy: everything here is non-null unless marked\n"
            + " * {@code @Nullable}. Verified by jcheck (J05xx).\n"
            + " */\n"
            + "@NullMarked\n"
            + "package " + packageName + ";\n"
            + "\n"
            + "import org.jspecify.annotations.NullMarked;\n";
    }

    public static String agentSnippet() {
        return AGENT_BEGIN + "\n"
            + "- After every edit run `jcheck check --message-format=llm` and fix all errors before moving on.\n"
            + "- Fix lower tiers first; higher-tier findings appear only after T0-T2 are clean.\n"
            + "- Use `jcheck explain <code>` when a diagnostic is unclear.\n"
            + "- Never add @SuppressWarnings, @SuppressJcheck or @Unsafe without a real reason.\n"
            + "- Apply `jcheck fix` for machine-applicable suggestions, then re-check.\n"
            + "- Done means: `jcheck check` clean, `jcheck test` passing with no thread, resource or fd leaks.\n"
            + "- Prefer records, sealed interfaces, Optional/Result, try-with-resources, and immutable collections.\n"
            + "- Never use null in @NullMarked code; use @Nullable only at boundaries.\n"
            + AGENT_END + "\n";
    }

    public static String template(String buildTool, int jdk) {
        return "# jcheck configuration: one file, one policy.\n"
            + "\n"
            + "[project]\n"
            + "build   = \"" + buildTool + "\"            # gradle | maven | flat\n"
            + "jdk     = " + jdk + "\n"
            + "modules = []\n"
            + "resolve_classpath = true      # ask the build tool for its dependency classpath\n"
            + "\n"
            + "[profile]\n"
            + "name = \"strict\"               # strict | standard | lenient\n"
            + "\n"
            + "[javac]\n"
            + "lint  = [\"all\", \"-processing\", \"-serial\"]\n"
            + "extra = []\n"
            + "\n"
            + "[errorprone]\n"
            + "# Error Prone and NullAway are attached when enabled. Their jars are resolved from\n"
            + "# Maven Central on first use and cached under ~/.cache/jcheck/maven; set offline = true\n"
            + "# to forbid downloads, or classpath = [...] to point at jars you already have.\n"
            + "# With them off, T3 still runs the built-in jcheck pack.\n"
            + "enabled          = true\n"
            + "promote          = \"warning->error\"\n"
            + "disabled         = []\n"
            + "custom_pack      = true\n"
            + "version          = \"2.24.1\"\n"
            + "nullaway_version = \"0.10.18\"\n"
            + "classpath        = []\n"
            + "offline          = false\n"
            + "\n"
            + "[nullness]\n"
            + "mode     = \"nullaway-jspecify\"\n"
            + "packages = []\n"
            + "checker_framework = false\n"
            + "\n"
            + "[checkers]\n"
            + "# version = \"3.42.0\"           # resolved from Maven Central when checkers are enabled\n"
            + "\n"
            + "[checkers.per-module]\n"
            + "# core = [\"resourceleak\", \"optional\", \"index\"]\n"
            + "\n"
            + "[bytecode]\n"
            + "spotbugs = { effort = \"max\", threshold = \"medium\", findsecbugs = true }\n"
            + "infer    = false\n"
            + "\n"
            + "[tests]\n"
            + "timeout_seconds  = 10\n"
            + "thread_leaks     = \"fail\"\n"
            + "fd_leaks         = \"fail\"\n"
            + "mutation_testing = false\n"
            + "mutation_version = \"1.15.8\"     # PIT, resolved when mutation_testing is on\n"
            + "coverage         = false      # JaCoCo, with launcher = \"console\"\n"
            + "coverage_version = \"0.8.12\"\n"
            + "launcher         = \"delegate\"   # or \"console\" for jcheck-junit\n"
            + "random_order     = false\n"
            + "\n"
            + "[llm]\n"
            + "max_diagnostics = 15\n"
            + "context_lines   = 2\n"
            + "\n"
            + "[lints]\n"
            + "# J0301 = \"deny\"\n"
            + "# J0902 = \"warn\"\n";
    }
}
