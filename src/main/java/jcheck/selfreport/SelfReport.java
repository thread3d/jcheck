package jcheck.selfreport;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collection;
import java.util.Locale;
import jcheck.cli.Main;
import jcheck.model.CheckReport;
import jcheck.model.Diagnostic;
import jcheck.model.Tier;
import jcheck.model.TierReport;

/**
 * The one way jcheck records its own trouble.
 *
 * <p>Call this when <em>jcheck</em> misbehaves — an unexpected exception, an analyzer that was
 * supposed to run and did not, an upstream diagnostic jcheck cannot name yet. Do not call it for
 * problems in the checked code: those are diagnostics, and they already have a home. The log is
 * for the backlog of making the tool itself better.
 *
 * <p>Every entry is best-effort: capture never prints, never throws, and never changes an exit
 * code. The {@code hint} stored with each entry names the kind of fix it expects.
 */
public final class SelfReport {

    private static volatile String command = "";
    private static volatile String root = "";

    private SelfReport() {
    }

    /** Sets the context stamped on every issue captured during this command. */
    public static void beginRun(String commandName, String projectRoot) {
        if (commandName != null && !commandName.isBlank()) {
            command = commandName;
        }
        if (projectRoot != null && !projectRoot.isBlank()) {
            root = projectRoot;
        }
    }

    /** Updates the project root without disturbing the command name (daemon and fix paths). */
    public static void setRoot(String projectRoot) {
        if (projectRoot != null && !projectRoot.isBlank()) {
            root = projectRoot;
        }
    }

    public static String command() {
        return command;
    }

    public static String root() {
        return root;
    }

    /** Record an exception jcheck did not expect. Always a bug. */
    public static Issue internalError(String summary, Throwable t) {
        return record(IssueKind.INTERNAL_ERROR, summary, detailOf(t), stackOf(t));
    }

    /** Record an integration that should have run and did not. */
    public static Issue toolFailure(String summary, String detail) {
        return record(IssueKind.TOOL_FAILURE, summary, detail, null);
    }

    /** Record upstream data jcheck does not curate or implement yet. */
    public static Issue capabilityGap(String summary, String detail) {
        return record(IssueKind.CAPABILITY_GAP, summary, detail, null);
    }

    /** Record something the host lacks; jcheck degraded as designed. */
    public static Issue environment(String summary, String detail) {
        return record(IssueKind.ENVIRONMENT, summary, detail, null);
    }

    /** Record friction by hand, with {@code jcheck issues add}. */
    public static Issue friction(String summary, String detail) {
        return record(IssueKind.FRICTION, summary, detail, null);
    }

    /** Writes one entry, deduplicated by fingerprint, and returns it even when the write was skipped. */
    public static Issue record(IssueKind kind, String summary, String detail, String stack) {
        if (kind == null || summary == null || summary.isBlank()) {
            return null;
        }
        String cleanSummary = oneLine(summary);
        Issue issue = new Issue(
            Issue.fingerprint(kind, cleanSummary, detail),
            Issue.now(),
            kind,
            cleanSummary,
            detail == null ? "" : detail.strip(),
            kind.hint(),
            command,
            root,
            Main.VERSION,
            Runtime.version().toString(),
            System.getProperty("os.name", "") + " "
                + System.getProperty("os.version", "") + " "
                + System.getProperty("os.arch", ""),
            stack == null ? "" : stack);
        IssueLog.append(issue);
        return issue;
    }

    /**
     * Classifies a tool note: the notes are already jcheck's own statement that something did not
     * happen, so they are the highest-signal automatic source of self-reports.
     *
     * <p>Returns null for notes that are merely informative (a fallback source root, a cached
     * build model, a configured plugin classpath) and for catalog-count notes, because uncatalogued
     * findings are reported precisely, one entry per upstream key, by {@link #captureReport}.
     */
    public static IssueKind classifyNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String n = note.toLowerCase(Locale.ROOT);
        if (n.contains("not in the catalog") || n.contains("uncatalogued")
            || n.contains("needs curation")) {
            return null; // handled per diagnostic, with the upstream key
        }
        if (n.contains("not installed") || n.contains("is not available")
            || n.contains("was not found") || n.contains("not on path")
            || n.contains("no maven installation") || n.contains("no gradle installation")
            || n.contains("no system java compiler") || n.contains("cannot reach")
            || n.contains("returned 404") || n.contains("returned 5")
            || n.contains("no jar published") || n.contains("no version for")
            || n.contains("cannot create") || n.contains("offline")) {
            return IssueKind.ENVIRONMENT;
        }
        if (n.contains("could not be resolved") || n.contains("were not resolved")
            || n.contains("not resolved") || n.contains("could not be started")
            || n.contains("could not run") || n.contains("cannot start")
            || n.contains("did not run") || n.contains("did not compile")
            || n.contains("did not finish") || n.contains("did not answer")
            || n.contains("produced no") || n.contains("no report")
            || n.contains("returned no") || n.contains("reported no")
            || n.contains("missing from this build") || n.contains("was interrupted")
            || n.contains("exited ") || n.contains("failed")
            || n.contains("cannot read the") || n.contains("cannot prepare")) {
            return IssueKind.TOOL_FAILURE;
        }
        return null;
    }

    /** Captures every note that says an analyzer or integration did not do its job. */
    public static void captureNotes(Collection<String> notes) {
        if (notes == null) {
            return;
        }
        for (String note : notes) {
            IssueKind kind = classifyNote(note);
            if (kind != null) {
                record(kind, note, note, null);
            }
        }
    }

    /**
     * Captures the self-report signals a finished check carries: tiers that were enabled but did
     * not run, tool notes about failed integrations, and every diagnostic still on a {@code J9xxx}
     * fallback code (the catalog gaps that make the tool less useful).
     */
    public static void captureReport(CheckReport report) {
        if (report == null) {
            return;
        }
        for (TierReport.Entry e : report.tiers.entries()) {
            if (!e.ran() && e.note().toLowerCase(Locale.ROOT).contains("plugin unavailable")) {
                record(IssueKind.TOOL_FAILURE,
                    Tier.tag(e.tier()) + " (" + Tier.name(e.tier())
                        + ") was enabled but did not run",
                    "the tier line reports: " + e.note(), null);
            }
        }
        captureNotes(report.toolNotes);
        for (Diagnostic d : report.diagnostics) {
            if (!isFallbackCode(d.code)) {
                continue;
            }
            String location = d.primary == null ? "" : d.primary.display() + ": ";
            record(IssueKind.CAPABILITY_GAP,
                "unmapped " + d.source + " diagnostic: " + d.sourceKey,
                location + d.message, null);
        }
    }

    /** True for the {@code J9xxx} codes the catalog hands out when an upstream key is unknown. */
    static boolean isFallbackCode(String code) {
        return code != null && code.length() == 5 && code.startsWith("J9");
    }

    private static String detailOf(Throwable t) {
        if (t == null) {
            return "";
        }
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getName() : message;
    }

    private static String stackOf(Throwable t) {
        if (t == null) {
            return "";
        }
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
