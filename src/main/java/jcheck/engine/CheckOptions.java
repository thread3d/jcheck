package jcheck.engine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Everything the engine needs for one check invocation. */
public final class CheckOptions {

    public final List<Path> sources;
    public final List<Path> classpath;
    public final List<Path> processorPath;
    public final Path scratchDir;
    /** {@code --release} value; null means "use the daemon/JDK default". */
    public final Integer release;
    /** {@code -Xlint} groups, e.g. {@code ["all", "-processing", "-serial"]}. */
    public final List<String> lints;
    public final List<String> javacExtra;
    public final boolean nativeChecks;
    public final boolean processAnnotations;
    public final boolean shouldStopAtFlow;
    /** Directory paths are made relative to, for stable output. May be null. */
    public final String rootDir;
    public final int maxErrors;
    public final String profile;
    /** Level policy applied after dedupe and cascade filtering. */
    public final LevelPolicy levelPolicy;
    /** Whether Error Prone / NullAway are attached (T3 alongside the native pack). */
    public final boolean errorProne;
    public final boolean checkerFramework;
    public final boolean spotbugs;
    /** Error Prone version to resolve when no explicit plugin classpath is configured. */
    public final String errorProneVersion;
    /** NullAway version to resolve when no explicit plugin classpath is configured. */
    public final String nullawayVersion;
    /** Explicit plugin jars; an empty list means "resolve from Maven Central". */
    public final List<String> pluginClasspath;
    /** Never reach the network; fail with a note when a plugin jar is not cached. */
    public final boolean pluginOffline;
    /** Repository override; blank means Maven Central. */
    public final String pluginRepo;
    /** {@code nullaway-jspecify} (default) or another mode; controls whether NullAway is attached. */
    public final String nullnessMode;
    /** Packages NullAway checks; empty means "only @NullMarked code". */
    public final List<String> nullnessPackages;
    /** Error Prone checks turned off for this project, by check name. */
    public final List<String> errorProneDisabled;
    /** Analyzer jars already resolved by the caller; empty means the plugin is unavailable. */
    public final List<Path> pluginJars;
    /** Tool-level notes the caller wants rendered alongside the engine's own. */
    public final List<String> toolNotes;

