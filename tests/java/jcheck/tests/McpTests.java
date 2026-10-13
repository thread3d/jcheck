package jcheck.tests;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import jcheck.cli.CheckRequest;
import jcheck.mcp.McpResources;
import jcheck.mcp.McpServer;
import jcheck.mcp.ProjectState;
import jcheck.util.Json;

import static jcheck.tests.TestMain.check;
import static jcheck.tests.TestMain.contains;
import static jcheck.tests.TestMain.eq;
import static jcheck.tests.TestMain.notContains;

/** Tests for the MCP server: JSON parsing, protocol conformance, resources, and a real client. */
final class McpTests {

    private static final String CART_SOURCE = String.join("\n",
        "package demo;",
        "",
        "import java.util.List;",
        "",
        "public final class Cart {",
        "    @SuppressWarnings(\"unchecked\")",
        "    public static int total(List<Integer> parts) {",
        "        String label = \"total\";",
        "        int sum = 0;",
        "        for (Integer part : parts) {",
        "            sum += part;",
        "        }",
        "        return sum + label.length();",
        "    }",
        "}",
        "");

    private McpTests() {
    }

    static void run(Path repoRoot) throws Exception {
        jsonParserTests();
        Path project = tempProject();
        try {
            ProjectState state = new ProjectState(project,
                new CheckRequest().root(project.toString()).format("llm"));
            protocolTests(state);
            resourceTests(state);
            cachingTests(project, state);
        } finally {
            UnitTests.deleteRecursively(project);
        }
        edgeCaseTests();
        endToEndTests();
    }

    // ---- json parser -------------------------------------------------------

    private static void jsonParserTests() {
        Map<String, Object> obj = Json.parseObject("{\"a\":1,\"b\":[true,null,\"x\"],\"c\":{\"d\":2.5}}");
        eq("json:int-is-long", Long.valueOf(1), obj.get("a"));
        eq("json:float-is-double", Double.valueOf(2.5),
            Json.asObject(obj.get("c")).get("d"));
        List<Object> b = Json.asArray(obj.get("b"));
        eq("json:array-size", 3, b.size());
        eq("json:true", Boolean.TRUE, b.get(0));
        eq("json:null", null, b.get(1));
        eq("json:string", "x", b.get(2));

        eq("json:escapes", "a\"b\\c\nd\te", Json.parse("\"a\\\"b\\\\c\\nd\\te\""));
        eq("json:unicode-escape", "A", Json.parse("\"\\u0041\""));
        eq("json:slash-escape", "/", Json.parse("\"\\/\""));
        eq("json:whitespace", Long.valueOf(7), Json.parse("  \n\t 7 \r\n "));
        eq("json:empty-object", 0, Json.parseObject("{}").size());
        eq("json:empty-array", 0, Json.asArray(Json.parse("[]")).size());
        eq("json:negative", Long.valueOf(-42), Json.parse("-42"));
        eq("json:exponent", Double.valueOf(1500.0), Json.parse("1.5e3"));

        String[] bad = {
            "", "{", "}", "[1,]", "{\"a\" 1}", "{'a':1}", "nul", "tru",
            "1 2", "\"unterminated", "\"\\q\"", "garbage here", "{\"a\":}", "[,1]",
        };
        for (String input : bad) {
            try {
                Json.parse(input);
                check("json:rejects:" + input, false, "expected a ParseException");
            } catch (Json.ParseException e) {
                check("json:rejects:" + input, true, null);
                check("json:offset-set:" + input, e.offset >= 0, "offset=" + e.offset);
            }
        }

        try {
            Json.parse("garbage here");
        } catch (Json.ParseException e) {
            contains("json:error-is-diagnosable", e.getMessage(), "garbage here");
        }

        // A raw control character inside a string is not valid JSON.
        try {
            Json.parse("\"a\u0001b\"");
            check("json:rejects-raw-control", false, "expected a ParseException");
        } catch (Json.ParseException e) {
            check("json:rejects-raw-control", true, null);
        }
    }

    // ---- protocol conformance ----------------------------------------------

