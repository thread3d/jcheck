package jcheck.render;

import java.util.LinkedHashMap;
import java.util.Map;
import jcheck.model.CheckReport;
import jcheck.model.Diagnostic;
import jcheck.model.Edit;
import jcheck.model.Label;
import jcheck.model.Span;
import jcheck.model.Suggestion;
import jcheck.model.Tier;
import jcheck.model.TierReport;
import jcheck.util.JsonWriter;

/**
 * JSON Lines renderer: one diagnostic object per line, then a final {@code summary} line.
 * The schema is versioned so consumers can pin against it.
 */
public final class JsonRenderer implements Renderer {

    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_RELATED_DEPTH = 3;

    @Override
    public String format() {
        return "json";
    }

    @Override
    public String render(CheckReport report, RenderOptions options) {
        StringBuilder out = new StringBuilder();
        int limit = options.maxDiagnostics > 0 ? options.maxDiagnostics : report.diagnostics.size();
        int shown = 0;
        for (Diagnostic d : report.diagnostics) {
            if (shown >= limit) {
                break;
            }
            JsonWriter w = new JsonWriter();
            write(w, d, 0);
            out.append(w).append('\n');
            shown++;
        }
        JsonWriter summary = new JsonWriter();
        writeSummary(summary, report, shown);
        out.append(summary).append('\n');
        return out.toString();
    }

    private void write(JsonWriter w, Diagnostic d, int depth) {
        w.beginObject();
        w.field("code", d.code);
        w.field("source", d.source);
        w.field("source_key", d.sourceKey);
        w.field("level", d.level.wire());
        w.field("tier", d.tier);
        w.field("message", d.message);
        w.name("primary");
        writeSpan(w, d.primary);

        w.name("labels").beginArray();
        for (Label l : d.labels) {
            w.beginObject();
            w.name("span");
            writeSpan(w, l.span());
            w.field("text", l.text());
            w.endObject();
        }
        w.endArray();

        w.name("notes").beginArray();
        for (String n : d.notes) {
            w.value(n);
        }
        w.endArray();

        w.name("help").beginArray();
        for (String h : d.help) {
            w.value(h);
        }
        w.endArray();

        w.name("suggestions").beginArray();
        for (Suggestion s : d.suggestions) {
            w.beginObject();
            w.field("applicability", s.applicability().wire());
            w.field("message", s.message());
            w.name("edits").beginArray();
            for (Edit e : s.edits()) {
                w.beginObject();
                w.name("span");
                writeSpan(w, e.span());
                w.field("replacement", e.replacement());
                w.endObject();
            }
            w.endArray();
            w.endObject();
        }
        w.endArray();

        if (!d.related.isEmpty()) {
            w.name("related").beginArray();
            for (Diagnostic r : d.related) {
                if (depth >= MAX_RELATED_DEPTH) {
                    w.beginObject();
                    w.field("code", r.code);
                    w.field("message", r.message);
                    w.field("truncated", true);
                    w.endObject();
                } else {
                    write(w, r, depth + 1);
                }
            }
            w.endArray();
        }

        w.optionalField("raw", d.raw);
        w.endObject();
    }

    private void writeSpan(JsonWriter w, Span s) {
        if (s == null) {
            w.nullValue();
            return;
        }
        w.beginObject();
        w.optionalField("file", s.file());
        w.field("line_start", s.lineStart());
        w.field("col_start", s.colStart());
        w.field("line_end", s.lineEnd());
        w.field("col_end", s.colEnd());
        w.endObject();
    }

    private void writeSummary(JsonWriter w, CheckReport report, int shown) {
        w.beginObject();
        w.name("summary").beginObject();
        w.field("schema_version", SCHEMA_VERSION);
        w.field("errors", report.errorCount());
        w.field("warnings", report.warningCount());
        w.field("shown", shown);
        w.field("total", report.diagnostics.size());
        w.field("dropped_cascades", report.dropped.size());
        w.field("analysis_reached", report.tiers.reached());
        w.field("analysis_reached_name", Tier.name(report.tiers.reached()));
        w.name("tiers").beginArray();
        for (TierReport.Entry e : report.tiers.entries()) {
            w.beginObject();
            w.field("tier", e.tier());
            w.field("ran", e.ran());
            w.field("note", e.note());
            w.endObject();
        }
        w.endArray();
        w.name("suppressions").beginArray();
        for (String s : report.suppressions) {
            w.value(s);
        }
        w.endArray();
        w.name("tool_notes").beginArray();
        for (String s : report.toolNotes) {
            w.value(s);
        }
        w.endArray();
        w.name("config_errors").beginArray();
        for (String s : report.configErrors) {
            w.value(s);
        }
        w.endArray();
        w.endObject();
        w.endObject();
    }

    /** Codes in first-seen order, for SARIF rule tables. */
    static Map<String, Diagnostic> firstByCode(CheckReport report) {
        Map<String, Diagnostic> map = new LinkedHashMap<>();
        for (Diagnostic d : report.diagnostics) {
            map.putIfAbsent(d.code, d);
        }
        return map;
    }
}
