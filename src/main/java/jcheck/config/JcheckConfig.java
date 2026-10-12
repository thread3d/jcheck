package jcheck.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jcheck.engine.LevelPolicy;
import jcheck.model.Level;

/**
 * The parsed {@code jcheck.toml}. Every field has a default, so a missing or partial config
 * still yields a usable policy.
 */
public final class JcheckConfig {

    public String build = "auto";
    public Integer jdk;
    public Integer release;
    public List<String> modules = List.of();
    /** Explicit source roots, relative to the project root. */
    public List<String> sourceRoots = List.of();
    public String profile = "strict";
    public List<String> lint = List.of("all", "-processing", "-serial");
    public List<String> javacExtra = List.of();
    /**
     * Whether Error Prone/NullAway are attached. False by default because the plugin jars are not
     * wired up yet; claiming T3 for a tool that did not run would be exactly the kind of quiet
     * false-clean the tier model exists to prevent.
     */
    public boolean errorProne = false;
    public List<String> errorProneDisabled = List.of();
    public boolean customPack = true;
    public String nullnessMode = "nullaway-jspecify";
    public List<String> nullnessPackages = List.of();
    public boolean checkerFramework = false;
    public Map<String, List<String>> checkersPerModule = Map.of();
    public boolean spotbugs = false;
    public String spotbugsEffort = "max";
    public String spotbugsThreshold = "medium";
    public boolean findsecbugs = true;
    public boolean infer = false;
    public int testTimeoutSeconds = 10;
    public String threadLeaks = "fail";
    public String fdLeaks = "fail";
    public boolean mutationTesting = false;
    public int llmMaxDiagnostics = 15;
    public int llmContextLines = 2;
    /** Per-code overrides from the {@code [lints]} table: code to deny|warn|allow|error|warning. */
    public Map<String, String> lintOverrides = Map.of();
    /** Path to a JUnit Platform Console Launcher jar, for {@code jcheck test}. */
    public String consoleLauncher;
    /** Fixed report location used by {@code jcheck test}. */
    public String reportDir;
    /** Where this config was loaded from, or null when defaults are in use. */
    public Path loadedFrom;

    public static JcheckConfig defaults() {
        return new JcheckConfig();
    }

