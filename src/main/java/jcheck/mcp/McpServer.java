package jcheck.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import jcheck.cli.CheckRequest;
import jcheck.cli.Main;
import jcheck.util.Json;
import jcheck.util.JsonWriter;

/**
 * A resources-only MCP server over stdio.
 *
 * <p>Speaks newline-delimited JSON-RPC 2.0. Per the transport spec the server MUST NOT write
 * anything to stdout that is not a valid MCP message, so all logging goes to stderr and
 * {@link #serveStdio} redirects {@code System.out} to stderr to defend the stream against any
 * library that prints.
 *
 * <p>No tools are exposed. The CLI is the tool interface; a second path to the same diagnostics
 * would be the "two modes the agent has to choose between" that SPEC.md warns against.
 */
public final class McpServer {

    /** The newest protocol version this server implements and has been verified against. */
    public static final String LATEST_PROTOCOL = "2025-11-25";

    /** Versions we will echo back, because resource semantics are stable across them. */
    public static final List<String> SUPPORTED_PROTOCOLS =
        List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    public static final String SERVER_NAME = "jcheck";

    public static final String INSTRUCTIONS =
        "jcheck exposes Java diagnostics as resources. Read " + McpResources.CAPABILITIES
        + " first: it states which analysis tiers actually run in this project and where the "
        + "guarantee ends. Read " + McpResources.DIAGNOSTICS + " after edits for the root-cause-first, "
        + "capped diagnostic list (it re-analyses only when the source tree changed). Read "
        + McpResources.DOCS_CODE_TEMPLATE + " or " + McpResources.CATALOG_CODE_TEMPLATE
        + " when a code is unclear, and " + McpResources.SUPPRESSIONS + " to audit suppressions. "
        + "Subscribe to " + McpResources.DIAGNOSTICS + " to be notified when the source tree changes.";

    /** Where a finished message line goes. */
    public interface Sink {
        void send(String jsonLine);
    }

    private final ProjectState state;
    private final Sink sink;
    private final PrintStream log;
    private final long pollMillis;
    private final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
    private final Object writeLock = new Object();

    private volatile Thread watcher;
    private volatile boolean closed;
    private boolean initializeReceived;
    private String negotiatedVersion = LATEST_PROTOCOL;

    public McpServer(ProjectState state, Sink sink, PrintStream log, long pollMillis) {
        this.state = state;
        this.sink = sink;
        this.log = log == null ? System.err : log;
        this.pollMillis = pollMillis <= 0 ? 1000 : pollMillis;
    }

    public ProjectState state() {
        return state;
    }

    public String negotiatedVersion() {
        return negotiatedVersion;
    }

    public Set<String> subscriptions() {
        return subscriptions;
    }

    /**
     * Handles one inbound line.
     *
     * @return the response line, or null when nothing must be sent (any notification, including
     *     unknown notifications, which the spec says MUST NOT be answered)
     */
    public String handleLine(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.strip();
        if (trimmed.isEmpty()) {
            return null;
        }

        Map<String, Object> request;
        try {
            request = Json.parseObject(trimmed);
        } catch (Json.ParseException e) {
            return errorResponse(null, -32700, "Parse error", Map.of("detail", e.getMessage()));
        }

        Object id = request.get("id");
        boolean notification = id == null;
        String method = Json.optString(request, "method");
        String jsonrpc = Json.optString(request, "jsonrpc");
        Map<String, Object> params = Json.optObject(request, "params");

        if (method == null || !"2.0".equals(jsonrpc)) {
            return notification ? null : errorResponse(id, -32600, "Invalid Request", null);
        }

        try {
            switch (method) {
                case "initialize":
                    return notification ? null : initialize(id, params);
                case "notifications/initialized":
                    initializeReceived = true;
                    return null;
                case "notifications/cancelled":
                    return null;
                case "ping":
                    return notification ? null : resultResponse(id, w -> { });
                case "resources/list":
                    return notification ? null : resultResponse(id, this::writeResourceList);
                case "resources/templates/list":
                    return notification ? null : resultResponse(id, this::writeTemplateList);
                case "resources/read":
                    return notification ? null : readResource(id, params);
                case "resources/subscribe":
                    return notification ? null : subscribe(id, params);
                case "resources/unsubscribe":
                    return notification ? null : unsubscribe(id, params);
                case "logging/setLevel":
                    return notification ? null : resultResponse(id, w -> { });
                case "tools/list":
                    // No tools by design. Answering with an empty list keeps probing clients happy
                    // without claiming a tools capability we do not have.
                    return notification ? null : resultResponse(id, w -> {
                        w.name("tools").beginArray().endArray();
                    });
                case "prompts/list":
                    return notification ? null : resultResponse(id, w -> {
                        w.name("prompts").beginArray().endArray();
                    });
                default:
                    return notification ? null
                        : errorResponse(id, -32601, "Method not found", Map.of("method", method));
            }
        } catch (RuntimeException e) {
            log.println("jcheck mcp: internal error handling " + method + ": " + e);
            return notification ? null
                : errorResponse(id, -32603, "Internal error",
                    Map.of("detail", String.valueOf(e.getMessage())));
        }
    }

