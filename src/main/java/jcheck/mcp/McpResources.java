package jcheck.mcp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jcheck.build.BuildModel;
import jcheck.catalog.Catalog;
import jcheck.catalog.CatalogEntry;
import jcheck.cli.Main;
import jcheck.config.JcheckConfig;
import jcheck.engine.NativeChecks;
import jcheck.explain.Explainer;
import jcheck.model.CheckReport;
import jcheck.model.Diagnostic;
import jcheck.model.Tier;
import jcheck.model.TierReport;
import jcheck.render.JsonRenderer;
import jcheck.render.LlmRenderer;
import jcheck.render.RenderOptions;

/**
 * The resources jcheck exposes over MCP.
 *
 * <p>Deliberately resources-only: no tools. The CLI already is the tool interface, and adding a
 * second way to ask for the same diagnostics would create exactly the "two modes the agent has to
 * choose between" that SPEC.md warns against. Resources are the part the CLI cannot express —
 * pull-based context that costs nothing until the agent asks for it.
 *
 * <p>The {@code capabilities} resource is the important one: it states which checks actually run
 * and where the guarantee ends, which is the uncertainty-surfacing the spec cares about most.
 */
public final class McpResources {

    public static final String CAPABILITIES = "jcheck://capabilities";
    public static final String CATALOG = "jcheck://catalog";
    public static final String SUPPRESSIONS = "jcheck://suppressions";
    public static final String DIAGNOSTICS = "jcheck://diagnostics";
    public static final String DIAGNOSTICS_JSON = "jcheck://diagnostics.json";
    public static final String CATALOG_CODE_TEMPLATE = "jcheck://catalog/{code}";
    public static final String DOCS_CODE_TEMPLATE = "jcheck://docs/{code}";
    public static final String CATALOG_CODE_PREFIX = "jcheck://catalog/";
    public static final String DOCS_CODE_PREFIX = "jcheck://docs/";

    public static final String MARKDOWN = "text/markdown";
    public static final String NDJSON = "application/x-ndjson";

    /** URIs whose content depends on the source tree, so subscriptions should fire for them. */
    private static final List<String> SOURCE_DEPENDENT =
        List.of(DIAGNOSTICS, DIAGNOSTICS_JSON, SUPPRESSIONS, CAPABILITIES);

    public record ResourceDef(String uri, String name, String title, String description, String mimeType) {
    }

    public record TemplateDef(String uriTemplate, String name, String title, String description,
                              String mimeType) {
    }

    /** Outcome of reading a resource; {@code found == false} maps to JSON-RPC error -32002. */
    public static final class ReadResult {
        public final boolean found;
        public final String mimeType;
        public final String text;
        public final String message;

        private ReadResult(boolean found, String mimeType, String text, String message) {
            this.found = found;
            this.mimeType = mimeType;
            this.text = text;
            this.message = message;
        }

        static ReadResult ok(String mimeType, String text) {
            return new ReadResult(true, mimeType, text, null);
        }

        static ReadResult notFound(String message) {
            return new ReadResult(false, null, null, message);
        }
    }

    private McpResources() {
    }

    public static List<ResourceDef> list() {
        List<ResourceDef> out = new ArrayList<>();
        out.add(new ResourceDef(CAPABILITIES, "capabilities",
            "jcheck capabilities",
            "Which analysis tiers actually run in this project and where the guarantee ends",
            MARKDOWN));
        out.add(new ResourceDef(DIAGNOSTICS, "diagnostics",
            "jcheck diagnostics",
            "Root-cause-first, capped diagnostic list in jcheck's agent-facing format",
            MARKDOWN));
        out.add(new ResourceDef(DIAGNOSTICS_JSON, "diagnostics-json",
            "jcheck diagnostics (JSON Lines)",
            "The same diagnostics in the versioned JSON Lines schema, one object per line",
            NDJSON));
        out.add(new ResourceDef(CATALOG, "catalog",
            "jcheck code catalog",
            "Every diagnostic code jcheck can emit, with its upstream key and tier",
            MARKDOWN));
        out.add(new ResourceDef(SUPPRESSIONS, "suppressions",
            "jcheck suppression inventory",
            "Every suppression found in the checked sources, for auditing",
            MARKDOWN));
        return out;
    }

