package jcheck.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads source lines for rendering context, caching per file.
 *
 * <p>Paths in diagnostics may already be relative to the project root, so each lookup tries the
 * configured base directory first and the path as given second. An unreadable file yields no
 * context rather than an error: rendering must never fail because a file moved.
 */
public final class SourceCache {

    private final String baseDir;
    private final Map<String, List<String>> cache = new HashMap<>();

    public SourceCache(String baseDir) {
        this.baseDir = baseDir;
    }

    public List<String> lines(String file) {
        if (file == null) {
            return List.of();
        }
        List<String> cached = cache.get(file);
        if (cached != null) {
            return cached;
        }
        List<String> lines = read(file);
        cache.put(file, lines);
        return lines;
    }

    /** 1-based line access; empty string when unavailable. */
    public String line(String file, int lineNo) {
        List<String> lines = lines(file);
        if (lineNo < 1 || lineNo > lines.size()) {
            return "";
        }
        return lines.get(lineNo - 1);
    }

    private List<String> read(String file) {
        for (Path p : candidates(file)) {
            try {
                if (Files.isReadable(p) && !Files.isDirectory(p)) {
                    return List.copyOf(Files.readAllLines(p));
                }
            } catch (IOException e) {
                // try the next candidate
            }
        }
        return List.of();
    }

    private List<Path> candidates(String file) {
        List<Path> out = new ArrayList<>(2);
        Path raw = Path.of(file);
        if (raw.isAbsolute()) {
            out.add(raw);
            return out;
        }
        if (baseDir != null && !baseDir.isEmpty()) {
            out.add(Path.of(baseDir).resolve(raw));
        }
        out.add(raw);
        return out;
    }
}
