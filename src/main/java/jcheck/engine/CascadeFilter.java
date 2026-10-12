package jcheck.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jcheck.model.Diagnostic;
import jcheck.model.Tier;

/**
 * Removes follow-on errors that only exist because an earlier error left a name or type
 * unresolved.
 *
 * <p>One missing import can produce a dozen "incompatible types" errors that all say the same
 * thing. A lower-tier error is never reported as the cause of a higher-tier one, and this filter
 * only ever drops diagnostics that name an already-unresolved symbol or an erroneous type.
 */
public final class CascadeFilter {

    /** Keys that mean "this name/package could not be resolved". */
    private static final String[] ROOT_FRAGMENTS = {
        "cant.resolve", "doesnt.exist", "does.not.exist", "import.not.found",
        "package.not.visible", "cant.find.symbol", "not.def.access", "not.def.public.cant.access",
    };

    /** Keys that commonly appear only as a consequence of a root error. */
    private static final String[] DEPENDENT_FRAGMENTS = {
        "incompatible.types", "prob.found.req", "cant.apply.symbol", "no.suitable.method.found",
        "no.suitable.abstract.meth.found", "wrong.number.type.args", "wrong.number.type.vars",
        "operator.cant.be.applied", "bad.operand.type", "method.does.not.override.superclass",
        "does.not.override.abstract", "override.meth", "incompatible.diff.ret", "unexpected.type",
        "type.var.cant.be.deref", "non-static.cant.be.ref", "cant.be.applied",
    };

    /**
     * javac's detail lines name the thing it could not resolve:
     * {@code symbol:   class Missing} or {@code package: foo.bar}.
     *
     * <p>Only {@code symbol:} and {@code package:} are read. {@code location:} is deliberately
     * ignored: it says where the failure happened, not what was missing, and treating the
     * enclosing class name as an unresolved symbol would drop unrelated errors that happen to
     * mention that class.
     */
    private static final Pattern NAMED =
        Pattern.compile("^\\s*(?:symbol|package)\\s*:\\s*(.+)$");
    private static final Pattern PACKAGE_ABSENT =
        Pattern.compile("\\bpackage\\s+([A-Za-z_$][\\w.$]*)\\s+does not exist");

    /** Symbol-kind words that prefix the name javac prints, plus common noise. */
    private static final Set<String> IGNORED_TOKENS = Set.of(
        "class", "interface", "variable", "method", "enum", "package", "symbol",
        "annotation", "record", "constructor", "module", "value", "static",
        "abstract", "final", "location", "in", "of", "is", "the", "and", "or");

    public static final class Result {
        public final List<Diagnostic> kept;
        public final List<Diagnostic> dropped;

        Result(List<Diagnostic> kept, List<Diagnostic> dropped) {
            this.kept = kept;
            this.dropped = dropped;
        }
    }

    private CascadeFilter() {
    }

    public static Result apply(List<Diagnostic> in) {
        List<Diagnostic> ordered = new ArrayList<>(in);
        ordered.sort((a, b) -> Integer.compare(a.seq, b.seq));

        Set<String> unresolved = new LinkedHashSet<>();
        int firstRootSeq = Integer.MAX_VALUE;
        boolean anyRoot = false;
        for (Diagnostic d : ordered) {
            if (isRoot(d)) {
                anyRoot = true;
                firstRootSeq = Math.min(firstRootSeq, d.seq);
                unresolved.addAll(namesIn(d));
            }
        }
        if (!anyRoot) {
            return new Result(ordered, List.of());
        }

        List<Diagnostic> kept = new ArrayList<>();
        List<Diagnostic> dropped = new ArrayList<>();
        for (Diagnostic d : ordered) {
            if (isRoot(d) || d.seq < firstRootSeq || d.tier <= Tier.T0) {
                kept.add(d);
                continue;
            }
            if (isDependentKey(d.sourceKey) && refersToUnresolved(d, unresolved)) {
                dropped.add(d);
                continue;
            }
            kept.add(d);
        }
        return new Result(kept, dropped);
    }

    static boolean isRoot(Diagnostic d) {
        if (!"javac".equals(d.source)) {
            return false;
        }
        return containsAny(d.sourceKey, ROOT_FRAGMENTS);
    }

    static boolean isDependentKey(String sourceKey) {
        return sourceKey != null && containsAny(sourceKey, DEPENDENT_FRAGMENTS);
    }

    private static boolean containsAny(String haystack, String[] needles) {
        if (haystack == null) {
            return false;
        }
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /** Symbol names a root diagnostic failed to resolve. */
    static Set<String> namesIn(Diagnostic d) {
        Set<String> out = new LinkedHashSet<>();
        String text = d.raw != null && !d.raw.isEmpty() ? d.raw : d.message;
        if (text == null) {
            return out;
        }
        for (String line : text.split("\n")) {
            Matcher m = NAMED.matcher(line);
            if (m.find()) {
                for (String token : m.group(1).split("\\s+")) {
                    addName(out, token);
                }
            }
            Matcher p = PACKAGE_ABSENT.matcher(line);
            if (p.find()) {
                addName(out, p.group(1));
            }
        }
        return out;
    }

    private static void addName(Set<String> out, String rawToken) {
        String v = rawToken.replaceAll("[^A-Za-z0-9_$.]", "");
        if (v.isEmpty() || IGNORED_TOKENS.contains(v.toLowerCase(java.util.Locale.ROOT))) {
            return;
        }
        out.add(v);
        int dot = v.lastIndexOf('.');
        if (dot > 0) {
            out.add(v.substring(0, dot));
        }
        for (String part : v.split("\\.")) {
            if (!part.isEmpty()
                && !IGNORED_TOKENS.contains(part.toLowerCase(java.util.Locale.ROOT))) {
                out.add(part);
            }
        }
    }

    private static boolean refersToUnresolved(Diagnostic d, Set<String> unresolved) {
        if (unresolved.isEmpty()) {
            return false;
        }
        String text = (d.raw != null && !d.raw.isEmpty() ? d.raw : d.message);
        if (text == null) {
            text = "";
        }
        StringBuilder all = new StringBuilder(text);
        for (String n : d.notes) {
            all.append('\n').append(n);
        }
        String haystack = all.toString();
        if (haystack.contains("erroneous") || haystack.contains("<error>")) {
            return true;
        }
        for (String name : unresolved) {
            if (containsWord(haystack, name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWord(String haystack, String needle) {
        int from = 0;
        while (true) {
            int i = haystack.indexOf(needle, from);
            if (i < 0) {
                return false;
            }
            boolean leftOk = i == 0 || !isIdentChar(haystack.charAt(i - 1));
            int end = i + needle.length();
            boolean rightOk = end >= haystack.length() || !isIdentChar(haystack.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = i + 1;
        }
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.';
    }
}
