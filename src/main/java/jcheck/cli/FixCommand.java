package jcheck.cli;

import java.nio.file.Path;
import jcheck.fix.FixApplier;

/**
 * {@code jcheck fix}: apply machine-applicable suggestions, re-check, and keep the result only
 * if the error count did not rise.
 */
public final class FixCommand {

    private FixCommand() {
    }

    public static CheckCommand.Outcome run(CheckRequest req, boolean includeMaybeIncorrect) {
        Path root = Path.of(req.root == null ? "." : req.root).toAbsolutePath().normalize();

        CheckCommand.Analysis before = CheckCommand.analyze(req);
        if (!before.ok()) {
            return new CheckCommand.Outcome(CheckCommand.EXIT_TOOL_FAILURE, "", before.error);
        }
        int beforeErrors = before.report.errorCount();

        FixApplier.Result result = FixApplier.apply(root, before.report.diagnostics, includeMaybeIncorrect);
        if (result.applied.isEmpty()) {
            StringBuilder sb = new StringBuilder("jcheck fix: no machine-applicable suggestions\n");
            for (String s : result.skipped) {
                sb.append("  skipped: ").append(s).append('\n');
            }
            return new CheckCommand.Outcome(CheckCommand.EXIT_CLEAN, sb.toString(), "");
        }

        CheckCommand.Analysis after = CheckCommand.analyze(req);
        int afterErrors = after.ok() ? after.report.errorCount() : Integer.MAX_VALUE;

        if (afterErrors > beforeErrors) {
            FixApplier.revert(root, result.backups);
            String msg = "jcheck fix: reverted; errors rose from " + beforeErrors
                + " to " + afterErrors + " across " + result.applied.size() + " file(s)\n";
            return new CheckCommand.Outcome(CheckCommand.EXIT_DIAGNOSTICS, msg, "");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("jcheck fix: applied ").append(result.editCount)
          .append(" edit(s) in ").append(result.applied.size()).append(" file(s)\n");
        for (FixApplier.Applied a : result.applied) {
            sb.append("  ").append(a.file).append(": ").append(a.editCount)
              .append(" edit(s) [").append(a.code).append("]\n");
        }
        for (String s : result.skipped) {
            sb.append("  skipped: ").append(s).append('\n');
        }
        sb.append("errors: ").append(beforeErrors).append(" -> ").append(afterErrors).append('\n');
        int exit = afterErrors > 0 ? CheckCommand.EXIT_DIAGNOSTICS : CheckCommand.EXIT_CLEAN;
        return new CheckCommand.Outcome(exit, sb.toString(), "");
    }
}
