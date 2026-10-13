package jcheck.tests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import jcheck.build.BuildModel;
import jcheck.build.DependencyResolver;
import jcheck.catalog.Catalog;
import jcheck.catalog.CatalogEntry;
import jcheck.cli.CheckCommand;
import jcheck.config.JcheckConfig;
import jcheck.config.Toml;
import jcheck.engine.CascadeFilter;
import jcheck.engine.Dedupe;
import jcheck.engine.LevelPolicy;
import jcheck.engine.SourceText;
import jcheck.engine.SpanResolver;
import jcheck.engine.TierAssign;
import jcheck.explain.Explainer;
import jcheck.fix.FixApplier;
import jcheck.init.Initializer;
import jcheck.model.Applicability;
import jcheck.model.CheckReport;
import jcheck.model.Diagnostic;
import jcheck.model.Edit;
import jcheck.model.Level;
import jcheck.model.Span;
import jcheck.model.Suggestion;
import jcheck.model.Tier;
import jcheck.model.TierReport;
import jcheck.plugins.MavenResolver;
import jcheck.render.HumanRenderer;
import jcheck.render.JsonRenderer;
import jcheck.render.LlmRenderer;
import jcheck.render.RenderOptions;
import jcheck.render.Renderers;
import jcheck.render.SarifRenderer;
import jcheck.testharness.JUnitXmlParser;

import static jcheck.tests.TestMain.check;
import static jcheck.tests.TestMain.contains;
import static jcheck.tests.TestMain.eq;
import static jcheck.tests.TestMain.notContains;

/** Unit tests for the parts that are not covered by the UI golden cases. */
final class UnitTests {

    private UnitTests() {
    }

    static void run(Path repoRoot) throws Exception {
        catalogTests();
        tierAssignTests();
        sourceTextTests();
        spanResolverTests();
        tomlTests();
        configTests();
        levelPolicyTests();
        cascadeTests();
        dedupeTests();
        rendererTests();
        jsonTests();
        sarifTests();
        fixApplierTests();
        junitXmlTests();
        explainTests(repoRoot);
        initializerTests();
        buildModelTests();
        dependencyResolverTests();
        mavenResolverTests();
        errorProneIntegrationTests();
    }

    // ---- dependency resolution ---------------------------------------------

    /**
     * The build-tool resolver's parsing is exercised offline: the marker protocol, the Maven
     * classpath split, and the cache fingerprint tracking the build file.
     */
    private static void dependencyResolverTests() throws Exception {
        DependencyResolver.Parsed parsed = DependencyResolver.parseGradleOutput(
            "noise\nJCHECK_CLASSPATH_BEGIN\n/a/x.jar\n/b/y.jar\n"
            + "JCHECK_PROCESSOR\n/c/ap.jar\nJCHECK_CLASSPATH_END\n");
        check("deps:gradle-parsed",
            parsed != null && parsed.classpath.size() == 2 && parsed.processorPath.size() == 1,
            "parsed=" + parsed);
        check("deps:gradle-null-without-markers",
            DependencyResolver.parseGradleOutput("nothing here") == null, null);
        check("deps:maven-split", DependencyResolver.splitClasspath(
            "/a/x.jar" + java.io.File.pathSeparator + "/b/y.jar").size() == 2, null);

        Path tmp = Files.createTempDirectory("jcheck-fingerprint-");
        Files.writeString(tmp.resolve("build.gradle"), "plugins { }", StandardCharsets.UTF_8);
        String first = DependencyResolver.fingerprint(tmp);
        Files.writeString(tmp.resolve("build.gradle"), "plugins { id 'java' }", StandardCharsets.UTF_8);
        String second = DependencyResolver.fingerprint(tmp);
        check("deps:fingerprint-tracks-build-file", !first.equals(second), "fingerprint did not change");
        deleteRecursively(tmp);
    }

    // ---- plugin resolution -------------------------------------------------

