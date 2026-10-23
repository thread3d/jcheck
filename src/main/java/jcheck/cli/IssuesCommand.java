package jcheck.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import jcheck.selfreport.Issue;
import jcheck.selfreport.IssueKind;
import jcheck.selfreport.IssueLog;
import jcheck.selfreport.SelfReport;

/**
 * {@code jcheck issues}: jcheck's own backlog.
 *
 * <p>jcheck records its bugs, failed integrations, catalog gaps and host limitations in a durable
 * JSONL file while it works (see {@code issues/README.md}). This command is how a later session
 * reads that file, finds where it lives, appends a note by hand, or clears it after a fix.
 */
public final class IssuesCommand {

    private IssuesCommand() {
    }

    public static int run(String[] argv) {
        Args a = Args.parse(argv, Set.of("detail", "kind", "root", "issue-log"));
        List<String> positional = a.positional();
        String sub = positional.isEmpty() ? "list" : positional.get(0);
        switch (sub) {
            case "list":
                return list(a);
            case "path":
                return path();
            case "clear":
                return clear();
            case "add":
                return add(a, positional);
            case "help":
            case "--help":
            case "-h":
                System.out.print(usage());
                return CheckCommand.EXIT_CLEAN;
            default:
                System.err.println("jcheck issues: unknown subcommand '" + sub + "'");
                System.err.print(usage());
                return CheckCommand.EXIT_TOOL_FAILURE;
        }
    }

    private static int list(Args a) {
        Path file = IssueLog.path();
        if (file == null) {
            System.err.println("jcheck: the self-report log is disabled or unwritable; "
                + "set " + IssueLog.ENV + "=<path> to record jcheck's own issues");
            return CheckCommand.EXIT_TOOL_FAILURE;
        }
        if (a.has("json")) {
            for (String line : IssueLog.readLines()) {
                System.out.println(line);
            }
            return CheckCommand.EXIT_CLEAN;
        }
        List<Issue> issues = IssueLog.read();
        if (issues.isEmpty()) {
            System.out.println("jcheck: no self-reported issues.");
            System.out.println("log: " + file);
            return CheckCommand.EXIT_CLEAN;
        }
        System.out.print(IssueLog.renderMarkdown(issues));
        System.out.println();
        System.out.println("log: " + file);
        return CheckCommand.EXIT_CLEAN;
    }

    private static int path() {
        Path file = IssueLog.path();
        if (file == null) {
            System.out.println("disabled");
            return CheckCommand.EXIT_CLEAN;
        }
        System.out.println(file);
        return CheckCommand.EXIT_CLEAN;
    }

    private static int clear() {
        Path file = IssueLog.path();
        if (file == null) {
            System.err.println("jcheck: the self-report log is disabled or unwritable; nothing to clear");
            return CheckCommand.EXIT_TOOL_FAILURE;
        }
        boolean existed = IssueLog.clear();
        System.out.println(existed
            ? "jcheck: cleared " + file
            : "jcheck: nothing to clear at " + file);
        return CheckCommand.EXIT_CLEAN;
    }

    private static int add(Args a, List<String> positional) {
        String summary = positional.size() > 1
            ? String.join(" ", positional.subList(1, positional.size()))
            : "";
        if (summary.isBlank()) {
            System.err.println("jcheck issues: usage: jcheck issues add <summary> [--detail <text>] "
                + "[--kind bug|gap|env|note]");
            return CheckCommand.EXIT_TOOL_FAILURE;
        }
        if (IssueLog.path() == null) {
            System.err.println("jcheck: the self-report log is disabled or unwritable; "
                + "set " + IssueLog.ENV + "=<path> to record it");
            return CheckCommand.EXIT_TOOL_FAILURE;
        }
        IssueKind kind = kindOf(a.get("kind"));
        Issue issue = SelfReport.record(kind, summary, a.get("detail"), null);
        if (issue == null) {
            System.err.println("jcheck: could not record the issue");
            return CheckCommand.EXIT_TOOL_FAILURE;
        }
        System.out.println("jcheck: recorded " + issue.id() + " in " + IssueLog.path());
        return CheckCommand.EXIT_CLEAN;
    }

    /** {@code bug}, {@code gap}, {@code env} and {@code note} are the words the log uses. */
    private static IssueKind kindOf(String word) {
        if (word == null) {
            return IssueKind.FRICTION;
        }
        switch (word.trim().toLowerCase()) {
            case "bug":
                return IssueKind.TOOL_FAILURE;
            case "gap":
                return IssueKind.CAPABILITY_GAP;
            case "env":
                return IssueKind.ENVIRONMENT;
            case "note":
            case "friction":
                return IssueKind.FRICTION;
            default:
                return IssueKind.fromWire(word.trim().toLowerCase());
        }
    }

    static String usage() {
        return String.join("\n",
            "usage: jcheck issues [list|path|clear|add] [flags]",
            "",
            "jcheck's own issue log: bugs, failed integrations, catalog gaps and host limits",
            "recorded while it works. One entry per distinct problem; clear it after a fix.",
            "",
            "  list            the backlog as markdown (default)",
            "  list --json     the raw JSONL lines",
            "  path            print the log file's location",
            "  clear           delete the log (use after fixing the entries)",
            "  add <summary>   record friction by hand; --detail <text>, --kind bug|gap|env|note",
            "",
            "The log location: --issue-log <path>, " + IssueLog.PROPERTY + ", "
                + IssueLog.ENV + ", else the",
            "jcheck checkout's issues/ directory, else ~/.local/state/jcheck/.",
            "Set it to 'off' to disable recording.",
            "");
    }
}
