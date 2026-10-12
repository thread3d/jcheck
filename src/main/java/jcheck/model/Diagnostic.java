package jcheck.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One normalized diagnostic: the single model every tool's output collapses into.
 *
 * <p>Immutable. Construct through {@link #builder(String)}. Fields are public final and also
 * exposed through same-named accessor methods so callers can read either way.
 */
public final class Diagnostic {

    /** A jcheck-stable code such as {@code J0201}. */
    public final String code;
    /** Originating tool: javac | errorprone | nullaway | checker:&lt;check&gt; | spotbugs | jcheck. */
    public final String source;
    /** Raw upstream id, e.g. {@code compiler.err.not.exhaustive.statement}. */
    public final String sourceKey;
    public final Level level;
    /** 0..5, see {@link Tier}. */
    public final int tier;
    public final String message;
    /** Primary span; may be null when a tool reports no location. */
    public final Span primary;
    public final List<Label> labels;
    public final List<String> notes;
    public final List<String> help;
    public final List<Suggestion> suggestions;
    /** Diagnostics this one absorbed (dedupe siblings, cascade children). */
    public final List<Diagnostic> related;
    /** Original tool text, preserved verbatim. May be null. */
    public final String raw;
    /** Emission order within a run; used for stable ordering and cascade reasoning. */
    public final int seq;

    private Diagnostic(Builder b) {
        this.code = b.code;
        this.source = b.source;
        this.sourceKey = b.sourceKey;
        this.level = b.level;
        this.tier = b.tier;
        this.message = b.message;
        this.primary = b.primary;
        this.labels = Collections.unmodifiableList(new ArrayList<>(b.labels));
        this.notes = Collections.unmodifiableList(new ArrayList<>(b.notes));
        this.help = Collections.unmodifiableList(new ArrayList<>(b.help));
        this.suggestions = Collections.unmodifiableList(new ArrayList<>(b.suggestions));
        this.related = Collections.unmodifiableList(new ArrayList<>(b.related));
        this.raw = b.raw;
        this.seq = b.seq;
    }

    public String code() {
        return code;
    }

    public String source() {
        return source;
    }

    public String sourceKey() {
        return sourceKey;
    }

    public Level level() {
        return level;
    }

    public int tier() {
        return tier;
    }

    public String message() {
        return message;
    }

    public Span primary() {
        return primary;
    }

    public List<Label> labels() {
        return labels;
    }

    public List<String> notes() {
        return notes;
    }

    public List<String> help() {
        return help;
    }

    public List<Suggestion> suggestions() {
        return suggestions;
    }

    public List<Diagnostic> related() {
        return related;
    }

    public String raw() {
        return raw;
    }

    public int seq() {
        return seq;
    }

    public boolean isError() {
        return level == Level.ERROR;
    }

    public boolean isWarning() {
        return level == Level.WARNING;
    }

    /** First machine-applicable suggestion, or null. */
    public Suggestion machineApplicable() {
        for (Suggestion s : suggestions) {
            if (s.isMachineApplicable()) {
                return s;
            }
        }
        return null;
    }

    public static Builder builder(String code) {
        return new Builder(code);
    }

    public Builder toBuilder() {
        Builder b = new Builder(code);
        b.source = source;
        b.sourceKey = sourceKey;
        b.level = level;
        b.tier = tier;
        b.message = message;
        b.primary = primary;
        b.labels.addAll(labels);
        b.notes.addAll(notes);
        b.help.addAll(help);
        b.suggestions.addAll(suggestions);
        b.related.addAll(related);
        b.raw = raw;
        b.seq = seq;
        return b;
    }

    /** Copy with a different level, preserving everything else. */
    public Diagnostic withLevel(Level newLevel) {
        if (newLevel == level) {
            return this;
        }
        return toBuilder().level(newLevel).build();
    }

    /** Copy with extra related diagnostics appended. */
    public Diagnostic withRelated(List<Diagnostic> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        Builder b = toBuilder();
        for (Diagnostic d : extra) {
            b.related(d);
        }
        return b.build();
    }

    @Override
    public String toString() {
        String where = primary == null ? "<no location>" : primary.display();
        return level.wire() + "[" + code + "] " + message + " (" + where + ")";
    }

    public static final class Builder {

        private final String code;
        private String source = "jcheck";
        private String sourceKey = "";
        private Level level = Level.ERROR;
        private int tier = Tier.T0;
        private String message = "";
        private Span primary;
        private final List<Label> labels = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        private final List<String> help = new ArrayList<>();
        private final List<Suggestion> suggestions = new ArrayList<>();
        private final List<Diagnostic> related = new ArrayList<>();
        private String raw;
        private int seq;

        private Builder(String code) {
            this.code = code;
        }

        public Builder source(String v) {
            this.source = v;
            return this;
        }

        public Builder sourceKey(String v) {
            this.sourceKey = v;
            return this;
        }

        public Builder level(Level v) {
            this.level = v;
            return this;
        }

        public Builder tier(int v) {
            this.tier = v;
            return this;
        }

        public Builder message(String v) {
            this.message = v == null ? "" : v;
            return this;
        }

        public Builder primary(Span v) {
            this.primary = v;
            return this;
        }

        public Builder label(Span span, String text) {
            this.labels.add(new Label(span, text));
            return this;
        }

        public Builder label(Label l) {
            this.labels.add(l);
            return this;
        }

        public Builder note(String v) {
            this.notes.add(v);
            return this;
        }

        public Builder help(String v) {
            this.help.add(v);
            return this;
        }

        public Builder suggestion(Suggestion v) {
            this.suggestions.add(v);
            return this;
        }

        public Builder related(Diagnostic v) {
            if (v != null) {
                this.related.add(v);
            }
            return this;
        }

        /** Replaces the label list wholesale. */
        public Builder setLabels(List<Label> v) {
            this.labels.clear();
            this.labels.addAll(v);
            return this;
        }

        /** Replaces the suggestion list wholesale. */
        public Builder setSuggestions(List<Suggestion> v) {
            this.suggestions.clear();
            this.suggestions.addAll(v);
            return this;
        }

        /** Replaces the related list wholesale. */
        public Builder setRelated(List<Diagnostic> v) {
            this.related.clear();
            this.related.addAll(v);
            return this;
        }

        public Builder raw(String v) {
            this.raw = v;
            return this;
        }

        public Builder seq(int v) {
            this.seq = v;
            return this;
        }

        public Diagnostic build() {
            return new Diagnostic(this);
        }
    }
}