    public static List<TemplateDef> templates() {
        List<TemplateDef> out = new ArrayList<>();
        out.add(new TemplateDef(CATALOG_CODE_TEMPLATE, "catalog-code",
            "jcheck catalog entry",
            "One catalog entry by its jcheck code, e.g. jcheck://catalog/J0201",
            MARKDOWN));
        out.add(new TemplateDef(DOCS_CODE_TEMPLATE, "docs-code",
            "jcheck explain page",
            "The long-form explanation for a code, e.g. jcheck://docs/J0201",
            MARKDOWN));
        return out;
    }

    public static boolean isSourceDependent(String uri) {
        return SOURCE_DEPENDENT.contains(uri);
    }

    /** True when the URI is one we can serve (static, or matching a template). */
    public static boolean isKnownUri(String uri) {
        if (uri == null) {
            return false;
        }
        for (ResourceDef d : list()) {
            if (d.uri().equals(uri)) {
                return true;
            }
        }
        return codeFrom(uri, CATALOG_CODE_PREFIX) != null || codeFrom(uri, DOCS_CODE_PREFIX) != null;
    }

    public static ReadResult read(ProjectState state, String uri) {
        if (uri == null || uri.isEmpty()) {
            return ReadResult.notFound("a resource uri is required");
        }
        if (CAPABILITIES.equals(uri)) {
            return ReadResult.ok(MARKDOWN, capabilities(state));
        }
        if (DIAGNOSTICS.equals(uri)) {
            return ReadResult.ok(MARKDOWN, diagnostics(state));
        }
        if (DIAGNOSTICS_JSON.equals(uri)) {
            return ReadResult.ok(NDJSON, diagnosticsJson(state));
        }
        if (CATALOG.equals(uri)) {
            return ReadResult.ok(MARKDOWN, catalogTable());
        }
        if (SUPPRESSIONS.equals(uri)) {
            return ReadResult.ok(MARKDOWN, suppressions(state));
        }
        String catalogCode = codeFrom(uri, CATALOG_CODE_PREFIX);
        if (catalogCode != null) {
            return catalogEntry(catalogCode);
        }
        String docsCode = codeFrom(uri, DOCS_CODE_PREFIX);
        if (docsCode != null) {
            return docsEntry(state, docsCode);
        }
        return ReadResult.notFound("no such resource: " + uri);
    }

    /**
     * Extracts and validates a code from a templated URI. Anything outside {@code [A-Za-z0-9]}
     * is rejected rather than passed through: the spec requires servers to validate resource URIs.
     */
    public static String codeFrom(String uri, String prefix) {
        if (uri == null || !uri.startsWith(prefix)) {
            return null;
        }
        String raw = uri.substring(prefix.length());
        if (raw.isEmpty() || raw.length() > 8 || !raw.matches("[A-Za-z0-9]+")) {
            return null;
        }
        return Explainer.normalize(raw);
    }

    // ---- resource bodies ---------------------------------------------------

