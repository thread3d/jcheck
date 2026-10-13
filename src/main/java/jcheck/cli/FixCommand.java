package jcheck.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jcheck.build.BuildModel;
import jcheck.config.JcheckConfig;
import jcheck.fix.FixApplier;
import jcheck.model.Diagnostic;
import jcheck.plugins.ErrorProneRunner;
import jcheck.plugins.OpenRewriteRunner;
import jcheck.plugins.PluginClasspath;

/**
 * {@code jcheck fix}: apply machine-applicable suggestions, optionally Error Prone's own patch
 * mode, re-check, and keep the result only if the error count did not rise.
 */
public final class FixCommand {

    private FixCommand() {
    }

    public static CheckCommand.Outcome run(CheckRequest req, boolean includeMaybeIncorrect,
                                           boolean errorPronePatches, boolean rewrite) {
        Path root = Path.of(req.root == null ? "." : req.root).toAbsolutePath().normalize();

        CheckCommand.Analysis before = CheckCommand.analyze(req);
        if (!before.ok()) {
            return new CheckCommand.Outcome(CheckCommand.EXIT_TOOL_FAILURE, "", before.error);
        }
        int beforeErrors = before.report.errorCount();

        JcheckConfig config;
        try {
            config = JcheckConfig.load(root);
        } catch (RuntimeException e) {
            return new CheckCommand.Outcome(CheckCommand.EXIT_TOOL_FAILURE, "", "jcheck: " + e.getMessage() + "\n");
        }

        // Snapshot before any edit so patch mode and OpenRewrite, which write in place, can be undone.
        Map<String, String> snapshot = new LinkedHashMap<>();
        List<Path> sources = List.of();
        BuildModel model = null;
        boolean wantPatches = errorPronePatches && (config.errorProne || req.errorProne);
        boolean wantRewrite = rewrite || config.rewrite;
        if (wantPatches || wantRewrite) {
            try {
                model = BuildModel.resolve(root, config, req.sources, req.classpath, null, req.release);
                sources = model.sources;
                snapshot = snapshot(sources);
            } catch (RuntimeException e) {
                return new CheckCommand.Outcome(CheckCommand.EXIT_TOOL_FAILURE, "",
                    "jcheck: cannot resolve sources for in-place edits: " + e.getMessage() + "\n");
            }
        }

        FixApplier.Result result = FixApplier.apply(root, before.report.diagnostics, includeMaybeIncorrect);

        List<String> notes = new ArrayList<>();
        boolean patched = false;
        if (wantPatches) {
            Set<String> checks = patchableChecks(before.report.diagnostics);
            if (checks.isEmpty()) {
                notes.add("no Error Prone patches were available (only Error Prone checks can patch)");
            } else {
                patched = runPatches(root, config, req, sources, new ArrayList<>(checks), notes);
            }
        }

        List<String> rewriteChanged = new ArrayList<>();
        if (wantRewrite && model != null) {
            OpenRewriteRunner.Result rewritten = OpenRewriteRunner.run(root, config, sources,
                model.classpath);
            notes.addAll(rewritten.notes);
            rewriteChanged.addAll(rewritten.changedFiles);
        }

        if (result.applied.isEmpty() && !patched && rewriteChanged.isEmpty()) {
            StringBuilder sb = new StringBuilder("jcheck fix: no machine-applicable suggestions\n");
            for (String s : result.skipped) {
                sb.append("  skipped: ").append(s).append('\n');
            }
            for (String n : notes) {
                sb.append("  note: ").append(n).append('\n');
            }
            return new CheckCommand.Outcome(CheckCommand.EXIT_CLEAN, sb.toString(), "");
        }

        CheckCommand.Analysis after = CheckCommand.analyze(req);
        int afterErrors = after.ok() ? after.report.errorCount() : Integer.MAX_VALUE;

        if (afterErrors > beforeErrors) {
            FixApplier.revert(root, result.backups);
            restore(snapshot);
            String msg = "jcheck fix: reverted; errors rose from " + beforeErrors
                + " to " + afterErrors + " across " + result.applied.size() + " file(s)\n";
            return new CheckCommand.Outcome(CheckCommand.EXIT_DIAGNOSTICS, msg, "");
        }

        StringBuilder sb = new StringBuilder();
        if (result.applied.isEmpty() && !patched) {
            sb.append("jcheck fix: applied OpenRewrite recipes\n");
        } else if (result.applied.isEmpty()) {
            sb.append("jcheck fix: applied Error Prone patch(es)\n");
        } else {
            sb.append("jcheck fix: applied ").append(result.editCount)
              .append(" edit(s) in ").append(result.applied.size()).append(" file(s)\n");
        }
        for (FixApplier.Applied a : result.applied) {
            sb.append("  ").append(a.file).append(": ").append(a.editCount)
              .append(" edit(s) [").append(a.code).append("]\n");
        }
        for (String file : rewriteChanged) {
            sb.append("  ").append(file).append(": rewritten\n");
        }
        for (String n : notes) {
            sb.append("  note: ").append(n).append('\n');
        }
        for (String s : result.skipped) {
            sb.append("  skipped: ").append(s).append('\n');
        }
        sb.append("errors: ").append(beforeErrors).append(" -> ").append(afterErrors).append('\n');
        int exit = afterErrors > 0 ? CheckCommand.EXIT_DIAGNOSTICS : CheckCommand.EXIT_CLEAN;
        return new CheckCommand.Outcome(exit, sb.toString(), "");
    }

    /** Only Error Prone's own checks have a patch mode; NullAway and javac do not. */
    private static Set<String> patchableChecks(List<Diagnostic> diagnostics) {
        Set<String> checks = new LinkedHashSet<>();
        for (Diagnostic d : diagnostics) {
            if ("errorprone".equals(d.source) && d.sourceKey != null && !d.sourceKey.isEmpty()
                && !d.sourceKey.startsWith("NullAway")) {
                checks.add(d.sourceKey);
            }
        }
        return checks;
    }

    private static boolean runPatches(Path root, JcheckConfig config, CheckRequest req,
                                      List<Path> sources, List<String> checks, List<String> notes) {
        try {
            PluginClasspath.Result cp = PluginClasspath.resolve(config.errorProneVersion,
                config.nullawayVersion, false, config.pluginClasspath, root,
                config.pluginOffline || req.pluginOffline, config.pluginRepo);
            BuildModel model = BuildModel.resolve(root, config, req.sources, req.classpath, null,
                req.release);
            ErrorProneRunner.Result patch = ErrorProneRunner.patch(sources, model.classpath,
                cp.processorPath, checks);
            notes.addAll(patch.notes);
            return patch.ran;
        } catch (RuntimeException e) {
            notes.add("Error Prone patch mode failed: " + e.getMessage());
            return false;
        }
    }

    private static Map<String, String> snapshot(List<Path> sources) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (Path p : sources) {
            try {
                snapshot.put(p.toString(), Files.readString(p, StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // an unreadable file simply cannot be restored
            }
        }
        return snapshot;
    }

    private static void restore(Map<String, String> snapshot) {
        for (Map.Entry<String, String> e : snapshot.entrySet()) {
            try {
                Files.writeString(Path.of(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // best effort, matching FixApplier.revert
            }
        }
    }
}
