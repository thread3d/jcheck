package jcheck.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jcheck.plugins.MavenResolver;

/**
 * Persists the {@link SourceIndex} between runs, keyed by project root, plus the fingerprint that
 * invalidates it when the compilation inputs change.
 *
 * <p>The index lives under the jcheck cache directory, never in the project: serving a project must
 * not write into it.
 */
public final class IncrementalCache {

    /** A loaded index. */
    public static final class Snapshot {
        public final String fingerprint;
        public final Map<String, SourceIndex.Entry> entries;

        Snapshot(String fingerprint, Map<String, SourceIndex.Entry> entries) {
            this.fingerprint = fingerprint;
            this.entries = Map.copyOf(entries);
        }

        public boolean isEmpty() {
            return entries.isEmpty();
        }
    }

    private IncrementalCache() {
    }

    public static Path dir(Path root) {
        return MavenResolver.defaultCacheDir().resolve("incremental").resolve(fnv(root.toString()));
    }

    public static Path classesDir(Path root) {
        return dir(root).resolve("classes");
    }

    private static Path indexPath(Path root) {
        return dir(root).resolve("index.tsv");
    }

    public static Snapshot load(Path root) {
        Path file = indexPath(root);
        if (!Files.isRegularFile(file)) {
            return new Snapshot("", Map.of());
        }
        Map<String, SourceIndex.Entry> entries = new LinkedHashMap<>();
        String fingerprint = "";
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                if (line.startsWith("fingerprint\t")) {
                    fingerprint = line.substring("fingerprint\t".length());
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length < 6) {
                    continue;
                }
                entries.put(parts[3], new SourceIndex.Entry(
                    parseLong(parts[1]), parseLong(parts[2]), parts[0],
                    split(parts[4]), split(parts[5])));
            }
        } catch (IOException | RuntimeException e) {
            return new Snapshot("", Map.of());
        }
        return new Snapshot(fingerprint, entries);
    }

    public static void save(Path root, String fingerprint, Map<String, SourceIndex.Entry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("fingerprint\t").append(fingerprint).append('\n');
        for (Map.Entry<String, SourceIndex.Entry> e : entries.entrySet()) {
            SourceIndex.Entry entry = e.getValue();
            sb.append(entry.apiHash).append('\t')
              .append(entry.size).append('\t')
              .append(entry.mtime).append('\t')
              .append(e.getKey()).append('\t')
              .append(String.join(",", entry.types)).append('\t')
              .append(String.join(",", entry.references)).append('\n');
        }
        try {
            Files.createDirectories(dir(root));
            Files.writeString(indexPath(root), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // An unwritable cache just means the next run is full.
        }
    }

    /**
     * Everything that invalidates the whole index: the classpath, the compiler flags and the JDK.
     * A change to any of them means a full re-check, exactly as the spec requires.
     */
    public static String globalFingerprint(List<Path> classpath, Integer release, List<String> lints,
                                           List<String> javacExtra) {
        StringBuilder sb = new StringBuilder();
        for (Path p : classpath) {
            sb.append(p).append(';');
        }
        sb.append("|release=").append(release);
        sb.append("|lints=").append(String.join(",", lints));
        sb.append("|extra=").append(String.join(",", javacExtra));
        sb.append("|jdk=").append(Runtime.version().feature());
        return SourceIndex.sha1(sb.toString());
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static java.util.Set<String> split(String joined) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (joined == null || joined.isEmpty()) {
            return out;
        }
        for (String s : joined.split(",")) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static String fnv(String text) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L;
        }
        return Long.toHexString(hash);
    }
}