    private static void protocolTests(ProjectState state) {
        McpServer server = newServer(state);

        Map<String, Object> init = request(server, 1, "initialize",
            "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}");
        Map<String, Object> initResult = result(init);
        eq("mcp:negotiates-requested-version", "2025-06-18", initResult.get("protocolVersion"));
        Map<String, Object> caps = Json.asObject(initResult.get("capabilities"));
        eq("mcp:declares-resources", Boolean.TRUE,
            Json.asObject(caps.get("resources")).get("subscribe"));
        eq("mcp:server-name", "jcheck", Json.asObject(initResult.get("serverInfo")).get("name"));
        check("mcp:instructions-present",
            initResult.get("instructions") instanceof String
                && ((String) initResult.get("instructions")).contains("jcheck://capabilities"),
            "instructions must point at the capabilities resource");
        check("mcp:does-not-declare-list-changed",
            !Json.asObject(caps.get("resources")).containsKey("listChanged"),
            "list is static, so listChanged must not be claimed");

        McpServer latest = newServer(state);
        eq("mcp:unknown-version-falls-back", McpServer.LATEST_PROTOCOL,
            result(request(latest, 1, "initialize",
                "{\"protocolVersion\":\"1.0.0\"}")).get("protocolVersion"));
        for (String version : McpServer.SUPPORTED_PROTOCOLS) {
            McpServer s = newServer(state);
            eq("mcp:echoes-" + version, version,
                result(request(s, 1, "initialize", "{\"protocolVersion\":\"" + version + "\"}"))
                    .get("protocolVersion"));
        }

        // Notifications MUST NOT be answered, including unknown ones.
        eq("mcp:initialized-notification-silent", null,
            server.handleLine("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
        eq("mcp:unknown-notification-silent", null,
            server.handleLine("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/nonsense\"}"));
        eq("mcp:read-without-id-silent", null,
            server.handleLine("{\"jsonrpc\":\"2.0\",\"method\":\"resources/read\",\"params\":{\"uri\":\"jcheck://catalog\"}}"));

        eq("mcp:ping", 0, result(request(server, 9, "ping", null)).size());

        Map<String, Object> list = result(request(server, 2, "resources/list", null));
        List<Object> resources = Json.asArray(list.get("resources"));
        eq("mcp:resource-count", 5, resources.size());
        List<String> uris = new ArrayList<>();
        for (Object o : resources) {
            Map<String, Object> r = Json.asObject(o);
            uris.add((String) r.get("uri"));
            check("mcp:resource-has-mimetype:" + r.get("uri"), r.get("mimeType") instanceof String,
                "missing mimeType");
            check("mcp:resource-has-description:" + r.get("uri"),
                r.get("description") instanceof String, "missing description");
        }
        check("mcp:lists-diagnostics", uris.contains(McpResources.DIAGNOSTICS), uris.toString());
        check("mcp:lists-capabilities", uris.contains(McpResources.CAPABILITIES), uris.toString());
        check("mcp:lists-suppressions", uris.contains(McpResources.SUPPRESSIONS), uris.toString());

        Map<String, Object> templates = result(request(server, 3, "resources/templates/list", null));
        List<Object> templateList = Json.asArray(templates.get("resourceTemplates"));
        eq("mcp:template-count", 2, templateList.size());

        // Error paths.
        Map<String, Object> err = requestError(server, 4, "frobnicate", null);
        eq("mcp:method-not-found", Long.valueOf(-32601), err.get("code"));

        Map<String, Object> notFound = requestError(server, 5, "resources/read",
            "{\"uri\":\"jcheck://nothing/here\"}");
        eq("mcp:resource-not-found-code", Long.valueOf(-32002), notFound.get("code"));
        eq("mcp:resource-not-found-uri", "jcheck://nothing/here",
            Json.asObject(notFound.get("data")).get("uri"));

        Map<String, Object> invalidParams = requestError(server, 6, "resources/read", "{}");
        eq("mcp:invalid-params", Long.valueOf(-32602), invalidParams.get("code"));

        String wrongEnvelope = requestLine(7, "ping", null).replace("\"2.0\"", "\"1.0\"");
        Map<String, Object> wrongVersion =
            Json.asObject(Json.parseObject(server.handleLine(wrongEnvelope)).get("error"));
        check("mcp:rejects-jsonrpc-1", wrongVersion != null && wrongVersion.get("code") != null,
            "expected an error");

        // A parse error carries no id, per JSON-RPC.
        Map<String, Object> parseError = Json.parseObject(server.handleLine("not json"));
        Map<String, Object> pe = error(parseError);
        eq("mcp:parse-error-code", Long.valueOf(-32700), pe.get("code"));
        check("mcp:parse-error-has-no-id", !parseError.containsKey("id"),
            "a parse error must not echo an id");

        // String ids must be echoed verbatim.
        String withStringId = server.handleLine(
            "{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"ping\"}");
        eq("mcp:string-id-echoed", "abc", Json.parseObject(withStringId).get("id"));

        // Probing clients get an empty tool list rather than an error.
        Map<String, Object> tools = result(request(server, 8, "tools/list", null));
        eq("mcp:no-tools", 0, Json.asArray(tools.get("tools")).size());
    }

    // ---- resources ---------------------------------------------------------

    private static void resourceTests(ProjectState state) {
        McpServer server = newServer(state);

        String capabilities = readText(server, 10, McpResources.CAPABILITIES);
        contains("res:capabilities-tier3-honest", capabilities, "Error Prone and NullAway are off");
        notContains("res:capabilities-no-gap", capabilities, "Catalogued but not implemented");
        check("res:capabilities-pack-complete",
            McpResources.cataloguedButUnimplemented(jcheck.catalog.Catalog.loadDefault()).isEmpty(),
            "every catalogued jcheck code should now have an implementation");
        contains("res:capabilities-lists-implemented", capabilities,
            "built-in jcheck pack: J0003");
        contains("res:capabilities-guarantee-section", capabilities, "Where the guarantee ends");
        notContains("res:capabilities-j0901-is-implemented", capabilities, "J0901 TestFailure");
        contains("res:capabilities-catalog-size", capabilities, "185 mapped codes");

        String diagnostics = readText(server, 11, McpResources.DIAGNOSTICS);
        contains("res:diagnostics-has-finding", diagnostics, "J0706");
        contains("res:diagnostics-tier-line", diagnostics, "analysis reached");
        check("res:diagnostics-cap-applied",
            diagnostics.split("\n").length < 40, "the LLM resource should stay compact");

        String json = readText(server, 12, McpResources.DIAGNOSTICS_JSON);
        String[] lines = json.strip().split("\n");
        check("res:diagnostics-json-has-summary", lines.length >= 2, "expected a summary line");
        for (int i = 0; i < lines.length; i++) {
            try {
                Json.parse(lines[i]);
            } catch (RuntimeException e) {
                check("res:diagnostics-json-line-" + i, false, e.getMessage());
            }
        }
        contains("res:diagnostics-json-schema", lines[lines.length - 1], "\"schema_version\":1");
        Map<String, Object> summary = Json.asObject(
            Json.parseObject(lines[lines.length - 1]).get("summary"));
        eq("res:diagnostics-json-one-finding", Long.valueOf(1), summary.get("total"));

        String catalog = readText(server, 13, McpResources.CATALOG);
        contains("res:catalog-has-j0201", catalog, "J0201");
        contains("res:catalog-has-upstream-key", catalog, "compiler.err.not.exhaustive.statement");
        check("res:catalog-is-large", catalog.length() > 5000, "catalog looks truncated");

        String entry = readText(server, 14, McpResources.CATALOG_CODE_PREFIX + "J0201");
        contains("res:catalog-entry-source", entry, "javac");
        contains("res:catalog-entry-tier", entry, "T2");

        String docs = readText(server, 15, McpResources.DOCS_CODE_PREFIX + "J0706");
        contains("res:docs-local-can-be-final", docs, "LocalCanBeFinal");
        contains("res:docs-failing-example", docs, "Failing example");

        String suppressions = readText(server, 16, McpResources.SUPPRESSIONS);
        contains("res:suppressions-lists-one", suppressions, "@SuppressWarnings(\"unchecked\")");
        contains("res:suppressions-needs-reason", suppressions, "real reason");

        // Unknown codes and malformed URIs must be refused, not guessed at.
        for (String uri : List.of("jcheck://docs/J9999", "jcheck://catalog/J9999",
                "jcheck://catalog/../secrets", "jcheck://catalog/a b")) {
            Map<String, Object> err = requestError(server, 17, "resources/read",
                "{\"uri\":\"" + uri + "\"}");
            check("res:refuses:" + uri, err.get("code") != null, "expected an error for " + uri);
        }

        check("res:codeFrom-valid", "J0201".equals(McpResources.codeFrom("jcheck://catalog/J0201",
            McpResources.CATALOG_CODE_PREFIX)), "should accept a plain code");
        eq("res:codeFrom-rejects-slash", null,
            McpResources.codeFrom("jcheck://catalog/../x", McpResources.CATALOG_CODE_PREFIX));
        eq("res:codeFrom-rejects-space", null,
            McpResources.codeFrom("jcheck://catalog/a b", McpResources.CATALOG_CODE_PREFIX));

        // Subscriptions.
        eq("res:subscribe-ok", 0, result(request(server, 18, "resources/subscribe",
            "{\"uri\":\"" + McpResources.DIAGNOSTICS + "\"}")).size());
        check("res:subscription-registered", server.subscriptions().contains(McpResources.DIAGNOSTICS),
            server.subscriptions().toString());
        eq("res:subscribe-unknown-uri", Long.valueOf(-32002),
            requestError(server, 19, "resources/subscribe",
                "{\"uri\":\"jcheck://nope\"}").get("code"));
        eq("res:unsubscribe-ok", 0, result(request(server, 20, "resources/unsubscribe",
            "{\"uri\":\"" + McpResources.DIAGNOSTICS + "\"}")).size());
        check("res:subscription-removed", !server.subscriptions().contains(McpResources.DIAGNOSTICS),
            server.subscriptions().toString());

        check("res:source-dependent", McpResources.isSourceDependent(McpResources.DIAGNOSTICS)
            && !McpResources.isSourceDependent(McpResources.CATALOG),
            "catalog is static, diagnostics is not");

        server.close();
    }

    // ---- warm-session caching ----------------------------------------------

    private static void cachingTests(Path project, ProjectState state) throws IOException {
        String before = state.fingerprint();
        check("cache:report-runs", state.report() != null, "expected a report");
        eq("cache:fingerprint-stable", before, state.fingerprint());

        Path source = project.resolve("src/main/java/demo/Cart.java");
        Files.writeString(source, CART_SOURCE + "\n// changed\n");
        check("cache:fingerprint-changes", !before.equals(state.fingerprint()),
            "fingerprint must change when a source file changes");

        int filesAfterChange = state.model().sources.size();
        state.refresh();
        eq("cache:model-refreshed", filesAfterChange, state.model().sources.size());

        // A new file must invalidate too, via the source-root directory mtime.
        String afterFirstChange = state.fingerprint();
        Files.writeString(project.resolve("src/main/java/demo/Extra.java"),
            "package demo;\n\nfinal class Extra {\n}\n");
        check("cache:fingerprint-sees-new-file", !afterFirstChange.equals(state.fingerprint()),
            "adding a source file must change the fingerprint");
    }

    // ---- end to end over real stdio ----------------------------------------

    private static void endToEndTests() throws Exception {
        Path project = tempProject();
        McpProcess proc = null;
        try {
            proc = McpProcess.start(project, 50);
            proc.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"e2e\",\"version\":\"1\"}}}");
            Map<String, Object> init = Json.parseObject(proc.await());
            eq("e2e:initialize-ok", null, init.get("error"));

            proc.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            proc.send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/list\"}");
            Map<String, Object> list = Json.parseObject(proc.await());
            eq("e2e:resource-count", 5,
                Json.asArray(result(list).get("resources")).size());

            proc.send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/read\","
                + "\"params\":{\"uri\":\"jcheck://diagnostics\"}}");
            String diagnostics = contentsText(Json.parseObject(proc.await()));
            contains("e2e:diagnostics-has-finding", diagnostics, "J0706");

            // Subscribe, let the watcher take its baseline, then change the tree.
            proc.send("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/subscribe\","
                + "\"params\":{\"uri\":\"jcheck://diagnostics\"}}");
            Map<String, Object> subscribed = Json.parseObject(proc.await());
            eq("e2e:subscribe-ok", null, subscribed.get("error"));
            Thread.sleep(600);
            Files.writeString(project.resolve("src/main/java/demo/Cart.java"),
                CART_SOURCE + "\n// trigger\n");

            String notification = proc.awaitNotification("notifications/resources/updated", 15_000);
            check("e2e:update-notification", notification != null,
                "expected notifications/resources/updated after a source change");
            if (notification != null) {
                contains("e2e:notification-uri", notification, "jcheck://diagnostics");
            }

            proc.closeStdin();
            check("e2e:exits-on-eof", proc.awaitExit(10_000), "server did not exit on stdin EOF");
            check("e2e:stdout-is-pure-json", proc.allStdoutWasJson(),
                "the server wrote something to stdout that is not an MCP message: "
                    + proc.nonJsonLines());
            check("e2e:errors-on-stderr", proc.stderr().contains("serving"),
                "expected a startup line on stderr, got: " + proc.stderr());
        } finally {
            if (proc != null) {
                proc.close();
            }
            UnitTests.deleteRecursively(project);
        }
    }

    // ---- edge cases --------------------------------------------------------

    private static void edgeCaseTests() throws IOException {
        // A project with no sources must not crash, and must not read as clean.
        Path empty = Files.createTempDirectory("jcheck-mcp-empty-");
        try {
            ProjectState state = new ProjectState(empty,
                new CheckRequest().root(empty.toString()).format("llm"));
            String diagnostics = readText(newServer(state), 30, McpResources.DIAGNOSTICS);
            contains("edge:empty-not-valid", diagnostics, "This analysis is not valid");
            contains("edge:empty-reason", diagnostics, "no Java sources");
            String caps = readText(newServer(state), 31, McpResources.CAPABILITIES);
            contains("edge:empty-capabilities-sources", caps, "Sources: 0 Java file(s)");
        } finally {
            UnitTests.deleteRecursively(empty);
        }

        // A syntax error must stop the tiers, and the resource must say so.
        Path broken = Files.createTempDirectory("jcheck-mcp-broken-");
        try {
            Path dir = broken.resolve("src/main/java/demo");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("S.java"),
                "package demo;\n\npublic final class S {\n    public void go() {\n        int x = ;\n    }\n}\n");
            ProjectState state = new ProjectState(broken,
                new CheckRequest().root(broken.toString()).format("llm"));
            String diagnostics = readText(newServer(state), 32, McpResources.DIAGNOSTICS);
            contains("edge:syntax-error-code", diagnostics, "J0104");
            contains("edge:syntax-error-stops-tiers", diagnostics, "reached T0 of 5");
            notContains("edge:syntax-error-no-t3-finding", diagnostics, "J0706");
        } finally {
            UnitTests.deleteRecursively(broken);
        }

        // Anything that is not an allow-listed code shape is refused rather than interpreted.
        Path project = tempProject();
        try {
            McpServer server = newServer(new ProjectState(project,
                new CheckRequest().root(project.toString()).format("llm")));
            contains("edge:lowercase-code", readText(server, 33, "jcheck://docs/j0706"), "J0706");
            contains("edge:short-code", readText(server, 34, "jcheck://catalog/706"), "J0706");
            for (String uri : List.of("file:///etc/passwd", "jcheck://docs/../../etc/passwd",
                    "jcheck://catalog/J0706/extra", "jcheck://", "jcheck://docs/")) {
                Map<String, Object> err = requestError(server, 35, "resources/read",
                    "{\"uri\":\"" + uri + "\"}");
                check("edge:refuses-uri:" + uri, err.get("code") != null, "expected an error for " + uri);
            }
            check("edge:known-uri-check", McpResources.isKnownUri(McpResources.CATALOG)
                && !McpResources.isKnownUri("file:///etc/passwd"),
                "isKnownUri must reject foreign schemes");

            // Carriage returns must not break framing.
            Map<String, Object> crlf = Json.parseObject(server.handleLine(
                "{\"jsonrpc\":\"2.0\",\"id\":36,\"method\":\"ping\"}\r"));
            eq("edge:crlf-tolerated", null, crlf.get("error"));
            server.close();
        } finally {
            UnitTests.deleteRecursively(project);
        }
    }

    // ---- helpers -----------------------------------------------------------

    private static Path tempProject() throws IOException {
        Path tmp = Files.createTempDirectory("jcheck-mcp-project-");
        Path dir = tmp.resolve("src/main/java/demo");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Cart.java"), CART_SOURCE);
        return tmp;
    }

    private static McpServer newServer(ProjectState state) {
        return new McpServer(state, line -> { },
            new PrintStream(OutputStream.nullOutputStream(), true), 1000);
    }

    private static Map<String, Object> request(McpServer server, int id, String method, String paramsJson) {
        String response = server.handleLine(requestLine(id, method, paramsJson));
        check("mcp:responds:" + method, response != null, "no response for " + method);
        return response == null ? Map.of() : Json.parseObject(response);
    }

    /** Sends a request expected to fail and returns the parsed {@code error} object. */
    private static Map<String, Object> requestError(McpServer server, int id, String method,
                                                    String paramsJson) {
        String response = server.handleLine(requestLine(id, method, paramsJson));
        if (response == null) {
            check("mcp:error-responds:" + method, false, "no response at all");
            return Map.of();
        }
        Map<String, Object> e = Json.asObject(Json.parseObject(response).get("error"));
        check("mcp:error-object:" + method, e != null, "expected an error, got " + response);
        return e == null ? Map.of() : e;
    }

    private static String requestLine(int id, String method, String paramsJson) {
        if (paramsJson == null) {
            return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\"}";
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method
            + "\",\"params\":" + paramsJson + "}";
    }

    private static Map<String, Object> result(Map<String, Object> response) {
        Map<String, Object> r = Json.asObject(response.get("result"));
        check("mcp:has-result", r != null, "expected a result, got " + response);
        return r == null ? Map.of() : r;
    }

    private static Map<String, Object> error(Map<String, Object> response) {
        Map<String, Object> e = Json.asObject(response.get("error"));
        check("mcp:has-error", e != null, "expected an error, got " + response);
        return e == null ? Map.of() : e;
    }

    private static String readText(McpServer server, int id, String uri) {
        Map<String, Object> response = request(server, id, "resources/read", "{\"uri\":\"" + uri + "\"}");
        return contentsText(response);
    }

    private static String contentsText(Map<String, Object> response) {
        List<Object> contents = Json.asArray(result(response).get("contents"));
        check("mcp:has-contents", contents != null && !contents.isEmpty(), "no contents");
        if (contents == null || contents.isEmpty()) {
            return "";
        }
        return (String) Json.asObject(contents.get(0)).get("text");
    }

    /** A real subprocess MCP client: writes JSON-RPC to stdin, reads lines from stdout. */
    static final class McpProcess {
        private final Process process;
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final List<String> stdout = new ArrayList<>();
        private final List<String> nonJson = new ArrayList<>();
        private final StringBuilder stderrBuffer = new StringBuilder();
        private volatile boolean eof;

        private McpProcess(Process process) {
            this.process = process;
        }

        static McpProcess start(Path root, long watchMillis) throws IOException {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = System.getProperty("java.class.path");
            ProcessBuilder pb = new ProcessBuilder(java, "-cp", classpath, "jcheck.cli.Main",
                "mcp", "--root", root.toString(), "--watch-interval-ms", String.valueOf(watchMillis));
            Process p = pb.start();
            McpProcess mcp = new McpProcess(p);
            mcp.startReaders();
            return mcp;
        }

        private void startReaders() {
            Thread out = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (stdout) {
                            stdout.add(line);
                        }
                        try {
                            Json.parse(line);
                        } catch (RuntimeException e) {
                            synchronized (nonJson) {
                                nonJson.add(line);
                            }
                        }
                        lines.add(line);
                    }
                } catch (IOException ignored) {
                    // stream closed
                } finally {
                    eof = true;
                }
            }, "mcp-e2e-stdout");
            out.setDaemon(true);
            out.start();

            Thread err = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (stderrBuffer) {
                            stderrBuffer.append(line).append('\n');
                        }
                    }
                } catch (IOException ignored) {
                    // stream closed
                }
            }, "mcp-e2e-stderr");
            err.setDaemon(true);
            err.start();
        }

        void send(String line) throws IOException {
            process.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
        }

        /** Waits for the next non-notification line. */
        String await() throws InterruptedException {
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                String line = lines.poll(200, TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                Map<String, Object> obj = Json.parseObject(line);
                if (obj.containsKey("id")) {
                    return line;
                }
                // a notification arrived while waiting for a response: keep it for the next await
                pending.add(line);
            }
            return "{}";
        }

        private final ArrayDeque<String> pending = new ArrayDeque<>();

        String awaitNotification(String method, long timeoutMillis) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (true) {
                String buffered = pending.poll();
                if (buffered != null && buffered.contains(method)) {
                    return buffered;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return null;
                }
                String line = lines.poll(Math.min(200, remaining), TimeUnit.MILLISECONDS);
                if (line != null && line.contains(method)) {
                    return line;
                }
            }
        }

        void closeStdin() {
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // already closed
            }
        }

        boolean awaitExit(long millis) {
            try {
                return process.waitFor(millis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        boolean allStdoutWasJson() {
            synchronized (nonJson) {
                return nonJson.isEmpty();
            }
        }

        List<String> nonJsonLines() {
            synchronized (nonJson) {
                return new ArrayList<>(nonJson);
            }
        }

        String stderr() {
            synchronized (stderrBuffer) {
                return stderrBuffer.toString();
            }
        }

        void close() {
            process.destroyForcibly();
        }
    }
}