    /**
     * Exercises the resolver entirely offline against a fixture repository: parent properties,
     * dependencyManagement, and test-scope exclusion are the three behaviours Error Prone's and
     * NullAway's real POMs depend on.
     */
    private static void mavenResolverTests() throws Exception {
        Path tmp = Files.createTempDirectory("jcheck-maven-test-");
        Path repo = tmp.resolve("repo");

        write(repo.resolve("com/example/parent/1.0/parent-1.0.pom"),
            "<project><modelVersion>4.0.0</modelVersion>"
            + "<groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version>"
            + "<packaging>pom</packaging>"
            + "<properties><lib.version>2.0</lib.version></properties>"
            + "<dependencyManagement><dependencies>"
            + "<dependency><groupId>com.example</groupId><artifactId>c</artifactId>"
            + "<version>${lib.version}</version></dependency>"
            + "</dependencies></dependencyManagement></project>");

        write(repo.resolve("com/example/a/1.0/a-1.0.pom"),
            "<project><modelVersion>4.0.0</modelVersion>"
            + "<parent><groupId>com.example</groupId><artifactId>parent</artifactId>"
            + "<version>1.0</version></parent>"
            + "<artifactId>a</artifactId>"
            + "<dependencies>"
            + "<dependency><groupId>com.example</groupId><artifactId>b</artifactId>"
            + "<version>1.0</version><scope>test</scope></dependency>"
            + "<dependency><groupId>com.example</groupId><artifactId>c</artifactId></dependency>"
            + "</dependencies></project>");

        write(repo.resolve("com/example/c/2.0/c-2.0.pom"),
            "<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>"
            + "<artifactId>c</artifactId><version>2.0</version></project>");

        write(repo.resolve("com/example/a/1.0/a-1.0.jar"), "");
        write(repo.resolve("com/example/c/2.0/c-2.0.jar"), "");
        write(repo.resolve("com/example/b/1.0/b-1.0.jar"), "");

        MavenResolver resolver = new MavenResolver(tmp.resolve("cache"), List.of(repo.toUri()), true);
        List<Path> jars = resolver.resolve(List.of("com.example:a:1.0"));
        List<String> names = new ArrayList<>();
        for (Path p : jars) {
            names.add(p.getFileName().toString());
        }
        check("maven:transitive-count", names.size() == 2, "got " + names);
        check("maven:root-included", names.contains("a-1.0.jar"), "got " + names);
        check("maven:managed-version", names.contains("c-2.0.jar"), "got " + names);
        check("maven:test-scope-excluded", !names.contains("b-1.0.jar"), "got " + names);
        check("maven:returns-real-files", jars.stream().allMatch(Files::isRegularFile),
            "a classpath entry must exist on disk");

        MavenResolver missing = new MavenResolver(tmp.resolve("cache2"), List.of(repo.toUri()), true);
        check("maven:missing-is-not-fatal",
            missing.resolve(List.of("com.example:missing:1.0")).isEmpty() && !missing.notes().isEmpty(),
            "a missing artifact should be a note, not an exception");

        try {
            MavenResolver.Gav.parse("not-a-coordinate");
            check("maven:bad-coordinate-rejected", false, "expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            check("maven:bad-coordinate-rejected", true, null);
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /**
     * Opt-in end-to-end check for the forked Error Prone/NullAway run. The default suite must not
     * download analyzer jars, so this only runs with {@code JCHECK_TEST_PLUGINS=1} set (and then
     * expects either a warm cache or network access).
     */
    private static void errorProneIntegrationTests() throws Exception {
        if (!"1".equals(System.getenv("JCHECK_TEST_PLUGINS"))) {
            return;
        }
        Path tmp = Files.createTempDirectory("jcheck-errorprone-it-");
        Path src = tmp.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Ref.java"),
            "package demo;\n"
            + "import org.jspecify.annotations.NullMarked;\n"
            + "import org.jspecify.annotations.Nullable;\n"
            + "@NullMarked\n"
            + "public final class Ref {\n"
            + "    int len(@Nullable String s) {\n"
            + "        return s.length();\n"
            + "    }\n"
            + "    boolean same(String a, String b) {\n"
            + "        return a == b;\n"
            + "    }\n"
            + "}\n", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("jcheck.toml"),
            "[errorprone]\nenabled = true\n\n[nullness]\nmode = \"nullaway-jspecify\"\n",
            StandardCharsets.UTF_8);

        CheckCommand.Outcome out = TestMain.runCase(tmp, "check --profile standard");
        contains("plugins:reference-equality", out.stdout, "J0430");
        contains("plugins:nullaway-dereference", out.stdout, "J0503");
        contains("plugins:source-nullaway", out.stdout, "source: nullaway");
        contains("plugins:tier3", out.stdout, "analysis reached T3");
        deleteRecursively(tmp);

        Path cf = Files.createTempDirectory("jcheck-checker-it-");
        Path cfSrc = cf.resolve("src/main/java/demo");
        Files.createDirectories(cfSrc);
        Files.writeString(cfSrc.resolve("Cf.java"),
            "package demo;\n"
            + "import org.checkerframework.checker.nullness.qual.Nullable;\n"
            + "public final class Cf {\n"
            + "    int len(@Nullable String s) {\n"
            + "        return s.length();\n"
            + "    }\n"
            + "}\n", StandardCharsets.UTF_8);
        Files.writeString(cf.resolve("jcheck.toml"),
            "[nullness]\nchecker_framework = true\n", StandardCharsets.UTF_8);
        CheckCommand.Outcome cfOut = TestMain.runCase(cf, "check --profile standard");
        contains("plugins:checker-nullness", cfOut.stdout, "J0509");
        contains("plugins:checker-source", cfOut.stdout, "source: checker");
        contains("plugins:tier4", cfOut.stdout, "analysis reached T4");
        deleteRecursively(cf);

        Path sb = Files.createTempDirectory("jcheck-spotbugs-it-");
        Path sbSrc = sb.resolve("src/main/java/demo");
        Files.createDirectories(sbSrc);
        Files.writeString(sbSrc.resolve("Sb.java"),
            "package demo;\n"
            + "public class Sb {\n"
            + "    String decode(byte[] b) {\n"
            + "        return new String(b);\n"
            + "    }\n"
            + "}\n", StandardCharsets.UTF_8);
        Files.writeString(sb.resolve("jcheck.toml"),
            "[bytecode]\nspotbugs = { effort = \"max\", threshold = \"low\", findsecbugs = false }\n",
            StandardCharsets.UTF_8);
        CheckCommand.Outcome sbOut = TestMain.runCase(sb, "check --profile standard");
        contains("plugins:spotbugs-report", sbOut.stdout, "source: spotbugs");
        contains("plugins:tier5", sbOut.stdout, "analysis reached T5");
        deleteRecursively(sb);
    }

    // ---- catalog -----------------------------------------------------------

    private static void catalogTests() {
        Catalog catalog = Catalog.loadDefault();
        check("catalog:loads", catalog.size() >= 180, "size=" + catalog.size());

        CatalogEntry j0201 = catalog.lookupCode("J0201");
        check("catalog:J0201-exists", j0201 != null, "J0201 missing");
        if (j0201 != null) {
            eq("catalog:J0201-tier", 2, j0201.tier());
            eq("catalog:J0201-key", "compiler.err.not.exhaustive.statement", j0201.sourceKey());
        }

        eq("catalog:mapped-code", "J0201",
            catalog.codeFor("javac", "compiler.err.not.exhaustive.statement"));
        check("catalog:is-mapped", catalog.isMapped("javac", "compiler.err.not.exhaustive.statement"),
            "expected the key to be mapped");
        check("catalog:unmapped-detected", !catalog.isMapped("javac", "compiler.err.not.a.real.key"),
            "expected the key to be unmapped");

        String fallback = catalog.codeFor("javac", "compiler.err.not.a.real.key");
        check("catalog:fallback-shape", fallback.matches("J9\\d{3}"), "got " + fallback);
        eq("catalog:fallback-stable", fallback, catalog.codeFor("javac", "compiler.err.not.a.real.key"));
        check("catalog:fallback-distinct",
            !fallback.equals(catalog.codeFor("javac", "compiler.err.another.key")),
            "two different keys landed on the same fallback code");

        for (CatalogEntry e : catalog.entries()) {
            check("catalog:code-shape:" + e.sourceKey(), e.code().matches("J\\d{4}"), "code=" + e.code());
            check("catalog:tier-range:" + e.code(),
                e.tier() >= 0 && e.tier() <= Tier.MAX, "tier=" + e.tier());
            check("catalog:doc:" + e.code(), !e.doc().isEmpty(), "empty doc path");
            check("catalog:headline:" + e.code(), !e.headline().isEmpty(), "empty headline");
        }

        check("catalog:reserved-J0706", catalog.lookup("jcheck", "LocalCanBeFinal") != null
            && "J0706".equals(catalog.lookup("jcheck", "LocalCanBeFinal").code()),
            "J0706 must map from jcheck/LocalCanBeFinal");

        String[][] pack = {
            {"SuppressionNeedsReason", "J0003"},
            {"UncheckedArithmetic", "J0301"},
            {"LossyCast", "J0302"},
            {"SharedMutableWithoutGuard", "J0601"},
            {"MutationDuringIteration", "J0602"},
            {"EscapingMutableInternal", "J0603"},
            {"UnsafeOutsideUnsafe", "J0701"},
            {"MutableStaticState", "J0705"},
            {"LocalCanBeFinal", "J0706"},
            {"UnclosedResource", "J0801"},
            {"TodoMarker", "J0902"},
            {"UnclosedExecutorOrThread", "J1001"},
        };
        for (String[] row : pack) {
            CatalogEntry e = catalog.lookup("jcheck", row[0]);
            check("catalog:pack:" + row[0], e != null && row[1].equals(e.code()),
                "expected " + row[0] + " -> " + row[1]);
        }

        // The declared implemented set and the catalogued jcheck pack must not drift apart: the
        // MCP capabilities resource derives "where the guarantee ends" from exactly this pair.
        Set<String> catalogued = new TreeSet<>();
        for (CatalogEntry e : catalog.entries()) {
            if ("jcheck".equals(e.source())) {
                catalogued.add(e.code());
            }
        }
        catalogued.removeAll(jcheck.engine.NativeChecks.IMPLEMENTED_HARNESS_CODES);
        eq("catalog:pack-complete", catalogued.toString(),
            new TreeSet<>(jcheck.engine.NativeChecks.IMPLEMENTED_CHECK_CODES).toString());
    }

    // ---- tier assignment ---------------------------------------------------

    private static void tierAssignTests() {
        eq("tier:syntax", Tier.T0, TierAssign.tierFor("javac", "compiler.err.illegal.start.of.expr", -1));
        eq("tier:expected", Tier.T0, TierAssign.tierFor("javac", "compiler.err.expected", -1));
        eq("tier:symbols", Tier.T1, TierAssign.tierFor("javac", "compiler.err.cant.resolve", -1));
        eq("tier:flow", Tier.T2, TierAssign.tierFor("javac", "compiler.err.missing.ret.stmt", -1));
        eq("tier:reachability", Tier.T2, TierAssign.tierFor("javac", "compiler.err.unreachable.stmt", -1));
        eq("tier:lint-warning", Tier.T2, TierAssign.tierFor("javac", "compiler.warn.fallthrough", -1));
        eq("tier:errorprone", Tier.T3, TierAssign.tierFor("errorprone", "SomeCheck", -1));
        eq("tier:nullaway", Tier.T3, TierAssign.tierFor("nullaway", "NullAway", -1));
        eq("tier:checker", Tier.T4, TierAssign.tierFor("checker", "resourceleak", -1));
        eq("tier:spotbugs", Tier.T5, TierAssign.tierFor("spotbugs", "NP_NULL_ON_SOME_PATH", -1));
        eq("tier:catalog-wins", Tier.T2, TierAssign.tierFor("javac", "compiler.err.anything", 2));
    }

    // ---- source text and spans ---------------------------------------------

    private static void sourceTextTests() {
        SourceText t = SourceText.of("alpha\nbeta\ngamma\n");
        eq("sourcetext:lines", 4, t.lineCount());
        eq("sourcetext:line1", "alpha", t.line(1));
        eq("sourcetext:line2", "beta", t.line(2));
        eq("sourcetext:line-out-of-range", "", t.line(99));
        eq("sourcetext:line-of-0", 1, t.lineOf(0));
        eq("sourcetext:line-of-6", 2, t.lineOf(6));
        eq("sourcetext:line-of-11", 3, t.lineOf(11));
        eq("sourcetext:col-of-6", 1, t.colOf(6));
        eq("sourcetext:col-of-8", 3, t.colOf(8));
        eq("sourcetext:negative-clamp", 1, t.lineOf(-5));
    }

    private static void spanResolverTests() {
        SpanResolver r = new SpanResolver();
        r.put("A.java", "class A {\n  int x = 1;\n}\n");
        Span s = r.span("A.java", null, 16, 17, -1, -1);
        eq("span:file", "A.java", s.file());
        eq("span:line", 2, s.lineStart());
        eq("span:col", 7, s.colStart());
        eq("span:single-line", false, s.isMultiLine());

        Span whole = r.span("A.java", null, 0, 12, -1, -1);
        eq("span:multiline", true, whole.isMultiLine());
        eq("span:multiline-end-line", 2, whole.lineEnd());

        Span unknown = r.span("missing.java", null, -1, -1, 12, 4);
        eq("span:fallback-line", 12, unknown.lineStart());
        eq("span:fallback-col", 4, unknown.colStart());
    }

    // ---- toml and config ---------------------------------------------------

    private static void tomlTests() {
        String text = String.join("\n",
            "# a comment",
            "[project]",
            "build   = \"gradle\"            # trailing comment",
            "jdk     = 21",
            "modules = [\"core\", \"server\"]",
            "",
            "[profile]",
            "name = \"strict\"",
            "",
            "[errorprone]",
            "enabled = true",
            "disabled = [\"Slf4jFormatShouldBeConst\"]",
            "",
            "[bytecode]",
            "spotbugs = { effort = \"max\", threshold = \"medium\", findsecbugs = true }",
            "",
            "[lints]",
            "J0301 = \"deny\"",
            "J0902 = \"warn\"",
            "");

        Map<String, Object> root = Toml.parse(text);
        Map<String, Object> project = Toml.table(root, "project");
        eq("toml:string", "gradle", Toml.str(project, "build", null));
        eq("toml:int", Integer.valueOf(21), Toml.integer(project, "jdk", null));
        eq("toml:array", List.of("core", "server"), Toml.strList(project, "modules", List.of()));
        eq("toml:nested-string", "strict", Toml.str(Toml.table(root, "profile"), "name", null));
        eq("toml:bool", Boolean.TRUE, Toml.bool(Toml.table(root, "errorprone"), "enabled", null));

        Map<String, Object> spotbugs = Toml.table(Toml.table(root, "bytecode"), "spotbugs");
        eq("toml:inline-table-effort", "max", Toml.str(spotbugs, "effort", null));
        eq("toml:inline-table-bool", Boolean.TRUE, Toml.bool(spotbugs, "findsecbugs", null));

        eq("toml:lints-table", "deny", Toml.str(Toml.table(root, "lints"), "J0301", null));
        eq("toml:missing-key-default", "fallback", Toml.str(project, "nope", "fallback"));
    }

    private static void configTests() {
        Map<String, Object> root = Toml.parse(String.join("\n",
            "[project]",
            "build = \"maven\"",
            "release = 17",
            "",
            "[profile]",
            "name = \"standard\"",
            "",
            "[checkers.per-module]",
            "core = [\"resourceleak\", \"index\"]",
            "",
            "[tests]",
            "timeout_seconds = 25",
            "",
            "[llm]",
            "max_diagnostics = 7",
            ""));

        JcheckConfig c = JcheckConfig.fromToml(root);
        eq("config:build", "maven", c.build);
        eq("config:release", Integer.valueOf(17), c.release);
        eq("config:profile", "standard", c.profile);
        eq("config:checkers-core", List.of("resourceleak", "index"), c.checkersPerModule.get("core"));
        eq("config:timeout", 25, c.testTimeoutSeconds);
        eq("config:llm-max", 7, c.llmMaxDiagnostics);
        check("config:checker-framework-implied", c.anyCheckerConfigured(),
            "a per-module checker list should imply the Checker Framework is configured");
        check("config:errorprone-default-off", !JcheckConfig.defaults().errorProne,
            "error-prone must default off while the plugin jars are not wired up");
    }

    // ---- level policy ------------------------------------------------------

    private static void levelPolicyTests() {
        Diagnostic warning = Diagnostic.builder("J0706").level(Level.WARNING)
            .message("m").tier(Tier.T3).build();

        eq("policy:strict-promotes", Level.ERROR,
            LevelPolicy.forProfile("strict", false).apply(warning).level());
        eq("policy:standard-keeps", Level.WARNING,
            LevelPolicy.forProfile("standard", false).apply(warning).level());
        eq("policy:lenient-demotes", Level.NOTE,
            LevelPolicy.forProfile("lenient", false).apply(warning).level());
        eq("policy:deny-warnings", Level.ERROR,
            LevelPolicy.forProfile("standard", true).apply(warning).level());

        Diagnostic error = Diagnostic.builder("J0203").level(Level.ERROR).message("m").build();
        eq("policy:strict-keeps-error", Level.ERROR,
            LevelPolicy.forProfile("strict", false).apply(error).level());

        LevelPolicy allow = LevelPolicy.builder("strict").allow("J0706").build();
        eq("policy:allow-drops", null, allow.apply(warning));

        LevelPolicy deny = LevelPolicy.builder("standard").deny("J0706").build();
        eq("policy:deny-overrides-profile", Level.ERROR, deny.apply(warning).level());

        LevelPolicy warn = LevelPolicy.builder("strict").warn("J0706").build();
        eq("policy:warn-overrides-profile", Level.WARNING, warn.apply(warning).level());
    }

    // ---- cascade filter ----------------------------------------------------

    private static void cascadeTests() {
        Diagnostic root = Diagnostic.builder("J0205")
            .source("javac").sourceKey("compiler.err.cant.resolve.location")
            .level(Level.ERROR).tier(Tier.T1).seq(0)
            .message("cannot find symbol")
            .raw("cannot find symbol\n  symbol:   class Missing\n  location: class demo.U")
            .note("symbol:   class Missing")
            .primary(new Span("U.java", 5, 9, 5, 16))
            .build();

        Diagnostic dependent = Diagnostic.builder("J0210")
            .source("javac").sourceKey("compiler.err.incompatible.types")
            .level(Level.ERROR).tier(Tier.T1).seq(1)
            .message("incompatible types: Missing cannot be converted to String")
            .primary(new Span("U.java", 6, 16, 6, 30))
            .build();

        Diagnostic unrelated = Diagnostic.builder("J0210")
            .source("javac").sourceKey("compiler.err.incompatible.types")
            .level(Level.ERROR).tier(Tier.T1).seq(2)
            .message("incompatible types: int cannot be converted to String")
            .primary(new Span("U.java", 7, 16, 7, 30))
            .build();

        CascadeFilter.Result r = CascadeFilter.apply(List.of(root, dependent, unrelated));
        eq("cascade:keeps-root-and-unrelated", 2, r.kept.size());
        eq("cascade:drops-dependent", 1, r.dropped.size());
        eq("cascade:dropped-is-dependent", "incompatible types: Missing cannot be converted to String",
            r.dropped.get(0).message());

        Diagnostic syntax = Diagnostic.builder("J0104")
            .source("javac").sourceKey("compiler.err.illegal.start.of.expr")
            .level(Level.ERROR).tier(Tier.T0).seq(3)
            .message("illegal start of expression")
            .raw("illegal start of expression")
            .build();
        Diagnostic other = Diagnostic.builder("J0210")
            .source("javac").sourceKey("compiler.err.incompatible.types")
            .level(Level.ERROR).tier(Tier.T0).seq(4)
            .message("incompatible types: Missing cannot be converted to String")
            .primary(new Span("S.java", 5, 1, 5, 4))
            .build();
        CascadeFilter.Result r2 = CascadeFilter.apply(List.of(root, syntax, other));
        check("cascade:never-drops-tier0", r2.kept.contains(other),
            "a T0 diagnostic must never be dropped as a cascade");

        Diagnostic missingReturn = Diagnostic.builder("J0203")
            .source("javac").sourceKey("compiler.err.missing.ret.stmt")
            .level(Level.ERROR).tier(Tier.T2).seq(5)
            .message("missing return statement").raw("missing return statement").build();
        Diagnostic incompatible = Diagnostic.builder("J0210")
            .source("javac").sourceKey("compiler.err.incompatible.types")
            .level(Level.ERROR).tier(Tier.T2).seq(6)
            .message("incompatible types: String cannot be converted to int")
            .raw("incompatible types: String cannot be converted to int").build();
        CascadeFilter.Result r3 = CascadeFilter.apply(List.of(missingReturn, incompatible));
        eq("cascade:keeps-when-no-unresolved-names", 2, r3.kept.size());
    }

    // ---- dedupe ------------------------------------------------------------

    private static void dedupeTests() {
        Catalog catalog = Catalog.loadDefault();

        Diagnostic a = Diagnostic.builder("J0205").source("javac")
            .sourceKey("compiler.err.cant.resolve.location").level(Level.ERROR).tier(Tier.T1)
            .message("cannot find symbol").note("symbol:   class Missing")
            .primary(new Span("U.java", 5, 9, 5, 16)).seq(0).build();
        Diagnostic b = Diagnostic.builder("J0205").source("javac")
            .sourceKey("compiler.err.cant.resolve.location").level(Level.ERROR).tier(Tier.T1)
            .message("cannot find symbol").note("symbol:   class Missing")
            .primary(new Span("U.java", 5, 25, 5, 32)).seq(1).build();
        List<Diagnostic> merged = Dedupe.apply(List.of(a, b), catalog);
        eq("dedupe:same-line-same-detail-merged", 1, merged.size());
        eq("dedupe:loser-kept-as-related", 1, merged.get(0).related().size());

        Diagnostic c = Diagnostic.builder("J0205").source("javac")
            .sourceKey("compiler.err.cant.resolve.location").level(Level.ERROR).tier(Tier.T1)
            .message("cannot find symbol").note("symbol:   class Other")
            .primary(new Span("U.java", 5, 25, 5, 32)).seq(2).build();
        List<Diagnostic> notMerged = Dedupe.apply(List.of(a, c), catalog);
        eq("dedupe:different-symbol-not-merged", 2, notMerged.size());

        // Cross-tool duplicates share a catalog group; the higher-fidelity source must win.
        Map<String, List<CatalogEntry>> byGroup = new LinkedHashMap<>();
        for (CatalogEntry e : catalog.entries()) {
            byGroup.computeIfAbsent(e.group(), k -> new ArrayList<>()).add(e);
        }
        CatalogEntry[] pair = null;
        for (List<CatalogEntry> list : byGroup.values()) {
            if (list.size() < 2) {
                continue;
            }
            for (int i = 0; i < list.size() && pair == null; i++) {
                for (int j = i + 1; j < list.size() && pair == null; j++) {
                    if (!list.get(i).source().equals(list.get(j).source())
                        && !"javac".equals(list.get(i).source())) {
                        pair = new CatalogEntry[] {list.get(i), list.get(j)};
                    }
                }
            }
            if (pair != null) {
                break;
            }
        }
        if (pair == null) {
            check("dedupe:shared-group-exists", false, "no cross-tool shared group in the catalog");
        } else {
            Diagnostic x = Diagnostic.builder(pair[0].code()).source(pair[0].source())
                .sourceKey(pair[0].sourceKey()).level(Level.WARNING).tier(pair[0].tier())
                .message("resource leaks here").primary(new Span("R.java", 10, 5, 10, 20)).seq(3).build();
            Diagnostic y = Diagnostic.builder(pair[1].code()).source(pair[1].source())
                .sourceKey(pair[1].sourceKey()).level(Level.WARNING).tier(pair[1].tier())
                .message("this resource is never closed")
                .primary(new Span("R.java", 10, 5, 10, 20)).seq(4).build();
            List<Diagnostic> crossTool = Dedupe.apply(List.of(x, y), catalog);
            eq("dedupe:cross-tool-same-group-merged", 1, crossTool.size());
            eq("dedupe:cross-tool-group", pair[0].group(), pair[1].group());
        }
    }

    // ---- renderers ---------------------------------------------------------

    private static void rendererTests() throws IOException {
        eq("renderers:human", "human", Renderers.forFormat("human").format());
        eq("renderers:llm", "llm", Renderers.forFormat("llm").format());
        eq("renderers:json", "json", Renderers.forFormat("json").format());
        eq("renderers:sarif", "sarif", Renderers.forFormat("sarif").format());
        eq("renderers:null-is-human", "human", Renderers.forFormat(null).format());
        try {
            Renderers.forFormat("xml");
            check("renderers:rejects-unknown", false, "expected an exception");
        } catch (IllegalArgumentException e) {
            contains("renderers:rejects-unknown", e.getMessage(), "unknown message format");
        }

        eq("renderers:gutter-width-small", 2, HumanRenderer.gutterWidth(new Span("f", 7, 1, 7, 2)));
        eq("renderers:gutter-width-large", 3, HumanRenderer.gutterWidth(new Span("f", 123, 1, 123, 2)));

        Diagnostic d = Diagnostic.builder("J0706").source("jcheck").sourceKey("LocalCanBeFinal")
            .level(Level.WARNING).tier(Tier.T3)
            .message("local variable `x` can be `final`")
            .primary(new Span("Foo.java", 3, 9, 3, 18))
            .label(new Span("Foo.java", 3, 9, 3, 18), "declared here")
            .help("add the final modifier")
            .suggestion(new Suggestion(Applicability.MACHINE_APPLICABLE, "make `x` final",
                List.of(new Edit(new Span("Foo.java", 3, 9, 3, 9), "final "))))
            .build();
        CheckReport report = new CheckReport(List.of(d), List.of(), List.of(),
            cleanTiers(Tier.T3), List.of(), List.of());

        // A real file on disk so the source-excerpt path is exercised, not the fallback.
        Path tmp = Files.createTempDirectory("jcheck-render-");
        try {
            Files.writeString(tmp.resolve("Foo.java"),
                "class Foo {\n  int y = 0;\n        String x = \"a\";\n}\n");

            String human = new HumanRenderer().render(report,
                RenderOptions.defaults().withRoot(tmp.toString()));
            contains("human:header", human, "warning[J0706]: local variable `x` can be `final`");
            contains("human:arrow", human, "--> Foo.java:3:9");
            contains("human:source-excerpt", human, " 3 |         String x = \"a\";");
            contains("human:carets", human, "^^^^^^^^^");
            contains("human:label", human, "declared here");
            contains("human:help", human, "= help: add the final modifier");
            contains("human:source", human, "= source: jcheck (LocalCanBeFinal)");
            contains("human:count-line", human, "jcheck: 0 errors, 1 warning.");

            String llm = new LlmRenderer().render(report,
                RenderOptions.forFormat("llm").withRoot(tmp.toString()));
            contains("llm:header", llm, "warning[J0706]");
            contains("llm:context", llm, "  String x = \"a\";");
            contains("llm:diff-old", llm, "-         String x = \"a\";");
            contains("llm:diff-new", llm, "+         final String x = \"a\";");
            contains("llm:fix", llm, "fix (MachineApplicable): make `x` final");
            contains("llm:tail", llm, "'jcheck explain <code>' for details.");
            notContains("llm:no-ansi", llm, "\u001b[");

            // The cap must be enforced and advertised.
            CheckReport big = new CheckReport(List.of(d, d, d, d, d), List.of(), List.of(),
                cleanTiers(Tier.T3), List.of(), List.of());
            String capped = new LlmRenderer().render(big,
                RenderOptions.forFormat("llm").withMaxDiagnostics(2));
            contains("llm:cap-omitted", capped, "3 more omitted");
        } finally {
            deleteRecursively(tmp);
        }

        eq("llm:apply-edit-insert",
            "  final String s = \"a\";",
            LlmRenderer.applyEditToLine("  String s = \"a\";",
                new Edit(new Span("f", 1, 3, 1, 3), "final ")));
        eq("llm:apply-edit-replace",
            "  int y = 2;",
            LlmRenderer.applyEditToLine("  int x = 2;",
                new Edit(new Span("f", 1, 7, 1, 8), "y")));
    }

    private static void jsonTests() {
        Diagnostic d = Diagnostic.builder("J0203").source("javac")
            .sourceKey("compiler.err.missing.ret.stmt").level(Level.ERROR).tier(Tier.T2)
            .message("missing return statement")
            .primary(new Span("M.java", 4, 29, 4, 30))
            .label(new Span("M.java", 4, 29, 4, 30), "missing return")
            .note("a note with a \"quote\" and a \\ backslash")
            .help("every path must return a value")
            .build();
        CheckReport report = new CheckReport(List.of(d), List.of(), List.of("Foo.java:3: @Unsafe"),
            cleanTiers(Tier.T2), List.of("a tool note"), List.of());

        String out = new JsonRenderer().render(report, RenderOptions.defaults());
        String[] lines = out.strip().split("\n");
        eq("json:two-lines", 2, lines.length);
        for (int i = 0; i < lines.length; i++) {
            try {
                Json.validate(lines[i]);
            } catch (RuntimeException e) {
                check("json:line-" + i + "-valid", false, e.getMessage() + " in " + lines[i]);
            }
        }
        check("json:escapes-quote", lines[0].contains("\\\"quote\\\""), "quote was not escaped");
        check("json:escapes-backslash", lines[0].contains("\\\\ backslash"), "backslash was not escaped");
        contains("json:code", lines[0], "\"code\":\"J0203\"");
        contains("json:primary-span", lines[0], "\"line_start\":4");
        contains("json:summary-schema", lines[1], "\"schema_version\":1");
        contains("json:summary-dropped", lines[1], "\"dropped_cascades\":0");
        contains("json:summary-suppressions", lines[1], "\"suppressions\":[\"Foo.java:3: @Unsafe\"]");
        contains("json:summary-tier-name", lines[1], "\"analysis_reached_name\":");
    }

    private static void sarifTests() {
        Diagnostic d = Diagnostic.builder("J0706").source("jcheck").sourceKey("LocalCanBeFinal")
            .level(Level.WARNING).tier(Tier.T3).message("local variable `x` can be `final`")
            .primary(new Span("Foo.java", 3, 9, 3, 18)).build();
        CheckReport report = new CheckReport(List.of(d), List.of(), List.of(),
            cleanTiers(Tier.T3), List.of(), List.of());

        String sarif = new SarifRenderer().render(report, RenderOptions.defaults());
        try {
            Json.validate(sarif.strip());
        } catch (RuntimeException e) {
            check("sarif:valid-json", false, e.getMessage());
        }
        contains("sarif:version", sarif, "\"version\":\"2.1.0\"");
        contains("sarif:schema", sarif, "\"$schema\"");
        contains("sarif:driver-name", sarif, "\"name\":\"jcheck\"");
        contains("sarif:rule-id", sarif, "\"id\":\"J0706\"");
        contains("sarif:result-level", sarif, "\"level\":\"warning\"");
        contains("sarif:region", sarif, "\"startLine\":3");
        contains("sarif:uri", sarif, "\"uri\":\"Foo.java\"");
    }

    // ---- fix ---------------------------------------------------------------

    private static void fixApplierTests() throws IOException {
        eq("fix:offset-line1", 0, FixApplier.offsetOf("abc\n", 1, 1));
        eq("fix:offset-line2", 5, FixApplier.offsetOf("abc\ndef\n", 2, 2));
        eq("fix:offset-clamped", 8, FixApplier.offsetOf("abc\ndef\n", 2, 999));

        Path tmp = Files.createTempDirectory("jcheck-fixtest-");
        try {
            Path src = tmp.resolve("src/Foo.java");
            Files.createDirectories(src.getParent());
            Files.writeString(src, "class Foo {\n  String s = \"a\";\n}\n");

            Diagnostic d = Diagnostic.builder("J0706").source("jcheck").sourceKey("LocalCanBeFinal")
                .level(Level.WARNING).tier(Tier.T3).message("can be final")
                .primary(new Span("src/Foo.java", 2, 3, 2, 20))
                .suggestion(new Suggestion(Applicability.MACHINE_APPLICABLE, "make it final",
                    List.of(new Edit(new Span("src/Foo.java", 2, 3, 2, 3), "final "))))
                .build();

            FixApplier.Result r = FixApplier.apply(tmp, List.of(d), false);
            eq("fix:one-file", 1, r.applied.size());
            eq("fix:one-edit", 1, r.editCount);
            contains("fix:applied", Files.readString(src), "  final String s = \"a\";");

            FixApplier.revert(tmp, r.backups);
            eq("fix:reverted", "class Foo {\n  String s = \"a\";\n}\n", Files.readString(src));

            Diagnostic maybe = Diagnostic.builder("J0301").source("jcheck").sourceKey("UncheckedArithmetic")
                .level(Level.ERROR).tier(Tier.T3).message("overflow")
                .primary(new Span("src/Foo.java", 2, 3, 2, 20))
                .suggestion(new Suggestion(Applicability.MAYBE_INCORRECT, "maybe",
                    List.of(new Edit(new Span("src/Foo.java", 2, 3, 2, 3), "/*x*/"))))
                .build();
            FixApplier.Result skipped = FixApplier.apply(tmp, List.of(maybe), false);
            eq("fix:maybe-incorrect-skipped", 0, skipped.applied.size());
            eq("fix:maybe-incorrect-reported", 1, skipped.skipped.size());
        } finally {
            deleteRecursively(tmp);
        }
    }

    // ---- junit xml ---------------------------------------------------------

    private static void junitXmlTests() throws IOException {
        Path tmp = Files.createTempDirectory("jcheck-junit-");
        try {
            Path test = tmp.resolve("src/test/java/demo/CartTest.java");
            Files.createDirectories(test.getParent());
            Files.writeString(test, "package demo;\nclass CartTest { }\n");

            Path xml = tmp.resolve("TEST-demo.CartTest.xml");
            Files.writeString(xml, String.join("\n",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<testsuite name=\"demo.CartTest\" tests=\"2\" failures=\"1\">",
                "  <testcase name=\"total\" classname=\"demo.CartTest\">",
                "    <failure message=\"expected: &lt;7&gt; but was: &lt;8&gt;\">"
                    + "org.opentest4j.AssertionFailedError: expected: &lt;7&gt; but was: &lt;8&gt;",
                "\tat demo.CartTest.total(CartTest.java:12)",
                "\tat java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)",
                "\tat org.junit.platform.engine.support.hierarchical.NodeTestTask.execute(NodeTestTask.java:95)",
                "    </failure>",
                "  </testcase>",
                "  <testcase name=\"ok\" classname=\"demo.CartTest\"/>",
                "</testsuite>",
                ""));

            List<Diagnostic> diags = JUnitXmlParser.parseFile(xml, tmp, 0);
            eq("junit:one-failure", 1, diags.size());
            Diagnostic d = diags.get(0);
            eq("junit:code", "J0901", d.code());
            eq("junit:tier", Tier.T5, d.tier());
            eq("junit:level", Level.ERROR, d.level());
            contains("junit:message", d.message(), "demo.CartTest.total failure");
            contains("junit:message-detail", d.message(), "expected: <7> but was: <8>");
            check("junit:primary-span", d.primary() != null
                && d.primary().lineStart() == 12, "expected line 12, got " + d.primary());
            check("junit:project-frame-first", !d.notes().isEmpty()
                && d.notes().get(0).contains("CartTest.java:12"),
                "first note should be the project frame, got " + d.notes());

            List<String> ordered = JUnitXmlParser.orderFrames(String.join("\n",
                "\tat org.junit.jupiter.engine.Foo(Foo.java:1)",
                "\tat demo.CartTest.total(CartTest.java:12)",
                "\tat java.base/java.lang.Thread.run(Thread.java:1)"), tmp);
            check("junit:ordering-puts-project-first",
                !ordered.isEmpty() && ordered.get(0).contains("CartTest.java:12"),
                "ordered=" + ordered);

            eq("junit:missing-dir-is-empty", 0,
                JUnitXmlParser.parseDirectory(tmp.resolve("nope"), tmp).size());
        } finally {
            deleteRecursively(tmp);
        }
    }

    // ---- explain -----------------------------------------------------------

    private static void explainTests(Path repoRoot) {
        eq("explain:normalize-201", "J0201", Explainer.normalize("201"));
        eq("explain:normalize-lower", "J0201", Explainer.normalize("j0201"));
        eq("explain:normalize-passthrough", "J0201", Explainer.normalize("J0201"));
        eq("explain:normalize-invalid", null, Explainer.normalize("nonsense"));
        eq("explain:normalize-empty", null, Explainer.normalize("   "));

        Explainer.Result ok = Explainer.explain(repoRoot.toString(), "J0706");
        eq("explain:known-code-exit", 0, ok.exitCode);
        contains("explain:known-code-body", ok.text, "J0706");

        Explainer.Result unknown = Explainer.explain(repoRoot.toString(), "J9999");
        eq("explain:unknown-code-exit", 2, unknown.exitCode);
        contains("explain:unknown-code-message", unknown.text, "unknown diagnostic code");
    }

    // ---- init --------------------------------------------------------------

    private static void initializerTests() throws IOException {
        contains("init:template-has-project", Initializer.template("gradle", 21), "[project]");
        contains("init:template-build", Initializer.template("gradle", 21), "build   = \"gradle\"");
        contains("init:template-lints", Initializer.template("gradle", 21), "[lints]");
        contains("init:agent-snippet",
            Initializer.agentSnippet(), "jcheck check --message-format=llm");
        contains("init:agent-snippet-markers", Initializer.agentSnippet(), "<!-- jcheck:begin -->");

        Path tmp = Files.createTempDirectory("jcheck-init-");
        try {
            Initializer.Result first = Initializer.run(tmp, true, false);
            eq("init:exit", 0, first.exitCode);
            check("init:wrote-config", Files.isRegularFile(tmp.resolve("jcheck.toml")),
                "jcheck.toml missing");
            check("init:wrote-agents", Files.isRegularFile(tmp.resolve("AGENTS.md")),
                "AGENTS.md missing");
            contains("init:agents-content",
                Files.readString(tmp.resolve("AGENTS.md")), "jcheck explain <code>");

            String before = Files.readString(tmp.resolve("jcheck.toml"));
            Files.writeString(tmp.resolve("jcheck.toml"), before + "\n# hand-edited\n");
            Initializer.Result second = Initializer.run(tmp, true, false);
            eq("init:second-exit", 0, second.exitCode);
            contains("init:never-overwrites", Files.readString(tmp.resolve("jcheck.toml")), "# hand-edited");
            contains("init:no-duplicate-agent-block", second.stdout, "already present in AGENTS.md");
        } finally {
            deleteRecursively(tmp);
        }
    }

    // ---- build model -------------------------------------------------------

    private static void buildModelTests() throws IOException {
        Path tmp = Files.createTempDirectory("jcheck-build-");
        try {
            eq("build:flat", "flat", BuildModel.detectBuildTool(tmp));
            Files.writeString(tmp.resolve("pom.xml"), "<project/>");
            eq("build:maven", "maven", BuildModel.detectBuildTool(tmp));
            Files.delete(tmp.resolve("pom.xml"));
            Files.writeString(tmp.resolve("build.gradle"), "plugins { }");
            eq("build:gradle", "gradle", BuildModel.detectBuildTool(tmp));

            Path src = tmp.resolve("src/main/java/demo/A.java");
            Files.createDirectories(src.getParent());
            Files.writeString(src, "package demo;\nclass A { }\n");
            Files.writeString(tmp.resolve("src/main/java/demo/package-info.java"), "package demo;\n");

            BuildModel m = BuildModel.resolve(tmp, JcheckConfig.defaults(),
                null, null, null, null);
            eq("build:sources-found", 2, m.sources.size());
            eq("build:root", tmp.toAbsolutePath().normalize(), m.root);
            check("build:classpath-has-output", m.classpath.stream()
                .anyMatch(p -> p.toString().endsWith("build/classes/java/main"))
                || m.classpath.isEmpty(),
                "unexpected classpath: " + m.classpath);
        } finally {
            deleteRecursively(tmp);
        }
    }

    // ---- helpers -----------------------------------------------------------

    private static TierReport cleanTiers(int reached) {
        List<TierReport.Entry> entries = new ArrayList<>();
        for (int t = 0; t <= Tier.MAX; t++) {
            boolean ran = t <= reached;
            entries.add(new TierReport.Entry(t, ran, ran ? "" : "not enabled"));
        }
        return new TierReport(entries, false);
    }

    static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    // ---- a strict JSON validator, so the JSON tests are worth something -----

    static final class Json {

        private Json() {
        }

        static void validate(String text) {
            new Parser(text).parse();
        }

        private static final class Parser {
            private final String s;
            private int i;

            Parser(String s) {
                this.s = s;
            }

            void parse() {
                ws();
                if (i >= s.length()) {
                    throw err("empty document");
                }
                value();
                ws();
                if (i != s.length()) {
                    throw err("trailing data");
                }
            }

            private void value() {
                ws();
                if (i >= s.length()) {
                    throw err("unexpected end of input");
                }
                char c = s.charAt(i);
                switch (c) {
                    case '{': object(); break;
                    case '[': array(); break;
                    case '"': string(); break;
                    case 't': literal("true"); break;
                    case 'f': literal("false"); break;
                    case 'n': literal("null"); break;
                    default: number();
                }
            }

            private void object() {
                expect('{');
                ws();
                if (peek() == '}') {
                    i++;
                    return;
                }
                while (true) {
                    ws();
                    string();
                    ws();
                    expect(':');
                    value();
                    ws();
                    char c = peek();
                    if (c == ',') {
                        i++;
                        continue;
                    }
                    expect('}');
                    return;
                }
            }

            private void array() {
                expect('[');
                ws();
                if (peek() == ']') {
                    i++;
                    return;
                }
                while (true) {
                    value();
                    ws();
                    char c = peek();
                    if (c == ',') {
                        i++;
                        continue;
                    }
                    expect(']');
                    return;
                }
            }

            private void string() {
                expect('"');
                while (true) {
                    if (i >= s.length()) {
                        throw err("unterminated string");
                    }
                    char c = s.charAt(i++);
                    if (c == '"') {
                        return;
                    }
                    if (c == '\\') {
                        if (i >= s.length()) {
                            throw err("truncated escape");
                        }
                        char e = s.charAt(i++);
                        if ("\"\\/bfnrtu".indexOf(e) < 0) {
                            throw err("invalid escape \\" + e);
                        }
                    } else if (c < 0x20) {
                        throw err("raw control character in string");
                    }
                }
            }

            private void literal(String lit) {
                if (!s.startsWith(lit, i)) {
                    throw err("expected " + lit);
                }
                i += lit.length();
            }

            private void number() {
                int start = i;
                if (peek() == '-') {
                    i++;
                }
                while (i < s.length() && Character.isDigit(s.charAt(i))) {
                    i++;
                }
                if (peek() == '.') {
                    i++;
                    while (i < s.length() && Character.isDigit(s.charAt(i))) {
                        i++;
                    }
                }
                if (peek() == 'e' || peek() == 'E') {
                    i++;
                    if (peek() == '+' || peek() == '-') {
                        i++;
                    }
                    while (i < s.length() && Character.isDigit(s.charAt(i))) {
                        i++;
                    }
                }
                if (i == start) {
                    throw err("invalid number");
                }
            }

            private char peek() {
                return i < s.length() ? s.charAt(i) : '\0';
            }

            private void expect(char c) {
                if (peek() != c) {
                    throw err("expected '" + c + "'");
                }
                i++;
            }

            private void ws() {
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                    i++;
                }
            }

            private IllegalArgumentException err(String message) {
                return new IllegalArgumentException(message + " at offset " + i);
            }
        }
    }
}
