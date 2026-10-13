package jcheck.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jcheck.build.BuildModel;
import jcheck.catalog.Catalog;
import jcheck.catalog.CatalogEntry;
import jcheck.config.JcheckConfig;
import jcheck.engine.SpanResolver;
import jcheck.model.CheckReport;
import jcheck.model.Diagnostic;
import jcheck.model.Level;
import jcheck.model.Span;
import jcheck.model.Tier;
import jcheck.model.TierReport;
import jcheck.plugins.OpenRewriteRunner;
import jcheck.render.RenderOptions;
import jcheck.render.Renderer;
import jcheck.render.Renderers;

/**
 * {@code jcheck rewrite}: apply the configured OpenRewrite recipes and report each change with a
 * stable code, so a rewrite lands in the same diagnostic list as everything else.
 *
 * <p>This is the recipe-only path. {@code jcheck fix --rewrite} applies the diagnostic model's
 * suggestions first and then recipes; this command is for "run the recipes and show me what they
 * did".
 */
public final class RewriteCommand {

    private RewriteCommand() {
    }

    public static CheckCommand.Outcome run(CheckRequest req) {
        Path root = Path.of(req.root == null ? "." : req.root).toAbsolutePath().normalize();
        JcheckConfig config;
        try {
            config = JcheckConfig.load(root);
        } catch (RuntimeException e) {
            return new CheckCommand.Outcome(CheckCommand.EXIT_TOOL_FAILURE, "", "jcheck: " + e.getMessage() + "\n");
        }

        BuildModel model;
        try {
            model = BuildModel.resolve(root, config, req.sources, req.classpath, null, req.release);
        } catch (RuntimeException e) {
            return new CheckCommand.Outcome(CheckCommand.EXIT_TOOL_FAILURE, "",
                "jcheck: cannot resolve the build model: " + e.getMessage() + "\n");
        }

        OpenRewriteRunner.Result result = OpenRewriteRunner.run(root, config, model.sources,
            model.classpath);
        if (!result.ran) {
            return new CheckCommand.Outcome(CheckCommand.EXIT_TOOL_FAILURE, "",
                "jcheck: " + String.join("\njcheck: ", result.notes) + "\n");
        }

        Catalog catalog = Catalog.loadDefault();
        SpanResolver spans = new SpanResolver();
        List<Diagnostic> diagnostics = new ArrayList<>();
        int seq = 9_000_000;
        for (OpenRewriteRunner.Change change : result.changes) {
            CatalogEntry entry = catalog.lookup("openrewrite", change.recipe);
            String code = entry != null ? entry.code()
                : catalog.codeFor("openrewrite", "OpenRewriteRecipe");
            String headline = entry != null ? entry.headline() : "OpenRewrite recipe applied";
            Span span = firstLineSpan(spans, change.file);
            Diagnostic.Builder b = Diagnostic.builder(code)
                .source("openrewrite")
                .sourceKey(change.recipe)
                .level(Level.NOTE)
                .tier(Tier.T5)
                .message(headline + " (`" + simpleName(change.recipe) + "`)")
                .primary(span)
                .seq(seq++);
            if (entry != null) {
                for (String h : entry.help) {
                    b.help(h);
                }
            }
            b.note("recipe: " + change.recipe);
            diagnostics.add(b.build());
        }

        String format = req.format == null ? "human" : req.format;
        Renderer renderer;
        try {
            renderer = Renderers.forFormat(format);
        } catch (IllegalArgumentException e) {
            return new CheckCommand.Outcome(CheckCommand.EXIT_TOOL_FAILURE, "", "jcheck: " + e.getMessage() + "\n");
        }
        List<String> notes = new ArrayList<>(model.notes);
        notes.addAll(result.notes);
        CheckReport report = new CheckReport(diagnostics, List.of(), List.of(), rewriteTiers(), notes,
            List.of());
        int maxDiag = req.maxDiagnostics > 0 ? req.maxDiagnostics
            : ("llm".equals(format) ? config.llmMaxDiagnostics : 0);
        int contextLines = req.contextLines >= 0 ? req.contextLines : config.llmContextLines;
        String stdout = renderer.render(report,
            new RenderOptions(format, maxDiag, contextLines, false, root.toString()));
        return new CheckCommand.Outcome(CheckCommand.EXIT_CLEAN, stdout, "");
    }

    /** A span over the first line of the changed file; a rewrite is a whole-file event. */
    private static Span firstLineSpan(SpanResolver spans, String file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        try {
            String content = Files.readString(Path.of(file), StandardCharsets.UTF_8);
            spans.put(file, content);
            int end = content.indexOf('\n');
            return spans.span(file, null, 0, end < 0 ? content.length() : end, -1, -1);
        } catch (IOException | RuntimeException e) {
            return spans.span(file, null, -1, -1, 1, 1);
        }
    }

    private static String simpleName(String recipe) {
        int dot = recipe.lastIndexOf('.');
        return dot < 0 ? recipe : recipe.substring(dot + 1);
    }

    /** Tier 5 ran (the recipes); the static tiers did not. */
    static TierReport rewriteTiers() {
        List<TierReport.Entry> entries = new ArrayList<>();
        for (int t = 0; t <= Tier.MAX; t++) {
            if (t == Tier.T5) {
                entries.add(new TierReport.Entry(t, true, "openrewrite"));
            } else {
                entries.add(new TierReport.Entry(t, false, "not run by jcheck rewrite"));
            }
        }
        return new TierReport(entries, false);
    }
}
