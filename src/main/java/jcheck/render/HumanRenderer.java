package jcheck.render;

import java.util.List;
import jcheck.model.CheckReport;
import jcheck.model.Diagnostic;
import jcheck.model.Label;
import jcheck.model.Span;
import jcheck.model.Suggestion;

/**
 * rustc-style human renderer: a header, a source excerpt with caret underlines and labels,
 * then {@code = note:} / {@code = help:} / {@code = source:} lines.
 */
public final class HumanRenderer implements Renderer {

    @Override
    public String format() {
        return "human";
    }

    @Override
    public String render(CheckReport report, RenderOptions options) {
        StringBuilder out = new StringBuilder();
        SourceCache sources = new SourceCache(options.root);
        List<Diagnostic> diags = report.diagnostics;
        int limit = options.maxDiagnostics > 0 ? options.maxDiagnostics : diags.size();
        int shown = 0;
        for (Diagnostic d : diags) {
            if (shown >= limit) {
                break;
            }
            renderOne(out, d, sources, options);
            shown++;
        }
        int omitted = diags.size() - shown;
        if (omitted > 0) {
            out.append("... ").append(omitted).append(" more omitted\n\n");
        }
        for (String note : report.toolNotes) {
            out.append("note: ").append(note).append('\n');
        }
        if (!report.toolNotes.isEmpty()) {
            out.append('\n');
        }
        out.append(report.tiers.summaryLine()).append('\n');
        out.append(report.countLine()).append('\n');
        return out.toString();
    }

    private void renderOne(StringBuilder out, Diagnostic d, SourceCache sources, RenderOptions options) {
        out.append(d.level.wire()).append('[').append(d.code).append("]: ").append(d.message).append('\n');

        Span p = d.primary;
        boolean hasSpan = p != null && p.file() != null;
        int width = hasSpan ? gutterWidth(p) : 0;
        String label = d.labels.isEmpty() ? "" : d.labels.get(0).text();

        if (hasSpan) {
            out.append(spaces(width)).append("--> ").append(p.display()).append('\n');
            if (!sources.lines(p.file()).isEmpty()) {
                renderSource(out, p, label, sources, width);
            } else if (!label.isEmpty()) {
                out.append(gutter(width)).append("^ ").append(label).append('\n');
            }
        }

        for (int i = 1; i < d.labels.size(); i++) {
            Label l = d.labels.get(i);
            String where = l.span() == null ? "" : " (" + l.span().display() + ")";
            out.append(annotation(width)).append("note: ").append(l.text()).append(where).append('\n');
        }
        for (String note : d.notes) {
            out.append(annotation(width)).append("note: ").append(note).append('\n');
        }
        for (String help : d.help) {
            out.append(annotation(width)).append("help: ").append(help).append('\n');
        }
        for (Suggestion s : d.suggestions) {
            out.append(annotation(width))
               .append("suggestion: ").append(s.message())
               .append(" [").append(s.applicability().wire()).append("]").append('\n');
        }
        out.append(annotation(width))
           .append("source: ").append(d.source);
        if (d.sourceKey != null && !d.sourceKey.isEmpty()) {
            out.append(" (").append(d.sourceKey).append(')');
        }
        out.append('\n');
        out.append('\n');
    }

    /**
     * Renders the first line of the span with caret underlines.
     *
     * <p>Only the first line is shown: javac often spans a whole method body (a missing return
     * statement covers every line of the method), and underlining all of it buries the one line
     * that matters. The LLM renderer makes the same choice, so both formats agree.
     */
    private void renderSource(StringBuilder out, Span p, String label, SourceCache sources, int width) {
        int from = p.lineStart();
        String text = sources.line(p.file(), from);
        out.append(pad(from, width)).append(" | ").append(text).append('\n');
        out.append(gutter(width)).append(caretsFor(p, from, text.length()));
        if (label != null && !label.isEmpty()) {
            out.append(' ').append(label);
        }
        out.append('\n');
    }

    public static String caretsFor(Span p, int ln, int lineLen) {
        int start;
        int end;
        if (!p.isMultiLine()) {
            start = p.colStart();
            end = Math.max(p.colEnd(), start + 1);
        } else if (ln == p.lineStart()) {
            start = p.colStart();
            end = Math.max(lineLen + 1, start + 1);
        } else if (ln == p.lineEnd()) {
            start = 1;
            end = Math.max(p.colEnd(), 2);
        } else {
            start = 1;
            end = Math.max(lineLen + 1, 2);
        }
        if (start < 1) {
            start = 1;
        }
        if (end <= start) {
            end = start + 1;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(spaces(start - 1));
        for (int i = start; i < end; i++) {
            sb.append('^');
        }
        return sb.toString();
    }

    public static int gutterWidth(Span p) {
        int maxLine = Math.max(p.lineStart(), p.lineEnd());
        int digits = String.valueOf(Math.max(maxLine, 1)).length();
        return Math.max(2, digits);
    }

    static String gutter(int width) {
        return spaces(width + 1) + "| ";
    }

    static String annotation(int width) {
        return spaces(width + 1) + "= ";
    }

    static String pad(int lineNo, int width) {
        String s = String.valueOf(lineNo);
        return spaces(Math.max(0, width - s.length())) + s;
    }

    static String spaces(int n) {
        if (n <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
