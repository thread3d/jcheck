package jcheck.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import jcheck.model.Diagnostic;
import jcheck.model.Level;

/**
 * Turns a project's policy into a per-diagnostic level decision.
 *
 * <p>{@code strict} promotes warnings to errors, {@code standard} leaves them alone, and
 * {@code lenient} demotes them to notes so information is never silently lost. Per-code
 * overrides win over the profile, and an explicit allow removes the diagnostic entirely.
 */
public final class LevelPolicy {

    public final String profile;
    /** Code to level. A null value means "allow" (drop). */
    public final Map<String, Level> overrides;
    public final Set<String> allowed;
    public final boolean denyWarnings;

    public LevelPolicy(String profile, Map<String, Level> overrides, Set<String> allowed,
                       boolean denyWarnings) {
        this.profile = profile == null ? "standard" : profile;
        this.overrides = Collections.unmodifiableMap(new LinkedHashMap<>(overrides));
        this.allowed = Collections.unmodifiableSet(new LinkedHashSet<>(allowed));
        this.denyWarnings = denyWarnings;
    }

    public static LevelPolicy forProfile(String profile, boolean denyWarnings) {
        return new LevelPolicy(profile, Map.of(), Set.of(), denyWarnings);
    }

    /** Returns the diagnostic at its effective level, or null when it is allowed (dropped). */
    public Diagnostic apply(Diagnostic d) {
        if (allowed.contains(d.code)) {
            return null;
        }
        if (overrides.containsKey(d.code)) {
            Level l = overrides.get(d.code);
            if (l == null) {
                return null;
            }
            return d.withLevel(l);
        }
        switch (profile) {
            case "strict":
                return d.level == Level.WARNING ? d.withLevel(Level.ERROR) : d;
            case "lenient":
                return d.level == Level.WARNING ? d.withLevel(Level.NOTE) : d;
            case "standard":
            default:
                return denyWarnings && d.level == Level.WARNING ? d.withLevel(Level.ERROR) : d;
        }
    }

    public static Builder builder(String profile) {
        return new Builder(profile);
    }

    public static final class Builder {
        private final String profile;
        private boolean denyWarnings;
        private final Map<String, Level> overrides = new LinkedHashMap<>();
        private final Set<String> allowed = new LinkedHashSet<>();

        private Builder(String profile) {
            this.profile = profile == null ? "standard" : profile;
        }

        public Builder denyWarnings(boolean v) {
            this.denyWarnings = v;
            return this;
        }

        public Builder deny(String code) {
            overrides.put(code, Level.ERROR);
            return this;
        }

        public Builder warn(String code) {
            overrides.put(code, Level.WARNING);
            return this;
        }

        public Builder allow(String code) {
            allowed.add(code);
            return this;
        }

        public LevelPolicy build() {
            return new LevelPolicy(profile, overrides, allowed, denyWarnings);
        }
    }
}
