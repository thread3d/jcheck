package jcheck.model;

/**
 * A half-open source region. Lines and columns are 1-based, inclusive of the start
 * and exclusive of the end column, matching how rustc renders spans.
 */
public record Span(String file, int lineStart, int colStart, int lineEnd, int colEnd) {

    public static Span at(String file, int line, int col) {
        return new Span(file, line, col, line, col);
    }

    public boolean isMultiLine() {
        return lineEnd > lineStart;
    }

    /** True when this span points nowhere useful (unknown position). */
    public boolean isNone() {
        return file == null || lineStart <= 0;
    }

    /** The {@code file:line:col} form used in headers and LLM output. */
    public String display() {
        return file + ":" + lineStart + ":" + colStart;
    }

    @Override
    public String toString() {
        return display();
    }
}
