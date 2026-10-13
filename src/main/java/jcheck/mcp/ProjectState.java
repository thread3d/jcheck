package jcheck.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import jcheck.build.BuildModel;
import jcheck.build.IncrementalCache;
import jcheck.build.IncrementalPlan;
import jcheck.cli.CheckCommand;
import jcheck.cli.CheckRequest;
import jcheck.config.JcheckConfig;
import jcheck.engine.DiagnosticOrder;
import jcheck.model.CheckReport;
import jcheck.model.Diagnostic;
import jcheck.model.Tier;
import jcheck.model.TierReport;

/**
 * The warm per-session state behind the MCP server.
 *
 * <p>This is the whole reason the MCP server is worth having rather than a shell-out wrapper:
 * the server is long-lived for the agent's editing session, so it holds the resolved build model
 * and the last analysis, and only re-runs javac when the source tree actually changed.
 *
 * <p>Change detection is a fingerprint over source file mtimes and sizes plus source-root
 * directory mtimes — the directory mtimes are what catch files being added or removed without
 * re-walking the tree on every poll.
 */
public final class ProjectState {

    private final Path root;
    private final CheckRequest template;

    private JcheckConfig config;
    private String configError;
    private BuildModel model;
    private CheckReport cachedReport;
    private String cachedFingerprint;
    private String lastError;

    public ProjectState(Path root, CheckRequest template) {
        this.root = root.toAbsolutePath().normalize();
        this.template = template;
        this.template.root = this.root.toString();
        loadConfig();
        this.model = resolveModel();
    }

    public Path root() {
        return root;
    }

    public CheckRequest template() {
        return template;
    }

    public synchronized JcheckConfig config() {
        return config;
    }

    public synchronized String configError() {
        return configError;
    }

    public synchronized BuildModel model() {
        return model;
    }

    public synchronized String lastError() {
        return lastError;
    }

    /** Source-tree fingerprint; changes whenever a source file or source root changes. */
    public synchronized String fingerprint() {
        BuildModel m = model;
        List<String> parts = new ArrayList<>();
        List<Path> roots = new ArrayList<>();
        for (Path src : m.sources) {
            Path parent = src.getParent();
            if (parent != null && !roots.contains(parent)) {
                roots.add(parent);
            }
            parts.add(stat(src));
        }
        for (Path p : roots) {
            parts.add("dir:" + stat(p));
        }
        for (Path extra : List.of(root.resolve("jcheck.toml"))) {
            if (Files.isRegularFile(extra)) {
                parts.add("cfg:" + stat(extra));
            }
        }
        Collections.sort(parts);
        long hash = 0xcbf29ce484222325L;
        for (String p : parts) {
            for (int i = 0; i < p.length(); i++) {
                hash ^= p.charAt(i);
                hash *= 0x100000001b3L;
            }
        }
        return parts.size() + ":" + Long.toHexString(hash);
    }

    private static String stat(Path p) {
        try {
            return p + ":" + Files.getLastModifiedTime(p).toMillis() + ":" + Files.size(p);
        } catch (IOException e) {
            return p + ":missing";
        }
    }

    /** The last analysis, re-run only when the source tree changed. Never null. */
    public synchronized CheckReport report() {
        String fp = fingerprint();
        if (cachedReport != null && fp.equals(cachedFingerprint)) {
            return cachedReport;
        }
        return refresh();
    }

    /**
     * Forces a fresh analysis. Inside a warm session this recompiles only the changed files and
     * their API dependents, merging the fresh findings with the cached ones for everything else;
     * the first run, a changed classpath or flags, a removed source, or an incremental compile that
     * does not come back clean all fall back to a full re-check.
     */
    public synchronized CheckReport refresh() {
        model = resolveModel();
        CheckReport incremental = tryIncremental();
        if (incremental != null) {
            lastError = null;
            cachedReport = incremental;
            cachedFingerprint = fingerprint();
            return cachedReport;
        }
        return fullRefresh();
    }

