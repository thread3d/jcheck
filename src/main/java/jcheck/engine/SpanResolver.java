package jcheck.engine;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.tools.JavaFileObject;
import jcheck.model.Span;

/**
 * Turns a tool's raw character offsets into a {@link Span}, caching source text per file.
 *
 * <p>Falls back to a tool-reported line/column when the source cannot be read, and to a
 * line-1 placeholder when nothing is known.
 */
public final class SpanResolver {

    private final Map<String, SourceText> cache = new HashMap<>();

    /** Source text for a file, reading through the given {@link JavaFileObject} when possible. */
    public SourceText textFor(String file, JavaFileObject source) {
        SourceText cached = cache.get(file);
        if (cached != null) {
            return cached;
        }
        String content = null;
        if (source != null) {
            try {
                CharSequence cs = source.getCharContent(true);
                content = cs == null ? null : cs.toString();
            } catch (IOException | UnsupportedOperationException e) {
                content = null;
            }
        }
        SourceText t = SourceText.of(content);
        cache.put(file, t);
        return t;
    }

    /** Registers source text explicitly (used by tests and the daemon's in-memory mode). */
    public void put(String file, String content) {
        cache.put(file, SourceText.of(content));
    }

    public Span span(String file, JavaFileObject source, long start, long end, long line, long col) {
        if (file == null) {
            return null;
        }
        SourceText t = textFor(file, source);
        String content = t.content();
        if (start >= 0 && !content.isEmpty()) {
            int s = (int) Math.min(start, content.length());
            int e = end < 0 ? s + 1 : (int) Math.min(end, content.length());
            if (e < s) {
                e = s;
            }
            int last = Math.max(s, e - 1);
            int lineStart = t.lineOf(s);
            int colStart = t.colOf(s);
            int lineEnd = t.lineOf(last);
            int colEnd = t.colOf(last) + 1;
            return new Span(file, lineStart, colStart, lineEnd, colEnd);
        }
        if (line > 0) {
            int l = (int) line;
            int c = col > 0 ? (int) col : 1;
            return new Span(file, l, c, l, c + 1);
        }
        return new Span(file, 1, 1, 1, 1);
    }
}