    private CheckOptions(Builder b) {
        this.sources = Collections.unmodifiableList(new ArrayList<>(b.sources));
        this.classpath = Collections.unmodifiableList(new ArrayList<>(b.classpath));
        this.processorPath = Collections.unmodifiableList(new ArrayList<>(b.processorPath));
        this.scratchDir = b.scratchDir;
        this.release = b.release;
        this.lints = Collections.unmodifiableList(new ArrayList<>(b.lints));
        this.javacExtra = Collections.unmodifiableList(new ArrayList<>(b.javacExtra));
        this.nativeChecks = b.nativeChecks;
        this.processAnnotations = b.processAnnotations;
        this.shouldStopAtFlow = b.shouldStopAtFlow;
        this.rootDir = b.rootDir;
        this.maxErrors = b.maxErrors <= 0 ? 10000 : b.maxErrors;
        this.profile = b.profile == null ? "standard" : b.profile;
        this.levelPolicy = b.levelPolicy == null
            ? LevelPolicy.forProfile(this.profile, false)
            : b.levelPolicy;
        this.errorProne = b.errorProne;
        this.checkerFramework = b.checkerFramework;
        this.spotbugs = b.spotbugs;
        this.errorProneVersion = blank(b.errorProneVersion) ? "2.24.1" : b.errorProneVersion;
        this.nullawayVersion = blank(b.nullawayVersion) ? "0.10.18" : b.nullawayVersion;
        this.pluginClasspath = Collections.unmodifiableList(new ArrayList<>(b.pluginClasspath));
        this.pluginOffline = b.pluginOffline;
        this.pluginRepo = b.pluginRepo == null ? "" : b.pluginRepo;
        this.nullnessMode = b.nullnessMode == null ? "nullaway-jspecify" : b.nullnessMode;
        this.nullnessPackages = Collections.unmodifiableList(new ArrayList<>(b.nullnessPackages));
        this.errorProneDisabled = Collections.unmodifiableList(new ArrayList<>(b.errorProneDisabled));
        this.pluginJars = Collections.unmodifiableList(new ArrayList<>(b.pluginJars));
        this.toolNotes = Collections.unmodifiableList(new ArrayList<>(b.toolNotes));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** Whether the given tier was configured to run in this invocation. */
    public boolean tierEnabled(int tier) {
        switch (tier) {
            case 0:
            case 1:
            case 2:
                return true;
            case 3:
                return nativeChecks || errorProne;
            case 4:
                return checkerFramework;
            case 5:
                return spotbugs;
            default:
                return false;
        }
    }

    /** Highest tier this invocation enabled. */
    public int highestEnabledTier() {
        int max = 0;
        for (int t = 0; t <= jcheck.model.Tier.MAX; t++) {
            if (tierEnabled(t)) {
                max = t;
            }
        }
        return max;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<Path> sources = new ArrayList<>();
        private final List<Path> classpath = new ArrayList<>();
        private final List<Path> processorPath = new ArrayList<>();
        private Path scratchDir;
        private Integer release;
        private final List<String> lints = new ArrayList<>(List.of("all", "-processing", "-serial"));
        private final List<String> javacExtra = new ArrayList<>();
        private boolean nativeChecks = true;
        private boolean processAnnotations = false;
        private boolean shouldStopAtFlow = true;
        private String rootDir;
        private int maxErrors = 10000;
        private String profile = "standard";
        private LevelPolicy levelPolicy;
        private boolean errorProne;
        private boolean checkerFramework;
        private boolean spotbugs;
        private String errorProneVersion;
        private String nullawayVersion;
        private final List<String> pluginClasspath = new ArrayList<>();
        private boolean pluginOffline;
        private String pluginRepo;
        private String nullnessMode;
        private final List<String> nullnessPackages = new ArrayList<>();
        private final List<String> errorProneDisabled = new ArrayList<>();
        private final List<Path> pluginJars = new ArrayList<>();
        private final List<String> toolNotes = new ArrayList<>();

        public Builder source(Path p) {
            this.sources.add(p);
            return this;
        }

        public Builder sources(List<Path> p) {
            this.sources.addAll(p);
            return this;
        }

        public Builder classpath(List<Path> p) {
            this.classpath.addAll(p);
            return this;
        }

        public Builder processorPath(List<Path> p) {
            this.processorPath.addAll(p);
            return this;
        }

        public Builder scratchDir(Path p) {
            this.scratchDir = p;
            return this;
        }

        public Builder release(Integer v) {
            this.release = v;
            return this;
        }

        public Builder lints(List<String> v) {
            this.lints.clear();
            this.lints.addAll(v);
            return this;
        }

        public Builder javacExtra(List<String> v) {
            this.javacExtra.addAll(v);
            return this;
        }

        public Builder nativeChecks(boolean v) {
            this.nativeChecks = v;
            return this;
        }

        public Builder processAnnotations(boolean v) {
            this.processAnnotations = v;
            return this;
        }

        public Builder shouldStopAtFlow(boolean v) {
            this.shouldStopAtFlow = v;
            return this;
        }

        public Builder rootDir(String v) {
            this.rootDir = v;
            return this;
        }

        public Builder maxErrors(int v) {
            this.maxErrors = v;
            return this;
        }

        public Builder profile(String v) {
            this.profile = v;
            return this;
        }

        public Builder levelPolicy(LevelPolicy v) {
            this.levelPolicy = v;
            return this;
        }

        public Builder errorProne(boolean v) {
            this.errorProne = v;
            return this;
        }

        public Builder checkerFramework(boolean v) {
            this.checkerFramework = v;
            return this;
        }

        public Builder spotbugs(boolean v) {
            this.spotbugs = v;
            return this;
        }

        public Builder errorProneVersion(String v) {
            this.errorProneVersion = v;
            return this;
        }

        public Builder nullawayVersion(String v) {
            this.nullawayVersion = v;
            return this;
        }

        public Builder pluginClasspath(List<String> v) {
            this.pluginClasspath.clear();
            this.pluginClasspath.addAll(v);
            return this;
        }

        public Builder pluginOffline(boolean v) {
            this.pluginOffline = v;
            return this;
        }

        public Builder pluginRepo(String v) {
            this.pluginRepo = v;
            return this;
        }

        public Builder nullnessMode(String v) {
            this.nullnessMode = v;
            return this;
        }

        public Builder nullnessPackages(List<String> v) {
            this.nullnessPackages.clear();
            this.nullnessPackages.addAll(v);
            return this;
        }

        public Builder errorProneDisabled(List<String> v) {
            this.errorProneDisabled.clear();
            this.errorProneDisabled.addAll(v);
            return this;
        }

        public Builder pluginJars(List<Path> v) {
            this.pluginJars.clear();
            this.pluginJars.addAll(v);
            return this;
        }

        public Builder toolNote(String v) {
            this.toolNotes.add(v);
            return this;
        }

        public Builder toolNotes(List<String> v) {
            this.toolNotes.addAll(v);
            return this;
        }

        public CheckOptions build() {
            return new CheckOptions(this);
        }
    }
}
