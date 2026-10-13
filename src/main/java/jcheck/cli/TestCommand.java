package jcheck.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jcheck.build.BuildModel;
import jcheck.config.JcheckConfig;
import jcheck.model.CheckReport;
import jcheck.model.Diagnostic;
import jcheck.model.Tier;
import jcheck.model.TierReport;
import jcheck.render.RenderOptions;
import jcheck.render.Renderer;
import jcheck.render.Renderers;
import jcheck.testharness.JUnitHarness;
import jcheck.testharness.JUnitXmlParser;

/**
 * {@code jcheck test}: delegate execution to the project's build tool, then render the JUnit XML
 * reports through the same diagnostic model and renderers as the static checks.
 */
public final class TestCommand {

    private TestCommand() {
    }

    public static CheckCommand.Outcome run(CheckRequest req) {
        Path root = Path.of(req.root == null ? "." : req.root).toAbsolutePath().normalize();
        JcheckConfig config;
        try {
            config = JcheckConfig.load(root);
        } catch (RuntimeException e) {
            return new CheckCommand.Outcome(CheckCommand.EXIT_TOOL_FAILURE, "", "jcheck: " + e.getMessage() + "\n");
        }

        boolean launcher = req.testLauncher || "console".equalsIgnoreCase(config.testLauncher);
        List<String> notes = new ArrayList<>();
        int buildExit;
        if (launcher) {
            JUnitHarness.Result harness = JUnitHarness.run(root, config, notes);
            if (!harness.available) {
                return new CheckCommand.Outcome(CheckCommand.EXIT_TOOL_FAILURE, "",
                    "jcheck: " + String.join("\njcheck: ", harness.notes) + "\n");
            }
            buildExit = harness.exitCode;
        } else {
            String tool = BuildModel.detectBuildTool(root);
            Path executable = BuildModel.findBuildExecutable(root, tool);
            if (executable == null) {
                return new CheckCommand.Outcome(CheckCommand.EXIT_TOOL_FAILURE, "",
                    "jcheck: " + tool + " is not installed or not on PATH; `jcheck test` delegates "
                    + "execution to the project's build tool, or run `jcheck test --launcher` to use "
                    + "the JUnit Platform Console Launcher with jcheck-junit attached\n");
            }
            notes.add("delegated test execution to " + executable + " test");
            try {
                ProcessBuilder pb = new ProcessBuilder(executable.toString(), "test")
                    .directory(root.toFile())
                    .inheritIO();
                buildExit = pb.start().waitFor();
            } catch (IOException e) {
                return new CheckCommand.Outcome(CheckCommand.EXIT_TOOL_FAILURE, "",
                    "jcheck: cannot run " + executable + ": " + e.getMessage() + "\n");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new CheckCommand.Outcome(CheckCommand.EXIT_TOOL_FAILURE, "", "jcheck: interrupted\n");
            }
        }

        Path reportDir = resolveReportDir(root, config, notes);
        List<Diagnostic> diagnostics = JUnitXmlParser.parseDirectory(reportDir, root);
        if (diagnostics.isEmpty() && buildExit != 0) {
            notes.add("the build tool reported failure but no JUnit XML was found at " + reportDir);
        }

        String format = req.format == null ? "human" : req.format;
        Renderer renderer;
        try {
            renderer = Renderers.forFormat(format);
        } catch (IllegalArgumentException e) {
            return new CheckCommand.Outcome(CheckCommand.EXIT_TOOL_FAILURE, "", "jcheck: " + e.getMessage() + "\n");
        }

        CheckReport report = new CheckReport(diagnostics, List.of(), List.of(),
            testTierReport(), notes, List.of());

        int maxDiag = req.maxDiagnostics > 0 ? req.maxDiagnostics
            : ("llm".equals(format) ? config.llmMaxDiagnostics : 0);
        int contextLines = req.contextLines >= 0 ? req.contextLines : config.llmContextLines;
        String stdout = renderer.render(report,
            new RenderOptions(format, maxDiag, contextLines, false, root.toString()));

        int exit = diagnostics.isEmpty()
            ? (buildExit == 0 ? CheckCommand.EXIT_CLEAN : CheckCommand.EXIT_TOOL_FAILURE)
            : CheckCommand.EXIT_DIAGNOSTICS;
        return new CheckCommand.Outcome(exit, stdout, "");
    }

    /**
     * Tiers 0-4 are not run by {@code jcheck test}, and saying so is the honest answer: a passing
     * test run must not read as a clean static analysis.
     */
    static TierReport testTierReport() {
        List<TierReport.Entry> entries = new ArrayList<>();
        for (int t = 0; t <= Tier.MAX; t++) {
            if (t == Tier.T5) {
                entries.add(new TierReport.Entry(t, true, "junit xml reports"));
            } else {
                entries.add(new TierReport.Entry(t, false, "not run by jcheck test"));
            }
        }
        return new TierReport(entries, false);
    }

    static Path resolveReportDir(Path root, JcheckConfig config, List<String> notes) {
        if (config.reportDir != null && !config.reportDir.isBlank()) {
            Path p = root.resolve(config.reportDir);
            notes.add("report dir from jcheck.toml: " + config.reportDir);
            return p;
        }
        for (String candidate : new String[] {
                "build/test-results/test", "target/surefire-reports", "build/test-results/testDebugUnitTest"}) {
            Path p = root.resolve(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        Path fallback = root.resolve("build/test-results/test");
        notes.add("no JUnit XML report directory found; expected " + fallback);
        return fallback;
    }
}
