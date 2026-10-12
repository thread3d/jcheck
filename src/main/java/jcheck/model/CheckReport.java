package jcheck.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Everything one check run produced, ready to render. */
public final class CheckReport {

    /** Final diagnostics: normalized, deduped, cascade-filtered, sorted, level-gated. */
    public final List<Diagnostic> diagnostics;
    /** Diagnostics removed as cascades, kept for {@code --report-suppressions} style auditing. */
    public final List<Diagnostic> dropped;
    /** Suppressions discovered in source, e.g. {@code @SuppressWarnings("NullAway")}. */
    public final List<String> suppressions;
    public final TierReport tiers;
    /** Tool-level notes (daemon restarts, unmarked dependencies, unsupported options). */
    public final List<String> toolNotes;
    /** Non-fatal configuration problems; a non-empty list means exit code 2 territory. */
    public final List<String> configErrors;

    public CheckReport(List<Diagnostic> diagnostics, List<Diagnostic> dropped,
                       List<String> suppressions, TierReport tiers,
                       List<String> toolNotes, List<String> configErrors) {
        this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        this.dropped = Collections.unmodifiableList(new ArrayList<>(dropped));
        this.suppressions = Collections.unmodifiableList(new ArrayList<>(suppressions));
        this.tiers = tiers;
        this.toolNotes = Collections.unmodifiableList(new ArrayList<>(toolNotes));
        this.configErrors = Collections.unmodifiableList(new ArrayList<>(configErrors));
    }

    public int errorCount() {
        int n = 0;
        for (Diagnostic d : diagnostics) {
            if (d.level == Level.ERROR) {
                n++;
            }
        }
        return n;
    }

    public int warningCount() {
        int n = 0;
        for (Diagnostic d : diagnostics) {
            if (d.level == Level.WARNING) {
                n++;
            }
        }
        return n;
    }

    /** {@code jcheck: 2 errors, 4 warnings.} */
    public String countLine() {
        int e = errorCount();
        int w = warningCount();
        return "jcheck: " + e + (e == 1 ? " error" : " errors") + ", "
             + w + (w == 1 ? " warning" : " warnings") + ".";
    }

    public static CheckReport empty(TierReport tiers) {
        return new CheckReport(List.of(), List.of(), List.of(), tiers, List.of(), List.of());
    }
}