    static String capabilities(ProjectState state) {
        JcheckConfig config = state.config();
        BuildModel model = state.model();
        Catalog catalog = Catalog.loadDefault();
        StringBuilder sb = new StringBuilder();

        sb.append("# jcheck capabilities\n\n");
        sb.append("jcheck ").append(Main.VERSION)
          .append(" on JDK ").append(Runtime.version().feature())
          .append(" - profile `").append(config.profile).append("`")
          .append(", build tool `").append(model.buildTool).append("`\n\n");

        sb.append("Root: `").append(state.root()).append("`\n\n");
        sb.append("Sources: ").append(model.sources.size()).append(" Java file(s)");
        if (model.release != null) {
            sb.append(", `--release ").append(model.release).append("`");
        }
        sb.append("\n\n");

        sb.append("## Analysis tiers that actually run here\n\n");
        sb.append("| Tier | Status | Detail |\n| --- | --- | --- |\n");
        sb.append("| T0 syntax | runs | javac parse |\n");
        sb.append("| T1 symbols and types | runs | javac attribute |\n");
        sb.append("| T2 flow | runs | definite assignment, reachability, exhaustiveness |\n");
        if (NativeChecks.IMPLEMENTED_CODES.isEmpty() && !config.errorProne) {
            sb.append("| T3 error-prone + nullaway + jcheck-checks | not available | nothing implemented |\n");
        } else {
            sb.append("| T3 error-prone + nullaway + jcheck-checks | ")
              .append(config.errorProne ? "runs" : "partial")
              .append(" | built-in jcheck pack: ")
              .append(String.join(", ", NativeChecks.IMPLEMENTED_CHECK_CODES));
            if (config.errorProne) {
                sb.append(". Error Prone and NullAway are enabled and run in a forked JVM "
                    + "with the module exports they need");
            } else {
                sb.append(". Error Prone and NullAway are off; enable them in [errorprone] "
                    + "to attach the plugins");
            }
            sb.append(" |\n");
        }
        sb.append("| T4 checker-framework | ")
          .append(config.anyCheckerConfigured() ? "runs" : "not configured");
        if (config.anyCheckerConfigured()) {
            sb.append(" | ").append(String.join(", ", config.checkerNames()))
              .append("; applied to the whole compilation, not per module");
        } else {
            sb.append(" | enable [checkers.per-module] or [nullness] checker_framework to attach it");
        }
        sb.append(" |\n");
        sb.append("| T5 bytecode tools | ")
          .append(config.spotbugs ? "configured but not implemented" : "not configured")
          .append(" | SpotBugs analysis is not driven by this build |\n\n");

        sb.append("## Where the guarantee ends\n\n");
        sb.append("- Only T0-T2 are backed by the compiler. Any claim beyond that is limited to the "
            + "checks listed above.\n");
        sb.append("- Catalog: ").append(catalog.size())
          .append(" mapped codes. An unmapped upstream key still gets a stable `J9xxx` code "
              + "derived from its key, so nothing is dropped, but it has no curated explanation.\n");
        if ("gradle".equals(model.buildTool) || "maven".equals(model.buildTool)) {
            sb.append("- The ").append(model.buildTool)
              .append(" dependency graph is not resolved, so third-party types can appear "
                  + "unresolved. Pass `--classpath` to close that gap.\n");
        }
        sb.append("- Suppressions are never blanket: `").append(SUPPRESSIONS)
          .append("` lists every one so a reviewer can audit them.\n");

        List<String> catalogued = cataloguedButUnimplemented(catalog);
        if (!catalogued.isEmpty()) {
            sb.append("\n## Catalogued but not implemented\n\n");
            sb.append("These codes have documentation and catalog rows, but nothing in this build "
                + "emits them yet. Do not read their absence from a report as evidence of absence "
                + "of the problem.\n\n");
            sb.append(String.join(", ", catalogued)).append("\n");
        }

        CheckReport cached = state.cachedReportOrNull();
        if (cached != null) {
            sb.append("\n## Last analysis\n\n");
            sb.append("- ").append(cached.tiers.summaryLine()).append("\n");
            sb.append("- ").append(cached.countLine()).append("\n");
            if (!cached.dropped.isEmpty()) {
                sb.append("- ").append(cached.dropped.size())
                  .append(" diagnostic(s) withheld as cascades or as above-tier findings\n");
            }
            sb.append("- ").append(cached.suppressions.size()).append(" suppression(s) found\n");
        } else {
            sb.append("\n## Last analysis\n\nNo analysis has run in this session yet.\n");
        }
        if (state.configError() != null) {
            sb.append("\n## Configuration problem\n\n`").append(state.configError()).append("`\n");
        }
        return sb.toString();
    }

