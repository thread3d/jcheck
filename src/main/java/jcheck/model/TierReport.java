package jcheck.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which analysis tiers actually ran. The point of this type is that a quiet T3 must never be
 * mistaken for a clean T3, so a skipped tier always carries a reason.
 */
public final class TierReport {

    /** One tier's outcome. */
    public static final class Entry {
        public final int tier;
        public final boolean ran;
        public final String note;

        public Entry(int tier, boolean ran, String note) {
            this.tier = tier;
            this.ran = ran;
            this.note = note == null ? "" : note;
        }

        public int tier() {
            return tier;
        }

        public boolean ran() {
            return ran;
        }

        public String note() {
            return note;
        }
    }

    private final List<Entry> entries;
    private final boolean anyErrors;

    public TierReport(List<Entry> entries, boolean anyErrors) {
        List<Entry> copy = new ArrayList<>(entries);
        copy.sort((a, b) -> Integer.compare(a.tier, b.tier));
        this.entries = Collections.unmodifiableList(copy);
        this.anyErrors = anyErrors;
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Highest tier that actually ran. */
    public int reached() {
        int max = Tier.T0;
        for (Entry e : entries) {
            if (e.ran && e.tier > max) {
                max = e.tier;
            }
        }
        return max;
    }

    public boolean ran(int tier) {
        for (Entry e : entries) {
            if (e.tier == tier) {
                return e.ran;
            }
        }
        return false;
    }

    public String note(int tier) {
        for (Entry e : entries) {
            if (e.tier == tier) {
                return e.note;
            }
        }
        return "";
    }

    public boolean anyErrors() {
        return anyErrors;
    }

    /**
     * The one-line statement rendered at the end of a report, e.g.
     * {@code analysis reached T2 of 5; T3-T5 skipped due to compile errors}.
     */
    public String summaryLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("analysis reached ").append(Tier.tag(reached())).append(" of ").append(Tier.MAX);
        List<String> skipped = new ArrayList<>();
        for (Entry e : entries) {
            if (!e.ran && e.tier > reached()) {
                skipped.add(Tier.tag(e.tier));
            }
        }
        if (!skipped.isEmpty()) {
            sb.append("; ");
            if (skipped.size() == 1) {
                sb.append(skipped.get(0));
            } else {
                sb.append(skipped.get(0)).append('-').append(skipped.get(skipped.size() - 1));
            }
            sb.append(" skipped");
            String reason = firstSkipReason();
            if ("compile errors".equals(reason)) {
                sb.append(" due to compile errors");
            } else if (!reason.isEmpty()) {
                sb.append(" (").append(reason).append(")");
            }
        }
        return sb.toString();
    }

    private String firstSkipReason() {
        for (Entry e : entries) {
            if (!e.ran && !e.note.isEmpty()) {
                return e.note;
            }
        }
        return "";
    }

    /** Per-tier detail, one entry per tier, for the LLM tier line. */
    public String detailLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("analysis reached ").append(Tier.tag(reached())).append(" of ").append(Tier.MAX);
        List<String> parts = new ArrayList<>();
        for (Entry e : entries) {
            if (e.tier > reached() || !e.ran) {
                if (!e.ran) {
                    String d = Tier.tag(e.tier) + " " + Tier.name(e.tier) + ": skipped";
                    if (!e.note.isEmpty()) {
                        d += ", " + e.note;
                    }
                    parts.add(d);
                }
            }
        }
        if (!parts.isEmpty()) {
            sb.append(" (").append(String.join("; ", parts)).append(")");
        }
        return sb.toString();
    }
}
