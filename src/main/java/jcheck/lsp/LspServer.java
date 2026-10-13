package jcheck.lsp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import jcheck.cli.CheckRequest;
import jcheck.cli.Main;
import jcheck.mcp.ProjectState;
import jcheck.model.CheckReport;
import jcheck.model.Diagnostic;
import jcheck.model.Edit;
import jcheck.model.Level;
import jcheck.model.Span;
import jcheck.model.Suggestion;
import jcheck.util.Json;
import jcheck.util.JsonWriter;

/**
 * {@code jcheck lsp}: a Language Server Protocol bridge over stdio.
 *
 * <p>It is the same idea as the MCP server — one long-lived process holding the resolved build
 * model and the last analysis — but speaks LSP so an editor gets diagnostics as it edits. On open,
 * change and save it re-runs the analysis (through {@link ProjectState}'s fingerprint, so an
 * unchanged tree costs nothing) and publishes diagnostics per file; it also answers
 * {@code textDocument/codeAction} with the machine-applicable suggestions as quick fixes.
 *
 * <p>Framing is LSP's {@code Content-Length} headers over JSON-RPC 2.0, not MCP's newline-delimited
 * transport, and nothing but protocol messages reaches stdout.
 */
public final class LspServer {

    private final Path root;
    private final ProjectState state;
    private final InputStream in;
    private final OutputStream out;
    private final Set<String> publishedFiles = new LinkedHashSet<>();
    private boolean shutdownRequested;

    LspServer(Path root, InputStream in, OutputStream out) {
        this.root = root.toAbsolutePath().normalize();
        CheckRequest template = new CheckRequest();
        template.root = this.root.toString();
        this.state = new ProjectState(this.root, template);
        this.in = in;
        this.out = out;
    }

    /** Serves stdio until {@code exit}. */
    public static int serveStdio(Path root) {
        try {
            new LspServer(root, System.in, System.out).serve();
            return 0;
        } catch (IOException e) {
            System.err.println("jcheck lsp: " + e.getMessage());
            return 2;
        }
    }

    /** Serves one already-open pair of streams; the seam in-process tests use. */
    public static int serve(Path root, InputStream in, OutputStream out) throws IOException {
        new LspServer(root, in, out).serve();
        return 0;
    }

    private void serve() throws IOException {
        String body;
        while ((body = readMessage(in)) != null) {
            Map<String, Object> message;
            try {
                message = Json.parseObject(body);
            } catch (RuntimeException e) {
                continue;
            }
            if (handle(message)) {
                return;
            }
        }
    }

    /** Returns true when the server should stop. */
    private boolean handle(Map<String, Object> message) throws IOException {
        String method = Json.optString(message, "method");
        Object id = message.get("id");
        Map<String, Object> params = Json.asObject(message.get("params"));
        if (method == null) {
            return false;
        }
        switch (method) {
            case "initialize":
                respond(id, result -> result.beginObject()
                    .name("capabilities").beginObject()
                        .name("textDocumentSync").beginObject()
                            .field("openClose", true)
                            .field("change", 1)
                            .name("save").beginObject().field("includeText", false).endObject()
                        .endObject()
                        .field("codeActionProvider", true)
                    .endObject()
                    .name("serverInfo").beginObject()
                        .field("name", "jcheck")
                        .field("version", Main.VERSION)
                    .endObject()
                    .endObject());
                return false;
            case "initialized":
                return false;
            case "shutdown":
                shutdownRequested = true;
                respond(id, result -> result.nullValue());
                return false;
            case "exit":
                return true;
            case "textDocument/didOpen":
            case "textDocument/didChange":
            case "textDocument/didSave":
                publishAll();
                return false;
            case "textDocument/didClose":
                clear(uriOf(params));
                return false;
            case "textDocument/codeAction":
                respond(id, result -> writeCodeActions(result, params));
                return false;
            default:
                if (id != null) {
                    error(id, -32601, "method not found: " + method);
                }
                return false;
        }
    }

    // ---- diagnostics -------------------------------------------------------

    private void publishAll() throws IOException {
        if (shutdownRequested) {
            return;
        }
        CheckReport report = state.report();
        Map<String, List<Diagnostic>> byFile = new LinkedHashMap<>();
        for (Diagnostic d : report.diagnostics) {
            String uri = uriOf(d.primary == null ? null : d.primary.file());
            if (uri == null) {
                continue;
            }
            byFile.computeIfAbsent(uri, k -> new ArrayList<>()).add(d);
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, List<Diagnostic>> entry : byFile.entrySet()) {
            seen.add(entry.getKey());
            publish(entry.getKey(), entry.getValue());
        }
        // Clear files that had diagnostics last time and none now, so stale squiggles disappear.
        for (String previous : new ArrayList<>(publishedFiles)) {
            if (!seen.contains(previous)) {
                publish(previous, List.of());
            }
        }
    }

    private void publish(String uri, List<Diagnostic> diagnostics) throws IOException {
        JsonWriter w = new JsonWriter();
        w.beginObject().field("jsonrpc", "2.0")
            .field("method", "textDocument/publishDiagnostics")
            .name("params").beginObject()
                .field("uri", uri)
                .name("diagnostics").beginArray();
        for (Diagnostic d : diagnostics) {
            writeDiagnostic(w, d);
        }
        w.endArray().endObject().endObject();
        writeMessage(out, w.toString());
        if (diagnostics.isEmpty()) {
            publishedFiles.remove(uri);
        } else {
            publishedFiles.add(uri);
        }
    }

