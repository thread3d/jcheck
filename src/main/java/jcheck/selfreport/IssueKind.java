package jcheck.selfreport;

/**
 * What kind of trouble jcheck ran into.
 *
 * <p>The kind is the triage axis, not a diagnostic level: it tells a future session whether the
 * entry is a jcheck bug, a missing capability, or just this host's environment, so the backlog can
 * be read without re-deriving each cause from the text.
 */
public enum IssueKind {

    /** jcheck threw, or would have thrown a stack trace at the user. Always a bug. */
    INTERNAL_ERROR("internal-error", "bug",
        "This is a jcheck bug, not a problem in the checked project. Reproduce with "
        + "JCHECK_DEBUG=1, add a regression test under tests/, and fix the throwing path."),

    /** A tier or integration that should have run did not. */
    TOOL_FAILURE("tool-failure", "bug",
        "Something jcheck drives failed. Decide whether the cause is jcheck's own logic (fix it "
        + "and add a test) or the environment (state the boundary in README's Limitations)."),

    /** Upstream data jcheck does not curate or implement yet. */
    CAPABILITY_GAP("capability-gap", "gap",
        "jcheck met something it cannot name yet: add a catalog row and a docs page, or "
        + "implement the missing check. Never hand-assign a J9xxx code."),

    /** The host lacks something optional; jcheck degraded as designed. */
    ENVIRONMENT("environment", "env",
        "The host, not jcheck, is missing this. Either document the boundary in README's "
        + "Limitations or make jcheck degrade more usefully (clearer message, vendored fallback)."),

    /** Logged by hand with {@code jcheck issues add}, usually friction noticed while using it. */
    FRICTION("friction", "note",
        "Recorded by hand. Decide whether to implement it, document it, or close it.");

    private final String wire;
    private final String severity;
    private final String hint;

    IssueKind(String wire, String severity, String hint) {
        this.wire = wire;
        this.severity = severity;
        this.hint = hint;
    }

    /** Stable spelling used in the JSONL log and on the CLI. */
    public String wire() {
        return wire;
    }

    /** {@code bug}, {@code gap}, {@code env} or {@code note}; ordering for the reader. */
    public String severity() {
        return severity;
    }

    /** What a future agent should do about an entry of this kind. */
    public String hint() {
        return hint;
    }

    /** Returns the kind for a wire spelling, defaulting to {@link #FRICTION} for unknown input. */
    public static IssueKind fromWire(String wire) {
        for (IssueKind k : values()) {
            if (k.wire.equals(wire)) {
                return k;
            }
        }
        return FRICTION;
    }

    /** Sort key: bugs before gaps before environment before notes. */
    public int rank() {
        switch (this) {
            case INTERNAL_ERROR:
                return 0;
            case TOOL_FAILURE:
                return 1;
            case CAPABILITY_GAP:
                return 2;
            case ENVIRONMENT:
                return 3;
            case FRICTION:
            default:
                return 4;
        }
    }
}
