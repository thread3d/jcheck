package jcheck.engine;

import java.util.ArrayList;
import java.util.List;

/** A source file's text plus a line-offset index for offset to line/column mapping. */
public final class SourceText {

    private final String content;
    private final int[] lineStarts;

    private SourceText(String content) {
        this.content = content;
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        this.lineStarts = new int[starts.size()];
        for (int i = 0; i < starts.size(); i++) {
            this.lineStarts[i] = starts.get(i);
        }
    }

    public static SourceText of(String content) {
        return new SourceText(content == null ? "" : content);
    }

    public String content() {
        return content;
    }

    public int lineCount() {
        return lineStarts.length;
    }

    /** 1-based line for a 0-based character offset. Clamps out-of-range offsets. */
    public int lineOf(int offset) {
        if (offset < 0) {
            return 1;
        }
        int lo = 0;
        int hi = lineStarts.length - 1;
        int ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lineStarts[mid] <= offset) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans + 1;
    }

    /** 1-based column for a 0-based character offset. */
    public int colOf(int offset) {
        if (offset < 0) {
            return 1;
        }
        int line = lineOf(offset);
        return offset - lineStarts[line - 1] + 1;
    }

    /** Line text without its trailing newline; 1-based. Empty string when out of range. */
    public String line(int lineNo) {
        if (lineNo < 1 || lineNo > lineStarts.length) {
            return "";
        }
        int start = lineStarts[lineNo - 1];
        int end = lineNo < lineStarts.length ? lineStarts[lineNo] : content.length();
        while (end > start) {
            char c = content.charAt(end - 1);
            if (c == '\n' || c == '\r') {
                end--;
            } else {
                break;
            }
        }
        return content.substring(start, end);
    }
}
