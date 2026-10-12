package jcheck.model;

/** Analysis tiers. A lower tier failing means higher tiers were never reached. */
public final class Tier {

    public static final int T0 = 0;
    public static final int T1 = 1;
    public static final int T2 = 2;
    public static final int T3 = 3;
    public static final int T4 = 4;
    public static final int T5 = 5;
    public static final int MAX = T5;

    private static final String[] NAMES = {
        "syntax",
        "symbols and types",
        "flow (definite assignment, reachability, exhaustiveness)",
        "error-prone + nullaway + jcheck-checks",
        "checker-framework",
        "bytecode tools",
    };

    private Tier() {
    }

    public static String name(int tier) {
        if (tier < 0 || tier >= NAMES.length) {
            return "T" + tier;
        }
        return NAMES[tier];
    }

    /** Short label used in the tier line, e.g. {@code T3}. */
    public static String tag(int tier) {
        return "T" + tier;
    }

    public static String describe(int tier) {
        return tag(tier) + " " + name(tier);
    }

    public static boolean isValid(int tier) {
        return tier >= T0 && tier <= MAX;
    }
}
