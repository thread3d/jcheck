package jcheck.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jcheck.catalog.Catalog;
import jcheck.config.JcheckConfig;
import jcheck.model.CheckReport;
import jcheck.model.Tier;
import jcheck.model.TierReport;
import jcheck.plugins.InferRunner;
import jcheck.render.RenderOptions;
import jcheck.render.Renderer;
import jcheck.render.Renderers;
import jcheck.selfreport.SelfReport;

/**
 * {@code jcheck infer}: run Facebook Infer and render its findings as diagnostics.
 *
 * <p>Infer is a native binary, so this command reports plainly when it is not installed rather than
 * pretending a quiet T5. Pass {@code --sources} to scope it.
 */
public final class InferCommand {

    private InferCommand() {
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
        jcheck.build.BuildModel model = jcheck.build.BuildModel.resolve(root, config, scoped,
            req.classpath, null, req.release);
        InferRunner.Result result = InferRunner.run(root, config.inferBinary, model.sources,
            model.classpath, Catalog.loadDefault());
        List<String> notes = new ArrayList<>(model.notes);
        notes.addAll(result.notes);

        String format = req.format == null ? "human" : req.format;
        Renderer renderer;
        try {
            renderer = Renderers.forFormat(format);
        } catch (IllegalArgumentException e) {
            return new CheckCommand.Outcome(CheckCommand.EXIT_TOOL_FAILURE, "", "jcheck: " + e.getMessage() + "\n");
        }

        CheckReport report = new CheckReport(result.diagnostics, List.of(), List.of(),
            inferTiers(), notes, List.of());
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

    /** Tier 5 ran (Infer); the static tiers did not, and saying so keeps the line honest. */
    static TierReport inferTiers() {
        List<TierReport.Entry> entries = new ArrayList<>();
        for (int t = 0; t <= Tier.MAX; t++) {
            if (t == Tier.T5) {
                entries.add(new TierReport.Entry(t, true, "infer"));
            } else {
                entries.add(new TierReport.Entry(t, false, "not run by jcheck infer"));
            }
        }
        return new TierReport(entries, false);
    }
}
