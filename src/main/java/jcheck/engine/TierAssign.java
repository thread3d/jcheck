package jcheck.engine;

import jcheck.model.Tier;

/**
 * Assigns an analysis tier to a diagnostic.
 *
 * <p>The catalog pins a tier per row when it can. For unmapped upstream keys, and for tools
 * with no catalog row, this falls back to a key-shape heuristic so that tier gating still
 * works on diagnostics the catalog has never seen.
 */
public final class TierAssign {

    private TierAssign() {
    }

    private static final String[] T0_FRAGMENTS = {
        "expected", "illegal.start.of", "orphaned", "premature.eof", "unclosed.",
        "empty.char.lit", "invalid.", ".number.too.large", "illegal.esc.char",
        "illegal.line.end", "should.be.in.file", "without.if", "without.try",
        "without.catch", "misplaced.", "repeated.modifier", "not.stmt",
    };

    private static final String[] T2_FRAGMENTS = {
        "var.might.not.have.been.initialized", "var.might.already.be.assigned",
        "cant.assign.val.to.final.var", "missing.ret.stmt", "unreachable.stmt",
        "not.exhaustive", "unreported.exception", "call.must.be.first.stmt.in.ctor",
        "cant.ref.before.ctor.called", "divide.by.zero", "possible.loss.of.precision",
    };

    /**
     * @param catalogTier the catalog's tier, or -1 when the row does not pin one
     */
    public static int tierFor(String source, String sourceKey, int catalogTier) {
        if (Tier.isValid(catalogTier)) {
            return catalogTier;
        }
        if (source == null) {
            return Tier.T1;
        }
        switch (source) {
            case "javac":
                return javacTier(sourceKey);
            case "errorprone":
            case "nullaway":
                return Tier.T3;
            case "checker":
                return Tier.T4;
            case "spotbugs":
            case "infer":
                return Tier.T5;
            case "jcheck":
                return Tier.T3;
            default:
                return Tier.T1;
        }
    }

    private static int javacTier(String key) {
        if (key == null) {
            return Tier.T1;
        }
        if (key.startsWith("compiler.warn.") || key.startsWith("compiler.note.")) {
            return Tier.T2;
        }
        for (String f : T0_FRAGMENTS) {
            if (key.contains(f)) {
                return Tier.T0;
            }
        }
        for (String f : T2_FRAGMENTS) {
            if (key.contains(f)) {
                return Tier.T2;
            }
        }
        if (key.startsWith("compiler.err.")) {
            return Tier.T1;
        }
        return Tier.T1;
    }
}