    private CheckReport fullRefresh() {
        boolean incremental = config.incremental && cacheUsable();
        IncrementalPlan.Plan plan = null;
        CheckRequest request = template.copy();
        if (incremental) {
            plan = IncrementalPlan.compute(root, model.sources, model.classpath, model.release,
                config.lint, config.javacExtra, true);
            Path classes = IncrementalCache.classesDir(root);
            deleteRecursively(classes);
            request.scratchDir = classes;
            request.keepScratch = true;
        }
        CheckCommand.Analysis analysis = CheckCommand.analyze(request);
        if (analysis.ok()) {
            lastError = null;
            cachedReport = analysis.report;
            if (plan != null && !hasJavacError(analysis.report)) {
                IncrementalCache.save(root, plan.fingerprint, plan.index);
            }
        } else {
            lastError = analysis.error == null ? "analysis failed" : analysis.error.strip();
            cachedReport = CheckReport.empty(emptyTiers());
        }
        cachedFingerprint = fingerprint();
        return cachedReport;
    }

    private CheckReport tryIncremental() {
        if (!config.incremental || cachedReport == null || !cacheUsable()) {
            return null;
        }
        IncrementalPlan.Plan plan = IncrementalPlan.compute(root, model.sources, model.classpath,
            model.release, config.lint, config.javacExtra, false);
        if (plan.full || !Files.isDirectory(plan.classesDir)) {
            return null;
        }
        CheckRequest request = template.copy();
        request.sources = new ArrayList<>(plan.recompile);
        List<Path> classpath = new ArrayList<>(template.classpath);
        if (!classpath.contains(plan.classesDir)) {
            classpath.add(plan.classesDir);
        }
        request.classpath = classpath;
        request.scratchDir = plan.classesDir;
        request.keepScratch = true;

        CheckCommand.Analysis analysis = CheckCommand.analyze(request);
        if (!analysis.ok() || hasJavacError(analysis.report)) {
            return null;
        }
        IncrementalCache.save(root, plan.fingerprint, plan.index);
        return merge(cachedReport, analysis.report, plan.recompile);
    }

    /** Fresh findings win; findings for files that were not recompiled are carried over. */
    private CheckReport merge(CheckReport previous, CheckReport fresh, List<Path> recompiled) {
        Set<String> files = new HashSet<>();
        for (Path p : recompiled) {
            files.add(relativize(p));
        }
        List<Diagnostic> merged = new ArrayList<>(fresh.diagnostics);
        for (Diagnostic d : previous.diagnostics) {
            String file = d.primary == null ? null : d.primary.file();
            if (file == null || !files.contains(file)) {
                merged.add(d);
            }
        }
        List<String> notes = new ArrayList<>(previous.toolNotes);
        for (String note : fresh.toolNotes) {
            if (!notes.contains(note)) {
                notes.add(note);
            }
        }
        return new CheckReport(DiagnosticOrder.sort(merged), fresh.dropped, fresh.suppressions,
            fresh.tiers, notes, fresh.configErrors);
    }

    /** Incremental compilation needs a writable cache for the class output; otherwise fall back. */
    private boolean cacheUsable() {
        try {
            Files.createDirectories(IncrementalCache.dir(root));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private String relativize(Path p) {
        try {
            return root.relativize(p.toAbsolutePath().normalize()).toString();
        } catch (IllegalArgumentException e) {
            return p.toString();
        }
    }

    private static boolean hasJavacError(CheckReport report) {
        for (Diagnostic d : report.diagnostics) {
            if (d.isError() && "javac".equals(d.source)) {
                return true;
            }
        }
        return report.configErrors != null && !report.configErrors.isEmpty();
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** The cached report when one already exists, without triggering an analysis. */
    public synchronized CheckReport cachedReportOrNull() {
        return cachedReport;
    }

    private void loadConfig() {
        try {
            config = JcheckConfig.load(root);
            configError = null;
        } catch (RuntimeException e) {
            config = JcheckConfig.defaults();
            configError = e.getMessage();
        }
    }

    private BuildModel resolveModel() {
        try {
            return BuildModel.resolve(root, config, template.sources, template.classpath, null, template.release);
        } catch (RuntimeException e) {
            configError = (configError == null ? "" : configError + "; ") + e.getMessage();
            return BuildModel.resolve(root, JcheckConfig.defaults(), List.of(), List.of(), null, null);
        }
    }

    private TierReport emptyTiers() {
        List<TierReport.Entry> entries = new ArrayList<>();
        for (int t = 0; t <= Tier.MAX; t++) {
            entries.add(new TierReport.Entry(t, false, "analysis did not run"));
        }
        return new TierReport(entries, false);
    }
}