    public static List<String> cataloguedButUnimplemented(Catalog catalog) {
        List<String> out = new ArrayList<>();
        for (CatalogEntry e : catalog.entries()) {
            if ("jcheck".equals(e.source()) && !NativeChecks.IMPLEMENTED_CODES.contains(e.code())) {
                out.add(e.code() + " " + e.sourceKey());
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    static String diagnostics(ProjectState state) {
        CheckReport report = state.report();
        StringBuilder sb = new StringBuilder();
        sb.append("# jcheck diagnostics\n\n");
        if (state.lastError() != null) {
            sb.append("The analysis could not run: ").append(state.lastError()).append("\n");
            return sb.toString();
        }
        // A configuration problem means the report below is not a clean bill of health. It has to
        // come first: an agent that reads "0 errors" without this would read a false clean.
        if (!report.configErrors.isEmpty()) {
            sb.append("**This analysis is not valid.**\n\n");
            for (String e : report.configErrors) {
                sb.append("- ").append(e).append('\n');
            }
            sb.append('\n');
        }
        RenderOptions options = new RenderOptions("llm", state.config().llmMaxDiagnostics,
            state.config().llmContextLines, false, state.root().toString());
        sb.append(new LlmRenderer().render(report, options));
        return sb.toString();
    }

    static String diagnosticsJson(ProjectState state) {
        CheckReport report = state.report();
        RenderOptions options = new RenderOptions("json", 0, 0, false, state.root().toString());
        return new JsonRenderer().render(report, options);
    }

    static String suppressions(ProjectState state) {
        CheckReport report = state.report();
        StringBuilder sb = new StringBuilder("# jcheck suppressions\n\n");
        if (report.suppressions.isEmpty()) {
            sb.append("None found in the checked sources.\n");
            return sb.toString();
        }
        sb.append(report.suppressions.size())
          .append(" suppression(s). Every one needs a real reason.\n\n");
        for (String s : report.suppressions) {
            sb.append("- `").append(s).append("`\n");
        }
        return sb.toString();
    }

    static String catalogTable() {
        Catalog catalog = Catalog.loadDefault();
        List<CatalogEntry> entries = new ArrayList<>(catalog.entries());
        entries.sort(Comparator.comparing(CatalogEntry::code));
        StringBuilder sb = new StringBuilder("# jcheck code catalog\n\n");
        sb.append(entries.size()).append(" codes. Codes are stable forever.\n\n");
        sb.append("| Code | Source | Upstream key | Tier | What it means |\n");
        sb.append("| --- | --- | --- | --- | --- |\n");
        for (CatalogEntry e : entries) {
            sb.append("| ").append(e.code())
              .append(" | ").append(e.source())
              .append(" | `").append(e.sourceKey()).append('`')
              .append(" | T").append(e.tier())
              .append(" | ").append(oneLine(e.headline()))
              .append(" |\n");
        }
        return sb.toString();
    }

    static ReadResult catalogEntry(String code) {
        Catalog catalog = Catalog.loadDefault();
        CatalogEntry e = catalog.lookupCode(code);
        if (e == null) {
            return ReadResult.notFound("no catalog entry for " + code);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(e.code()).append(" - ").append(e.sourceKey()).append("\n\n");
        sb.append("- source: `").append(e.source()).append("`\n");
        sb.append("- upstream key: `").append(e.sourceKey()).append("`\n");
        sb.append("- tier: T").append(e.tier()).append(' ').append(Tier.name(e.tier())).append("\n");
        if (!e.group().equals(e.code())) {
            sb.append("- dedupe group: `").append(e.group())
              .append("` (merges with the same issue reported by other tools)\n");
        }
        sb.append('\n').append(e.headline()).append("\n");
        for (String h : e.help()) {
            sb.append("\n- ").append(h);
        }
        sb.append("\n\nLong form: read `").append(DOCS_CODE_PREFIX).append(e.code()).append("`\n");
        return ReadResult.ok(MARKDOWN, sb.toString());
    }

    static ReadResult docsEntry(ProjectState state, String code) {
        Explainer.Result r = Explainer.explain(state.root().toString(), code);
        if (r.exitCode != 0) {
            return ReadResult.notFound(r.text.strip());
        }
        return ReadResult.ok(MARKDOWN, r.text);
    }

    static String oneLine(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ").strip();
    }

    /** Codes grouped by source, used by tests and the capabilities resource. */
    static Map<String, List<String>> codesBySource() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (CatalogEntry e : Catalog.loadDefault().entries()) {
            out.computeIfAbsent(e.source().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(e.code());
        }
        return out;
    }
}
