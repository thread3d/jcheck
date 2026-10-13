package jcheck.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import jcheck.build.BuildModel;
import jcheck.config.JcheckConfig;
import jcheck.engine.CheckOptions;
import jcheck.engine.Engine;
import jcheck.engine.LevelPolicy;
import jcheck.model.CheckReport;
import jcheck.plugins.PluginClasspath;
import jcheck.render.RenderOptions;
import jcheck.render.Renderer;
import jcheck.render.Renderers;

/**
 * The check pipeline, shared by the in-process CLI path and the daemon.
 *
 * <p>Returns rendered text and an exit code rather than printing, which is what lets the daemon
 * serve the exact same output a local run would produce.
 */
public final class CheckCommand {

    /** Exit code contract: 0 clean, 1 diagnostics at error level, 2 tool or configuration failure. */
    public static final int EXIT_CLEAN = 0;
    public static final int EXIT_DIAGNOSTICS = 1;
    public static final int EXIT_TOOL_FAILURE = 2;

    public static final class Outcome {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        public Outcome(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    /** Either a report or the reason one could not be produced. */
    public static final class Analysis {
        public final CheckReport report;
        public final String error;

        Analysis(CheckReport report, String error) {
            this.report = report;
            this.error = error;
        }

        public boolean ok() {
            return report != null;
        }
    }

    private CheckCommand() {
    }

    /** Runs the pipeline and renders it. */
    public static Outcome run(CheckRequest req) {
        Analysis a = analyze(req);
        if (!a.ok()) {
            return new Outcome(EXIT_TOOL_FAILURE, "", a.error);
        }
        CheckReport report = a.report;

        String format = req.format == null ? "human" : req.format;
        Renderer renderer;
        try {
            renderer = Renderers.forFormat(format);
        } catch (IllegalArgumentException e) {
            return new Outcome(EXIT_TOOL_FAILURE, "", "jcheck: " + e.getMessage() + "\n");
        }
        Path root = Path.of(req.root == null ? "." : req.root).toAbsolutePath().normalize();

        JcheckConfig config;
        try {
            config = JcheckConfig.load(root);
        } catch (RuntimeException e) {
            return new Outcome(EXIT_TOOL_FAILURE, "", "jcheck: " + e.getMessage() + "\n");
        }

        int maxDiag;
        if (req.maxDiagnostics > 0) {
            maxDiag = req.maxDiagnostics;
        } else if ("llm".equals(format)) {
            maxDiag = config.llmMaxDiagnostics;
        } else {
            maxDiag = 0;
        }
        int contextLines = req.contextLines >= 0 ? req.contextLines : config.llmContextLines;

        RenderOptions ro = new RenderOptions(format, maxDiag, contextLines, false, root.toString());
        String stdout = renderer.render(report, ro);

        if (req.reportSuppressions) {
            StringBuilder sb = new StringBuilder(stdout);
            sb.append('\n').append("suppressions:").append('\n');
            if (report.suppressions.isEmpty()) {
                sb.append("  (none)").append('\n');
            } else {
                for (String s : report.suppressions) {
                    sb.append("  ").append(s).append('\n');
                }
            }
            stdout = sb.toString();
        }

        StringBuilder err = new StringBuilder();
        for (String ce : report.configErrors) {
            err.append("jcheck: ").append(ce).append('\n');
        }

        int exit;
        if (!report.configErrors.isEmpty()) {
            exit = EXIT_TOOL_FAILURE;
        } else if (report.errorCount() > 0) {
            exit = EXIT_DIAGNOSTICS;
        } else {
            exit = EXIT_CLEAN;
        }
        return new Outcome(exit, stdout, err.toString());
    }

    /** Runs the pipeline without rendering, for callers that need the diagnostics themselves. */
    public static Analysis analyze(CheckRequest req) {
        Path root = Path.of(req.root == null ? "." : req.root).toAbsolutePath().normalize();

        JcheckConfig config;
        try {
            config = JcheckConfig.load(root);
        } catch (RuntimeException e) {
            return new Analysis(null, "jcheck: " + e.getMessage() + "\n");
        }

        BuildModel model;
        try {
            model = BuildModel.resolve(root, config, req.sources, req.classpath, null, req.release);
        } catch (RuntimeException e) {
            return new Analysis(null, "jcheck: cannot resolve build model: " + e.getMessage() + "\n");
        }

        Path scratch;
        try {
            scratch = Files.createTempDirectory("jcheck-scratch-");
        } catch (IOException e) {
            return new Analysis(null, "jcheck: cannot create a scratch directory: " + e.getMessage() + "\n");
        }

        String profile = req.profile != null ? req.profile : config.profile;
        LevelPolicy policy = config.levelPolicy(profile, req.denyWarnings, req.deny, req.warn, req.allow);

        /*
         * Resolve the analyzer jars before the first compile. NullAway needs JSpecify on the
         * checked code's classpath, and a missing annotation jar would fail T1 and hide every T3
         * finding — including the NullAway one the user enabled the plugin to see.
         */
        boolean errorProne = config.errorProne || req.errorProne;
        List<Path> classpath = new ArrayList<>(model.classpath);
        List<Path> pluginJars = List.of();
        List<String> pluginNotes = new ArrayList<>();
        if (errorProne) {
            try {
                PluginClasspath.Result plugins = PluginClasspath.resolve(
                    config.errorProneVersion, config.nullawayVersion,
                    config.nullnessMode != null && config.nullnessMode.startsWith("nullaway"),
                    config.pluginClasspath, root,
                    config.pluginOffline || req.pluginOffline, config.pluginRepo);
                pluginJars = plugins.processorPath;
                pluginNotes.addAll(plugins.notes);
                classpath.addAll(plugins.compileExtra);
            } catch (RuntimeException e) {
                pluginNotes.add("Error Prone is enabled but its plugin jars could not be resolved: "
                    + e.getMessage());
            }
        }

        CheckOptions.Builder ob = CheckOptions.builder()
            .rootDir(root.toString())
            .profile(profile)
            .levelPolicy(policy)
            .release(model.release)
            .lints(config.lint)
            .javacExtra(config.javacExtra)
            .scratchDir(scratch)
            .sources(model.sources)
            .classpath(classpath)
            .processorPath(model.processorPath)
            .nativeChecks(config.customPack)
            .processAnnotations(!model.processorPath.isEmpty())
            .errorProne(errorProne)
            .errorProneVersion(config.errorProneVersion)
            .nullawayVersion(config.nullawayVersion)
            .pluginClasspath(config.pluginClasspath)
            .pluginOffline(config.pluginOffline || req.pluginOffline)
            .pluginRepo(config.pluginRepo)
            .nullnessMode(config.nullnessMode)
            .nullnessPackages(config.nullnessPackages)
            .errorProneDisabled(config.errorProneDisabled)
            .pluginJars(pluginJars)
            .toolNotes(pluginNotes)
            .toolNotes(model.notes);

        if (config.spotbugs) {
            ob.toolNote("spotbugs is enabled in jcheck.toml but bytecode analysis is not wired up; T5 did not run");
        }

        try {
            CheckReport report = Engine.run(ob.build());
            return new Analysis(report, null);
        } catch (RuntimeException e) {
            return new Analysis(null, "jcheck: " + e.getMessage() + "\n");
        } finally {
            deleteRecursively(scratch);
        }
    }

    static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