    public static JcheckConfig load(Path projectRoot) {
        Path file = projectRoot.resolve("jcheck.toml");
        if (!Files.isRegularFile(file)) {
            return defaults();
        }
        try {
            JcheckConfig c = fromToml(Toml.parseFile(file));
            c.loadedFrom = file;
            return c;
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    public static JcheckConfig fromToml(Map<String, Object> root) {
        JcheckConfig c = new JcheckConfig();
        Map<String, Object> project = Toml.table(root, "project");
        c.build = Toml.str(project, "build", c.build);
        c.jdk = Toml.integer(project, "jdk", null);
        c.release = Toml.integer(project, "release", null);
        c.modules = Toml.strList(project, "modules", c.modules);
        c.sourceRoots = Toml.strList(project, "sources", c.sourceRoots);

        c.profile = Toml.str(Toml.table(root, "profile"), "name", c.profile);

        Map<String, Object> javac = Toml.table(root, "javac");
        c.lint = Toml.strList(javac, "lint", c.lint);
        c.javacExtra = Toml.strList(javac, "extra", c.javacExtra);

        Map<String, Object> ep = Toml.table(root, "errorprone");
        c.errorProne = Toml.bool(ep, "enabled", c.errorProne);
        c.errorProneDisabled = Toml.strList(ep, "disabled", c.errorProneDisabled);
        c.customPack = Toml.bool(ep, "custom_pack", c.customPack);

        Map<String, Object> nullness = Toml.table(root, "nullness");
        c.nullnessMode = Toml.str(nullness, "mode", c.nullnessMode);
        c.nullnessPackages = Toml.strList(nullness, "packages", c.nullnessPackages);
        c.checkerFramework = Toml.bool(nullness, "checker_framework", c.checkerFramework);

        Map<String, Object> perModule = Toml.table(root, "checkers.per-module");
        Map<String, List<String>> checkers = new LinkedHashMap<>();
        for (String module : Toml.keys(perModule)) {
            checkers.put(module, Toml.strList(perModule, module, List.of()));
        }
        c.checkersPerModule = checkers;
        if (!checkers.isEmpty()) {
            c.checkerFramework = true;
        }

        Map<String, Object> bytecode = Toml.table(root, "bytecode");
        Map<String, Object> sb = Toml.table(bytecode, "spotbugs");
        if (!sb.isEmpty()) {
            c.spotbugs = true;
            c.spotbugsEffort = Toml.str(sb, "effort", c.spotbugsEffort);
            c.spotbugsThreshold = Toml.str(sb, "threshold", c.spotbugsThreshold);
            c.findsecbugs = Toml.bool(sb, "findsecbugs", c.findsecbugs);
        } else {
            c.spotbugs = Toml.bool(bytecode, "spotbugs", false);
        }
        c.infer = Toml.bool(bytecode, "infer", c.infer);

        Map<String, Object> tests = Toml.table(root, "tests");
        c.testTimeoutSeconds = Toml.integer(tests, "timeout_seconds", c.testTimeoutSeconds);
        c.threadLeaks = Toml.str(tests, "thread_leaks", c.threadLeaks);
        c.fdLeaks = Toml.str(tests, "fd_leaks", c.fdLeaks);
        c.mutationTesting = Toml.bool(tests, "mutation_testing", c.mutationTesting);
        c.consoleLauncher = Toml.str(tests, "console_launcher", null);
        c.reportDir = Toml.str(tests, "report_dir", null);

        Map<String, Object> llm = Toml.table(root, "llm");
        c.llmMaxDiagnostics = Toml.integer(llm, "max_diagnostics", c.llmMaxDiagnostics);
        c.llmContextLines = Toml.integer(llm, "context_lines", c.llmContextLines);

        Map<String, Object> lintTable = Toml.table(root, "lints");
        Map<String, String> overrides = new LinkedHashMap<>();
        for (String code : Toml.keys(lintTable)) {
            overrides.put(code, Toml.str(lintTable, code, "warn"));
        }
        c.lintOverrides = overrides;
        return c;
    }

    /**
     * Builds the level policy from this config, with CLI overrides layered on top.
     *
     * @param cliDeny  codes forced to error
     * @param cliWarn  codes forced to warning
     * @param cliAllow codes dropped entirely
     */
    public LevelPolicy levelPolicy(boolean denyWarnings, List<String> cliDeny,
                                   List<String> cliWarn, List<String> cliAllow) {
        return levelPolicy(profile, denyWarnings, cliDeny, cliWarn, cliAllow);
    }

    /**
     * Builds the level policy, with the profile and CLI overrides layered on top of the config's
     * per-code rules.
     *
     * @param profileOverride profile selected on the command line; null uses the config's
     * @param cliDeny  codes forced to error
     * @param cliWarn  codes forced to warning
     * @param cliAllow codes dropped entirely
     */
    public LevelPolicy levelPolicy(String profileOverride, boolean denyWarnings, List<String> cliDeny,
                                   List<String> cliWarn, List<String> cliAllow) {
        LevelPolicy.Builder b = LevelPolicy
            .builder(profileOverride == null ? profile : profileOverride)
            .denyWarnings(denyWarnings);
        for (Map.Entry<String, String> e : lintOverrides.entrySet()) {
            Level l = Level.parse(e.getValue());
            if (l == null) {
                b.allow(e.getKey());
            } else if (l == Level.ERROR) {
                b.deny(e.getKey());
            } else {
                b.warn(e.getKey());
            }
        }
        for (String code : cliAllow) {
            b.allow(code);
        }
        for (String code : cliWarn) {
            b.warn(code);
        }
        for (String code : cliDeny) {
            b.deny(code);
        }
        return b.build();
    }

    /** True when any Checker Framework checker is configured for any module. */
    public boolean anyCheckerConfigured() {
        if (checkerFramework) {
            return true;
        }
        for (List<String> v : checkersPerModule.values()) {
            if (v != null && !v.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** The modules declared in the project, or a single unnamed module for a flat layout. */
    public List<String> moduleNames() {
        return modules.isEmpty() ? List.of() : new ArrayList<>(modules);
    }
}