    // ---- methods -----------------------------------------------------------

    private String initialize(Object id, Map<String, Object> params) {
        String requested = params == null ? null : Json.optString(params, "protocolVersion");
        negotiatedVersion = requested != null && SUPPORTED_PROTOCOLS.contains(requested)
            ? requested : LATEST_PROTOCOL;
        initializeReceived = true;
        return resultResponse(id, w -> {
            w.field("protocolVersion", negotiatedVersion);
            w.name("capabilities").beginObject();
            w.name("resources").beginObject();
            // We implement subscriptions. We do not emit list_changed because the resource list is
            // static, and claiming a capability we do not honour would be worse than omitting it.
            w.field("subscribe", true);
            w.endObject();
            w.endObject();
            w.name("serverInfo").beginObject();
            w.field("name", SERVER_NAME);
            w.field("title", "jcheck");
            w.field("version", Main.VERSION);
            w.field("description", "A Rust-style feedback layer for Java");
            w.endObject();
            w.field("instructions", INSTRUCTIONS);
        });
    }

    private void writeResourceList(JsonWriter w) {
        w.name("resources").beginArray();
        for (McpResources.ResourceDef d : McpResources.list()) {
            w.beginObject();
            w.field("uri", d.uri());
            w.field("name", d.name());
            w.field("title", d.title());
            w.field("description", d.description());
            w.field("mimeType", d.mimeType());
            w.endObject();
        }
        w.endArray();
    }

    private void writeTemplateList(JsonWriter w) {
        w.name("resourceTemplates").beginArray();
        for (McpResources.TemplateDef t : McpResources.templates()) {
            w.beginObject();
            w.field("uriTemplate", t.uriTemplate());
            w.field("name", t.name());
            w.field("title", t.title());
            w.field("description", t.description());
            w.field("mimeType", t.mimeType());
            w.endObject();
        }
        w.endArray();
    }

    private String readResource(Object id, Map<String, Object> params) {
        String uri = params == null ? null : Json.optString(params, "uri");
        if (uri == null || uri.isEmpty()) {
            return errorResponse(id, -32602, "Invalid params", Map.of("detail", "params.uri is required"));
        }
        McpResources.ReadResult r = McpResources.read(state, uri);
        if (!r.found) {
            return errorResponse(id, -32002, "Resource not found",
                Map.of("uri", uri, "detail", r.message == null ? "" : r.message));
        }
        return resultResponse(id, w -> {
            w.name("contents").beginArray();
            w.beginObject();
            w.field("uri", uri);
            w.field("mimeType", r.mimeType);
            w.field("text", r.text);
            w.endObject();
            w.endArray();
        });
    }

    private String subscribe(Object id, Map<String, Object> params) {
        String uri = params == null ? null : Json.optString(params, "uri");
        if (uri == null || uri.isEmpty()) {
            return errorResponse(id, -32602, "Invalid params", Map.of("detail", "params.uri is required"));
        }
        if (!McpResources.isKnownUri(uri)) {
            return errorResponse(id, -32002, "Resource not found", Map.of("uri", uri));
        }
        subscriptions.add(uri);
        startWatcherIfNeeded();
        return resultResponse(id, w -> { });
    }

    private String unsubscribe(Object id, Map<String, Object> params) {
        String uri = params == null ? null : Json.optString(params, "uri");
        if (uri == null || uri.isEmpty()) {
            return errorResponse(id, -32602, "Invalid params", Map.of("detail", "params.uri is required"));
        }
        subscriptions.remove(uri);
        return resultResponse(id, w -> { });
    }

    // ---- change watching ---------------------------------------------------

    private void startWatcherIfNeeded() {
        if (watcher != null || closed) {
            return;
        }
        Thread t = new Thread(this::watchLoop, "jcheck-mcp-watch");
        t.setDaemon(true);
        watcher = t;
        t.start();
    }

