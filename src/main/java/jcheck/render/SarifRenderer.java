package jcheck.render;

import java.util.Map;
import jcheck.model.CheckReport;
import jcheck.model.Diagnostic;
import jcheck.model.Level;
import jcheck.model.Span;
import jcheck.model.Tier;
import jcheck.util.JsonWriter;

/** SARIF 2.1.0 renderer, for CI and IDEs. */
public final class SarifRenderer implements Renderer {

    private static final String SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";

    @Override
    public String format() {
        return "sarif";
    }

    @Override
    public String render(CheckReport report, RenderOptions options) {
        JsonWriter w = new JsonWriter();
        w.beginObject();
        w.field("$schema", SCHEMA);
        w.field("version", "2.1.0");
        w.name("runs").beginArray();
        w.beginObject();

        w.name("tool").beginObject();
        w.name("driver").beginObject();
        w.field("name", "jcheck");
        w.field("informationUri", "https://github.com/thread3d/jcheck");
        w.field("version", "0.1.0");
        w.name("rules").beginArray();
        for (Map.Entry<String, Diagnostic> e : JsonRenderer.firstByCode(report).entrySet()) {
            Diagnostic d = e.getValue();
            w.beginObject();
            w.field("id", e.getKey());
            w.name("shortDescription").beginObject();
            w.field("text", d.message);
            w.endObject();
            w.name("properties").beginObject();
            w.field("tier", d.tier);
            w.field("source", d.source);
            w.field("source_key", d.sourceKey);
            w.endObject();
            w.endObject();
        }
        w.endArray();
        w.endObject();
        w.endObject();

        w.name("results").beginArray();
        int limit = options.maxDiagnostics > 0 ? options.maxDiagnostics : report.diagnostics.size();
        int shown = 0;
        for (Diagnostic d : report.diagnostics) {
            if (shown >= limit) {
                break;
            }
            writeResult(w, d);
            shown++;
        }
        w.endArray();

        w.name("properties").beginObject();
        w.field("analysis_reached", report.tiers.reached());
        w.field("analysis_reached_name", Tier.name(report.tiers.reached()));
        w.field("errors", report.errorCount());
        w.field("warnings", report.warningCount());
        w.field("dropped_cascades", report.dropped.size());
        w.field("total", report.diagnostics.size());
        w.endObject();

        w.endObject();
        w.endArray();
        w.endObject();
        return w.toString() + "\n";
    }

    private void writeResult(JsonWriter w, Diagnostic d) {
        w.beginObject();
        w.field("ruleId", d.code);
        w.field("level", sarifLevel(d.level));
        w.name("message").beginObject();
        w.field("text", d.message);
        w.endObject();
        Span p = d.primary;
        if (p != null) {
            w.name("locations").beginArray();
            w.beginObject();
            w.name("physicalLocation").beginObject();
            w.name("artifactLocation").beginObject();
            w.optionalField("uri", p.file());
            w.endObject();
            w.name("region").beginObject();
            w.field("startLine", p.lineStart());
            w.field("startColumn", p.colStart());
            w.field("endLine", p.lineEnd());
            w.field("endColumn", p.colEnd());
            w.endObject();
            w.endObject();
            w.endObject();
            w.endArray();
        }
        w.name("properties").beginObject();
        w.field("tier", d.tier);
        w.field("source", d.source);
        w.field("source_key", d.sourceKey);
        w.endObject();
        w.endObject();
    }

    static String sarifLevel(Level level) {
        switch (level) {
            case ERROR:
                return "error";
            case WARNING:
                return "warning";
            case NOTE:
            case HELP:
            default:
                return "note";
        }
    }
}
