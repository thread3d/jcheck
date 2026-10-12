package jcheck.render;

import java.util.List;
import jcheck.model.CheckReport;
import jcheck.model.Diagnostic;
import jcheck.model.Edit;
import jcheck.model.Span;
import jcheck.model.Suggestion;

/**
 * Compact renderer shaped for an agent's context window: root cause first (the engine already
 * orders by tier), a hard cap on items, source context with the span marked, and a before/after
 * line pair for every suggestion. No ANSI, no progress, deterministic.
 */
public final class LlmRenderer implements Renderer {

    @Override
    public String format() {
        return "llm";
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
            out.append(omitted).append(" more omitted; raise with --max-diagnostics\n\n");
        }
        for (String note : report.toolNotes) {
            out.append("note: ").append(note).append('\n');
        }
        out.append(report.tiers.detailLine()).append('\n');
        out.append(report.countLine()).append(" 'jcheck explain <code>' for details.\n");
        return out.toString();
    }

    private void renderOne(StringBuilder out, Diagnostic d, SourceCache sources, RenderOptions options) {
        out.append(d.level.wire()).append('[').append(d.code).append("]: ").append(d.message).append('\n');

        Span p = d.primary;
        if (p != null && p.file() != null) {
            out.append("  --> ").append(p.display()).append('\n');
            List<String> fileLines = sources.lines(p.file());
            if (!fileLines.isEmpty()) {
                int from = p.lineStart();
                int first = Math.max(1, from - Math.max(0, options.contextLines));
                for (int ln = first; ln < from; ln++) {
                    out.append("  ").append(sources.line(p.file(), ln)).append('\n');
                }
                String text = sources.line(p.file(), from);
                out.append("  ").append(text).append('\n');
                String carets = HumanRenderer.caretsFor(p, from, text.length());
                out.append("  ").append(carets);
                if (!d.labels.isEmpty() && !d.labels.get(0).text().isEmpty()) {
                    out.append(' ').append(d.labels.get(0).text());
                }
                out.append('\n');
            }
        }
        for (String help : d.help) {
            out.append("  help: ").append(help).append('\n');
        }
        for (String note : d.notes) {
            out.append("  note: ").append(note).append('\n');
        }
        for (Suggestion s : d.suggestions) {
            out.append("  fix (").append(s.applicability().wire()).append("): ").append(s.message()).append('\n');
            for (Edit e : s.edits()) {
                String oldLine = sources.line(e.span().file(), e.span().lineStart());
                String newLine = applyEditToLine(oldLine, e);
                out.append("    - ").append(oldLine).append('\n');
                out.append("    + ").append(newLine).append('\n');
            }
        }
        out.append('\n');
    }

    /** Applies one edit to its line's text, for the before/after pair. */
    public static String applyEditToLine(String line, Edit e) {
        Span s = e.span();
        if (s == null || s.lineStart() != s.lineEnd()) {
            return e.replacement();
        }
        int start = Math.max(0, Math.min(s.colStart() - 1, line.length()));
        int end = Math.max(start, Math.min(s.colEnd() - 1, line.length()));
        return line.substring(0, start) + e.replacement() + line.substring(end);
    }
}
