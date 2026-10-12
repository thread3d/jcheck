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
        this.toolNotes = Collections.unmodifiableList(new ArrayList<>(b.toolNotes));
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