    private void watchLoop() {
        String last = state.fingerprint();
        try {
            while (!closed && !subscriptions.isEmpty()) {
                Thread.sleep(Math.max(20, pollMillis));
                String now = state.fingerprint();
                if (now.equals(last)) {
                    continue;
                }
                last = now;
                for (String uri : new ArrayList<>(subscriptions)) {
                    if (McpResources.isSourceDependent(uri)) {
                        emitUpdated(uri);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.println("jcheck mcp: watcher stopped: " + e);
        } finally {
            watcher = null;
        }
    }

    private void emitUpdated(String uri) {
        String line = notification("notifications/resources/updated", w -> w.field("uri", uri));
        synchronized (writeLock) {
            sink.send(line);
        }
    }

    /** Blocks reading lines from {@code in} until end of stream. */
    public void run(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            String response = handleLine(line);
            if (response != null) {
                synchronized (writeLock) {
                    sink.send(response);
                }
            }
        }
        closed = true;
    }

    public void close() {
        closed = true;
        Thread t = watcher;
        if (t != null) {
            t.interrupt();
        }
    }

    public boolean isInitializeReceived() {
        return initializeReceived;
    }

    // ---- stdio entry point -------------------------------------------------

    /**
     * Runs the server on stdin/stdout.
     *
     * <p>{@code System.out} is swapped for a stream that forwards to stderr before anything else
     * happens. The protocol stream is the captured original stdout, so a stray {@code println}
     * anywhere in the engine cannot corrupt a JSON-RPC message.
     */
    public static int serveStdio(Path root, long pollMillis) {
        PrintStream protocolOut = System.out;
        PrintStream err = System.err;
        System.setOut(new PrintStream(err, true));

        CheckRequest template = new CheckRequest().root(root.toString()).format("llm");
        ProjectState state = new ProjectState(root, template);
        McpServer server = new McpServer(state, line -> {
            protocolOut.print(line);
            protocolOut.print('\n');
            protocolOut.flush();
        }, err, pollMillis);

        err.println("jcheck mcp: serving " + state.root() + " (protocol " + LATEST_PROTOCOL + ")");
        try {
            server.run(System.in);
            return 0;
        } catch (IOException e) {
            err.println("jcheck mcp: " + e.getMessage());
            return 2;
        } finally {
            server.close();
        }
    }

    // ---- JSON-RPC framing --------------------------------------------------

    private static String resultResponse(Object id, Consumer<JsonWriter> body) {
        JsonWriter w = new JsonWriter();
        w.beginObject();
        w.field("jsonrpc", "2.0");
        w.name("id");
        writeId(w, id);
        w.name("result");
        w.beginObject();
        body.accept(w);
        w.endObject();
        w.endObject();
        return w.toString();
    }

    private static String errorResponse(Object id, int code, String message, Map<String, Object> data) {
        JsonWriter w = new JsonWriter();
        w.beginObject();
        w.field("jsonrpc", "2.0");
        if (id != null) {
            w.name("id");
            writeId(w, id);
        }
        w.name("error");
        w.beginObject();
        w.field("code", code);
        w.field("message", message);
        if (data != null && !data.isEmpty()) {
            w.name("data");
            w.beginObject();
            for (Map.Entry<String, Object> e : new LinkedHashMap<>(data).entrySet()) {
                w.name(e.getKey());
                writeValue(w, e.getValue());
            }
            w.endObject();
        }
        w.endObject();
        w.endObject();
        return w.toString();
    }

    private static String notification(String method, Consumer<JsonWriter> paramsBody) {
        JsonWriter w = new JsonWriter();
        w.beginObject();
        w.field("jsonrpc", "2.0");
        w.field("method", method);
        w.name("params");
        w.beginObject();
        paramsBody.accept(w);
        w.endObject();
        w.endObject();
        return w.toString();
    }

    private static void writeId(JsonWriter w, Object id) {
        if (id instanceof String) {
            w.value((String) id);
        } else if (id instanceof Long) {
            w.value(((Long) id).longValue());
        } else if (id instanceof Boolean) {
            w.value(((Boolean) id).booleanValue());
        } else {
            w.nullValue();
        }
    }

    private static void writeValue(JsonWriter w, Object v) {
        if (v == null) {
            w.nullValue();
        } else if (v instanceof String) {
            w.value((String) v);
        } else if (v instanceof Long) {
            w.value(((Long) v).longValue());
        } else if (v instanceof Boolean) {
            w.value(((Boolean) v).booleanValue());
        } else {
            w.value(String.valueOf(v));
        }
    }
}