    private void writeDiagnostic(JsonWriter w, Diagnostic d) {
        w.beginObject();
        w.name("range");
        writeRange(w, d.primary);
        w.field("severity", severity(d.level));
        w.field("code", d.code);
        w.field("source", "jcheck");
        w.field("message", messageOf(d));
        w.name("data").beginObject().field("code", d.code).field("tool", d.source).endObject();
        w.endObject();
    }

    /** The message plus its help lines, which LSP clients render as a code-block-ish tooltip. */
    private static String messageOf(Diagnostic d) {
        StringBuilder sb = new StringBuilder(d.message);
        for (String help : d.help) {
            sb.append('\n').append("help: ").append(help);
        }
        for (String note : d.notes) {
            sb.append('\n').append("note: ").append(note);
        }
        return sb.toString();
    }

    private static int severity(Level level) {
        switch (level) {
            case ERROR:
                return 1;
            case WARNING:
                return 2;
            case NOTE:
                return 3;
            default:
                return 4;
        }
    }

    // ---- code actions ------------------------------------------------------

    private void writeCodeActions(JsonWriter w, Map<String, Object> params) {
        String uri = uriOf(params);
        w.beginArray();
        if (uri == null) {
            w.endArray();
            return;
        }
        CheckReport report = state.report();
        for (Diagnostic d : report.diagnostics) {
            if (d.suggestions.isEmpty() || !uri.equals(uriOf(d.primary == null ? null : d.primary.file()))) {
                continue;
            }
            for (Suggestion s : d.suggestions) {
                if (s.edits().isEmpty()) {
                    continue;
                }
                w.beginObject()
                    .field("title", s.message() + " (" + d.code + ")")
                    .field("kind", "quickfix")
                    .name("edit").beginObject()
                        .name("changes").beginObject()
                            .name(uri).beginArray();
                for (Edit e : s.edits()) {
                    w.beginObject();
                    w.name("range");
                    writeRange(w, e.span());
                    w.field("newText", e.replacement());
                    w.endObject();
                }
                w.endArray().endObject().endObject().endObject();
            }
        }
        w.endArray();
    }

    private void clear(String uri) throws IOException {
        if (uri != null && publishedFiles.contains(uri)) {
            publish(uri, List.of());
        }
    }

    // ---- helpers -----------------------------------------------------------

    private void writeRange(JsonWriter w, Span span) {
        int line = span == null ? 0 : Math.max(0, span.lineStart() - 1);
        int col = span == null ? 0 : Math.max(0, span.colStart() - 1);
        int endLine = span == null ? line : Math.max(0, span.lineEnd() - 1);
        int endCol = span == null ? col : Math.max(0, span.colEnd() - 1);
        w.beginObject()
            .name("start").beginObject().field("line", line).field("character", col).endObject()
            .name("end").beginObject().field("line", endLine).field("character", endCol).endObject()
            .endObject();
    }

    private String uriOf(Map<String, Object> params) {
        Map<String, Object> doc = params == null ? null : Json.asObject(params.get("textDocument"));
        String uri = doc == null ? null : Json.optString(doc, "uri");
        return uri;
    }

    private String uriOf(String file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        Path p = Path.of(file);
        return (p.isAbsolute() ? p : root.resolve(p)).normalize().toUri().toString();
    }

    private void respond(Object id, Consumer<JsonWriter> result) throws IOException {
        JsonWriter w = new JsonWriter();
        w.beginObject().field("jsonrpc", "2.0");
        writeId(w, id);
        w.name("result");
        result.accept(w);
        w.endObject();
        writeMessage(out, w.toString());
    }

    private void error(Object id, int code, String message) throws IOException {
        JsonWriter w = new JsonWriter();
        w.beginObject().field("jsonrpc", "2.0");
        writeId(w, id);
        w.name("error").beginObject().field("code", code).field("message", message).endObject();
        w.endObject();
        writeMessage(out, w.toString());
    }

    private static void writeId(JsonWriter w, Object id) {
        w.name("id");
        if (id instanceof Number) {
            w.value(((Number) id).longValue());
        } else if (id instanceof String) {
            w.value((String) id);
        } else {
            w.nullValue();
        }
    }

    static String readMessage(InputStream in) throws IOException {
        int contentLength = -1;
        StringBuilder header = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            header.append((char) b);
            if (header.length() >= 4
                && header.charAt(header.length() - 4) == '\r'
                && header.charAt(header.length() - 3) == '\n'
                && header.charAt(header.length() - 2) == '\r'
                && header.charAt(header.length() - 1) == '\n') {
                break;
            }
        }
        for (String line : header.toString().split("\r\n")) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length:")) {
                try {
                    contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
                } catch (NumberFormatException e) {
                    contentLength = -1;
                }
            }
        }
        if (contentLength < 0) {
            return null;
        }
        byte[] body = in.readNBytes(contentLength);
        return new String(body, StandardCharsets.UTF_8);
    }

    static void writeMessage(OutputStream out, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }
}
