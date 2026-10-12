package jcheck.render;

/** Knobs every renderer honours. */
public final class RenderOptions {

    public final String format;
    /** Cap on rendered diagnostics; 0 or negative means unlimited. */
    public final int maxDiagnostics;
    public final int contextLines;
    public final boolean color;
    /** Base directory used to relativize file paths; may be null. */
    public final String root;

    public RenderOptions(String format, int maxDiagnostics, int contextLines, boolean color, String root) {
        this.format = format == null ? "human" : format;
        this.maxDiagnostics = maxDiagnostics;
        this.contextLines = contextLines;
        this.color = color;
        this.root = root;
    }

    public static RenderOptions defaults() {
        return new RenderOptions("human", 0, 2, false, null);
    }

    public static RenderOptions forFormat(String format) {
        if ("llm".equals(format)) {
            return new RenderOptions("llm", 15, 2, false, null);
        }
        return new RenderOptions(format, 0, 2, false, null);
    }

    public RenderOptions withRoot(String v) {
        return new RenderOptions(format, maxDiagnostics, contextLines, color, v);
    }

    public RenderOptions withMaxDiagnostics(int v) {
        return new RenderOptions(format, v, contextLines, color, root);
    }

    public RenderOptions withContextLines(int v) {
        return new RenderOptions(format, maxDiagnostics, v, color, root);
    }

    public RenderOptions withColor(boolean v) {
        return new RenderOptions(format, maxDiagnostics, contextLines, v, root);
    }
}
