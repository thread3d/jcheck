package jcheck.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides the smallest set of files a check has to recompile, using the API hashes and references
 * from {@link SourceIndex}.
 *
 * <p>The rule is the one incremental Java compilation uses everywhere: a changed file always
 * recompiles; its dependents recompile only when its API changed; and that propagates, because a
 * dependent's own API may change in turn. Anything the planner cannot be sure about — a removed
 * source, a changed classpath, a missing cache or class output — falls back to a full re-check
 * rather than risking a stale clean.
 */
public final class IncrementalPlan {

    /** What to recompile, or that a full check is required. */
    public static final class Plan {
        public final boolean full;
        public final List<Path> recompile;
        public final Path classesDir;
        public final String summary;
        /** The current index, to persist once the compile succeeds. */
        public final Map<String, SourceIndex.Entry> index;
        public final String fingerprint;

        Plan(boolean full, List<Path> recompile, Path classesDir, String summary,
             Map<String, SourceIndex.Entry> index, String fingerprint) {
            this.full = full;
            this.recompile = List.copyOf(recompile);
            this.classesDir = classesDir;
            this.summary = summary;
            this.index = Map.copyOf(index);
            this.fingerprint = fingerprint;
        }
    }

    private IncrementalPlan() {
    }

    /**
     * @param forceFull true when the caller has already decided this run is full but still wants the
     *                  index and fingerprint to persist afterwards
     */
    public static Plan compute(Path root, List<Path> sources, List<Path> classpath, Integer release,
                               List<String> lints, List<String> javacExtra, boolean forceFull) {
        Path classes = IncrementalCache.classesDir(root);
        String fingerprint = IncrementalCache.globalFingerprint(classpath, release, lints, javacExtra);
        Map<String, SourceIndex.Entry> index = SourceIndex.index(sources);
        if (forceFull) {
            return new Plan(true, sources, classes, "full re-check", index, fingerprint);
        }
        if (index.isEmpty()) {
            return new Plan(true, sources, classes, "sources could not be indexed", index, fingerprint);
        }
        IncrementalCache.Snapshot previous = IncrementalCache.load(root);
        if (previous.isEmpty()) {
            return new Plan(true, sources, classes, "no previous index", index, fingerprint);
        }
        if (!fingerprint.equals(previous.fingerprint)) {
            return new Plan(true, sources, classes,
                "classpath, flags or JDK changed", index, fingerprint);
        }
        if (!Files.isDirectory(classes)) {
            return new Plan(true, sources, classes, "no previous class output", index, fingerprint);
        }

        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, SourceIndex.Entry> e : index.entrySet()) {
            SourceIndex.Entry before = previous.entries.get(e.getKey());
            if (before == null || before.size != e.getValue().size
                || before.mtime != e.getValue().mtime) {
                changed.add(e.getKey());
            }
        }
        for (String path : previous.entries.keySet()) {
            if (!index.containsKey(path)) {
                return new Plan(true, sources, classes, "a source was removed", index, fingerprint);
            }
        }
        if (changed.isEmpty()) {
            return new Plan(true, sources, classes, "no source changed", index, fingerprint);
        }

        Map<String, Set<String>> dependents = new HashMap<>();
        for (Map.Entry<String, SourceIndex.Entry> e : index.entrySet()) {
            for (String reference : e.getValue().references) {
                dependents.computeIfAbsent(reference, k -> new LinkedHashSet<>()).add(e.getKey());
            }
        }

        Set<String> affected = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(changed);
        while (!queue.isEmpty()) {
            String file = queue.poll();
            if (!affected.add(file)) {
                continue;
            }
            if (!apiDiffers(file, previous, index)) {
                continue;
            }
            SourceIndex.Entry entry = index.get(file);
            if (entry == null) {
                continue;
            }
            for (String type : entry.types) {
                for (String dependent : dependents.getOrDefault(type, Set.of())) {
                    if (!affected.contains(dependent)) {
                        queue.add(dependent);
                    }
                }
            }
        }

        if (affected.size() >= index.size()) {
            return new Plan(true, sources, classes, "every file is affected", index, fingerprint);
        }
        List<Path> subset = new ArrayList<>();
        for (String file : affected) {
            subset.add(Path.of(file));
        }
        subset.sort(Comparator.comparing(Path::toString));
        int dependentsCount = Math.max(0, subset.size() - changed.size());
        String summary = "incremental: recompiling " + subset.size() + " of " + index.size()
            + " file(s) (" + changed.size() + " changed, " + dependentsCount + " dependent)";
        return new Plan(false, subset, classes, summary, index, fingerprint);
    }

    private static boolean apiDiffers(String file, IncrementalCache.Snapshot previous,
                                      Map<String, SourceIndex.Entry> index) {
        SourceIndex.Entry before = previous.entries.get(file);
        SourceIndex.Entry now = index.get(file);
        return before == null || now == null || !before.apiHash.equals(now.apiHash);
    }
}
