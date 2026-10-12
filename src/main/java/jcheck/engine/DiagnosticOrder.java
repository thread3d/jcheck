package jcheck.engine;

import java.util.ArrayList;
import java.util.Comparator;
import jcheck.model.Diagnostic;

/**
 * Deterministic diagnostic ordering: tier, then file, line, column, code, message.
 *
 * <p>Same input, same order, always. An agent diffing two runs should see only real changes.
 */
public final class DiagnosticOrder implements Comparator<Diagnostic> {

    public static final DiagnosticOrder INSTANCE = new DiagnosticOrder();

    private DiagnosticOrder() {
    }

    @Override
    public int compare(Diagnostic a, Diagnostic b) {
        int c = Integer.compare(a.tier, b.tier);
        if (c != 0) {
            return c;
        }
        c = file(a).compareTo(file(b));
        if (c != 0) {
            return c;
        }
        c = Integer.compare(line(a), line(b));
        if (c != 0) {
            return c;
        }
        c = Integer.compare(col(a), col(b));
        if (c != 0) {
            return c;
        }
        c = a.code.compareTo(b.code);
        if (c != 0) {
            return c;
        }
        return a.message.compareTo(b.message);
    }

    private static String file(Diagnostic d) {
        return d.primary == null || d.primary.file() == null ? "" : d.primary.file();
    }

    private static int line(Diagnostic d) {
        return d.primary == null ? 0 : d.primary.lineStart();
    }

    private static int col(Diagnostic d) {
        return d.primary == null ? 0 : d.primary.colStart();
    }

    /** Sorts in place and returns the same list for convenience. */
    public static java.util.List<Diagnostic> sort(java.util.List<Diagnostic> in) {
        java.util.List<Diagnostic> copy = new ArrayList<>(in);
        copy.sort(INSTANCE);
        return copy;
    }
}
