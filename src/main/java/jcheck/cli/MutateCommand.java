package jcheck.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jcheck.catalog.Catalog;
import jcheck.config.JcheckConfig;
import jcheck.model.CheckReport;
import jcheck.model.Tier;
import jcheck.model.TierReport;
import jcheck.render.RenderOptions;
import jcheck.render.Renderer;
import jcheck.render.Renderers;
import jcheck.selfreport.SelfReport;
import jcheck.testharness.MutationRunner;

/**
 * {@code jcheck mutate}: run PIT mutation testing and render surviving mutants as diagnostics.
 *
 * <p>This is the check that measures the tests: a mutant that survives means the suite asserted
 * something that does not depend on the logic it claims to cover. Pass {@code --sources} to scope it
 * to changed files.
 */
public final class MutateCommand {

    private MutateCommand() {
    }

    public static CheckCommand.Outcome run(CheckRequest req) {
        Path root = Path.of(req.root == null ? "." : req.root).toAbsolutePath().normalize();
        JcheckConfig config;
        try {
            config = JcheckConfig.load(root);
        } catch (RuntimeException e) {
            return new CheckCommand.Outcome(CheckCommand.EXIT_TOOL_FAILURE, "", "jcheck: " + e.getMessage() + "\n");
        }

        List<Path> scoped = new ArrayList<>();
        for (Path p : req.sources) {
            scoped.add(p.isAbsolute() ? p : root.resolve(p));
        }
        MutationRunner.Result result = MutationRunner.run(root, config, scoped, Catalog.loadDefault());

        String format = req.format == null ? "human" : req.format;
        Renderer renderer;
        try {
            renderer = Renderers.forFormat(format);
        } catch (IllegalArgumentException e) {
            return new CheckCommand.Outcome(CheckCommand.EXIT_TOOL_FAILURE, "", "jcheck: " + e.getMessage() + "\n");
        }

        CheckReport report = new CheckReport(result.diagnostics, List.of(), List.of(),
            mutationTiers(), result.notes, List.of());
        SelfReport.captureReport(report);
        int maxDiag = req.maxDiagnostics > 0 ? req.maxDiagnostics
            : ("llm".equals(format) ? config.llmMaxDiagnostics : 0);
        int contextLines = req.contextLines >= 0 ? req.contextLines : config.llmContextLines;
        String stdout = renderer.render(report,
            new RenderOptions(format, maxDiag, contextLines, false, root.toString()));

        int exit;
        if (!result.ran) {
            exit = CheckCommand.EXIT_TOOL_FAILURE;
        } else if (result.diagnostics.isEmpty()) {
            exit = CheckCommand.EXIT_CLEAN;
        } else {
            exit = CheckCommand.EXIT_DIAGNOSTICS;
        }
        return new CheckCommand.Outcome(exit, stdout, "");
    }

    /** Tier 5 ran (PIT); the static tiers did not, and saying so keeps the line honest. */
    static TierReport mutationTiers() {
        List<TierReport.Entry> entries = new ArrayList<>();
        for (int t = 0; t <= Tier.MAX; t++) {
            if (t == Tier.T5) {
                entries.add(new TierReport.Entry(t, true, "pit mutation testing"));
            } else {
                entries.add(new TierReport.Entry(t, false, "not run by jcheck mutate"));
            }
        }
        return new TierReport(entries, false);
    }
}
