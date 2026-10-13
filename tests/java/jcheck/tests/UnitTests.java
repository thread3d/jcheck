package jcheck.tests;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import jcheck.build.BuildModel;
import jcheck.build.DependencyResolver;
import jcheck.build.IncrementalCache;
import jcheck.build.IncrementalPlan;
import jcheck.catalog.Catalog;
import jcheck.catalog.CatalogEntry;
import jcheck.cli.CheckCommand;
import jcheck.cli.CheckRequest;
import jcheck.cli.FixCommand;
import jcheck.cli.InferCommand;
import jcheck.cli.JcstressCommand;
import jcheck.cli.RewriteCommand;
import jcheck.config.JcheckConfig;
import jcheck.config.Toml;
import jcheck.daemon.Daemon;
import jcheck.daemon.DaemonClient;
import jcheck.engine.CascadeFilter;
import jcheck.engine.Dedupe;
import jcheck.engine.LevelPolicy;
import jcheck.engine.SourceText;
import jcheck.engine.SpanResolver;
import jcheck.engine.TierAssign;
import jcheck.explain.Explainer;
import jcheck.fix.FixApplier;
import jcheck.init.Initializer;
import jcheck.lsp.LspServer;
import jcheck.mcp.ProjectState;
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
import jcheck.util.JsonWriter;

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
        incrementalPlanTests();
        multiModuleTests();
        mavenResolverTests();
        suggestionRoundTripTests();
        daemonTests();
        lspTests();
        inferIntegrationTests();
        corpusTests();
        daemonOomTests();
        errorProneIntegrationTests();
    }

    // ---- suggestion round-trip ---------------------------------------------

    /**
     * The SPEC gate for suggestions: every machine-applicable fix must apply, and the re-check must
     * no longer report that code.
     */
    private static void suggestionRoundTripTests() throws Exception {
        Path tmp = Files.createTempDirectory("jcheck-roundtrip-");
        Path src = tmp.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Files.writeString(src.resolve("F.java"),
            "package demo;\n"
            + "public final class F {\n"
            + "    static int narrow(long total) {\n"
            + "        return (int) total;\n"
            + "    }\n"
            + "    static int sum(int a, int b) {\n"
            + "        String label = \"x\";\n"
            + "        return a + b + label.length();\n"
            + "    }\n"
            + "}\n", StandardCharsets.UTF_8);

        CheckRequest req = new CheckRequest();
        req.root = tmp.toString();
        req.profile = "standard";
        CheckCommand.Analysis before = CheckCommand.analyze(req);
        check("fix:analyze-ok", before.ok(), before.error);

        Set<String> codes = new HashSet<>();
        for (Diagnostic d : before.report.diagnostics) {
            codes.add(d.code);
        }
        check("fix:has-j0302", codes.contains("J0302"), "codes=" + codes);
        check("fix:has-j0706", codes.contains("J0706"), "codes=" + codes);

        FixApplier.Result applied = FixApplier.apply(tmp, before.report.diagnostics, false);
        check("fix:applied-edits", applied.editCount >= 2, "edits=" + applied.editCount);

        CheckCommand.Analysis after = CheckCommand.analyze(req);
        check("fix:recheck-ok", after.ok(), after.error);
        Set<String> afterCodes = new HashSet<>();
        for (Diagnostic d : after.report.diagnostics) {
            afterCodes.add(d.code);
        }
        check("fix:j0302-resolved", !afterCodes.contains("J0302"), "codes=" + afterCodes);
        check("fix:j0706-resolved", !afterCodes.contains("J0706"), "codes=" + afterCodes);
        deleteRecursively(tmp);
    }

    // ---- daemon ------------------------------------------------------------

    /**
     * One warm daemon across four requests: a missing dependency, a classpath change that must be
     * picked up, a source edit that must not serve a stale report, and two concurrent requests that
     * must agree. Environments without Unix sockets or /tmp execution skip it.
     */
    private static void daemonTests() throws Exception {
        Path root = Files.createTempDirectory("jcheck-daemon-");
        Path src = root.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Path d = src.resolve("D.java");
        Files.writeString(d,
            "package demo;\n"
            + "import dep.Helper;\n"
            + "public final class D {\n"
            + "    public static int one() {\n"
            + "        return Helper.one();\n"
            + "    }\n"
            + "\n"
            + "    static int twice(int x) {\n"
            + "        int y = x * 2;\n"
            + "        return y;\n"
            + "    }\n"
            + "}\n", StandardCharsets.UTF_8);

        boolean started;
        try {
            started = Daemon.spawn(root, 20_000);
        } catch (IOException | RuntimeException e) {
            started = false;
        }
        if (!started) {
            deleteRecursively(root);
            return;
        }
        try {
            check("daemon:ping", DaemonClient.ping(root.toString()), "daemon did not answer a ping");
            CheckRequest req = new CheckRequest();
            req.root = root.toString();
            req.format = "human";

            // The dependency is missing, so T1 fails and T3 is gated off.
            DaemonClient.Response before = DaemonClient.check(root.toString(), req);
            notContains("daemon:classpath-missing", before.stdout, "J0706");

            // Drop the dependency into lib/; the daemon must resolve it on the next request.
            writeJar(root.resolve("lib/dep.jar"), "dep.Helper",
                "package dep;\npublic final class Helper {\n"
                + "    public static int one() {\n        return 1;\n    }\n}\n");
            DaemonClient.Response after = DaemonClient.check(root.toString(), req);
            contains("daemon:classpath-change-picked-up", after.stdout, "J0706");

            // Edit the source; the next request must not serve the previous report.
            Files.writeString(d,
                "package demo;\n"
                + "import dep.Helper;\n"
                + "public final class D {\n"
                + "    public static int one() {\n"
                + "        return Helper.one();\n"
                + "    }\n"
                + "\n"
                + "    static int twice(int x) {\n"
                + "        final int y = x * 2;\n"
                + "        return y;\n"
                + "    }\n"
                + "}\n", StandardCharsets.UTF_8);
            DaemonClient.Response edited = DaemonClient.check(root.toString(), req);
            notContains("daemon:source-change-not-stale", edited.stdout, "J0706");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Callable<DaemonClient.Response> call = () -> DaemonClient.check(root.toString(), req);
                Future<DaemonClient.Response> first = pool.submit(call);
                Future<DaemonClient.Response> second = pool.submit(call);
                DaemonClient.Response a = first.get();
                DaemonClient.Response b = second.get();
                eq("daemon:concurrent-exit", 0, a.exitCode);
                eq("daemon:consistent-output", a.stdout, b.stdout);
                check("daemon:has-tier-line", a.stdout.contains("analysis reached"),
                    "stdout=" + a.stdout);
            } finally {
                pool.shutdownNow();
            }

            // A truncated frame must not take the daemon down; the next ping still answers.
            try (java.nio.channels.SocketChannel raw =
                     java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
                raw.connect(java.net.UnixDomainSocketAddress.of(
                    Daemon.socketPath(root.toString())));
                java.io.DataOutputStream out = new java.io.DataOutputStream(
                    java.nio.channels.Channels.newOutputStream(raw));
                out.writeInt(jcheck.daemon.Protocol.MAGIC);
                out.writeInt(jcheck.daemon.Protocol.OP_CHECK);
                out.writeUTF("");
                out.writeUTF("human");
                out.writeInt(0);
                out.writeInt(-1);
                out.writeUTF("");
                out.flush(); // stop mid-frame
            } catch (IOException ignored) {
                // the daemon may close its side of a broken frame; that is fine
            }
            check("daemon:survives-malformed-request", DaemonClient.ping(root.toString()),
                "daemon died on a malformed request");
        } finally {
            check("daemon:shutdown", DaemonClient.shutdown(root.toString()),
                "daemon did not shut down");
            deleteRecursively(root);
        }
    }

    /** Compiles one class with the system compiler and packs it into a jar. */
    private static void writeJar(Path jar, String className, String source) throws Exception {
        Path work = Files.createTempDirectory("jcheck-jar-");
        Path file = work.resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null,
                StandardCharsets.UTF_8)) {
            compiler.getTask(null, fm, null, List.of("-d", work.toString()), null,
                fm.getJavaFileObjects(file)).call();
        }
        Files.createDirectories(jar.getParent());
        try (java.util.jar.JarOutputStream out =
                 new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            Path compiled = work.resolve(className.replace('.', '/') + ".class");
            out.putNextEntry(new java.util.jar.JarEntry(className.replace('.', '/') + ".class"));
            Files.copy(compiled, out);
            out.closeEntry();
        }
        deleteRecursively(work);
    }

    // ---- multi-module builds -------------------------------------------------

    /**
     * A Gradle or Maven project with several modules has no root {@code src/main/java}; the model
     * must still find every module's sources rather than reporting that there are none.
     */
    private static void multiModuleTests() throws Exception {
        Path tmp = Files.createTempDirectory("jcheck-modules-");
        Path a = tmp.resolve("module-a/src/main/java/demo");
        Path b = tmp.resolve("module-b/src/main/java/demo");
        Path c = tmp.resolve("group/module-c/src/main/java/demo");
        Path t = tmp.resolve("module-b/src/test/java/demo");
        for (Path dir : List.of(a, b, c, t)) {
            Files.createDirectories(dir);
        }
        Files.writeString(a.resolve("A.java"), "package demo;\npublic final class A {}\n");
        Files.writeString(b.resolve("B.java"), "package demo;\npublic final class B {}\n");
        Files.writeString(c.resolve("C.java"), "package demo;\npublic final class C {}\n");
        Files.writeString(t.resolve("BTest.java"), "package demo;\npublic final class BTest {}\n");
        // A build output must be pruned, not treated as a module.
        Path built = tmp.resolve("module-a/build/src/main/java/demo");
        Files.createDirectories(built);
        Files.writeString(built.resolve("Generated.java"), "package demo;\npublic final class Generated {}\n");

        try {
            JcheckConfig config = JcheckConfig.load(tmp);
            BuildModel model = BuildModel.resolve(tmp, config, null, null, null, null);
            List<String> names = model.sources.stream()
                .map(p -> p.getFileName().toString()).sorted().toList();
            check("modules:a", names.contains("A.java"), names.toString());
            check("modules:b", names.contains("B.java"), names.toString());
            check("modules:nested", names.contains("C.java"), names.toString());
            check("modules:test", names.contains("BTest.java"), names.toString());
            check("modules:prunes-build", !names.contains("Generated.java"), names.toString());
            eq("modules:count", 3, model.modules.size());

            // A Maven layout with declared modules and a non-default source directory.
            Path maven = Files.createTempDirectory("jcheck-maven-modules-");
            try {
                Path api = maven.resolve("api/src/java/demo");
                Files.createDirectories(api);
                Files.writeString(api.resolve("Api.java"), "package demo;\npublic final class Api {}\n");
                Files.writeString(maven.resolve("pom.xml"),
                    "<project><modules><module>api</module></modules></project>\n");
                Files.writeString(maven.resolve("api/pom.xml"),
                    "<project><build><sourceDirectory>src/java</sourceDirectory></build></project>\n");
                JcheckConfig mavenConfig = JcheckConfig.load(maven);
                BuildModel mavenModel = BuildModel.resolve(maven, mavenConfig, null, null, null, null);
                List<String> mavenNames = mavenModel.sources.stream()
                    .map(p -> p.getFileName().toString()).toList();
                check("modules:maven-source-dir", mavenNames.contains("Api.java"), mavenNames.toString());
                check("modules:maven-module", mavenModel.modules.size() == 1,
                    "modules=" + mavenModel.modules);
            } finally {
                deleteRecursively(maven);
            }
        } finally {
            deleteRecursively(tmp);
        }
    }

    // ---- real-project corpus ------------------------------------------------
    /**
     * Runs the T3 pack over a real library's sources — Apache Commons Lang's own `-sources` jar —
     * and records diagnostics per kLOC. This is the false-positive guard the curated cases cannot
     * be: a rule that explodes on ordinary code shows up here as a noise-budget failure instead of
     * in a user's first run. Opt-in, because it downloads the sources jar.
     */
    private static void corpusTests() throws Exception {
        if (!"1".equals(System.getenv("JCHECK_TEST_PLUGINS"))) {
            return;
        }
        MavenResolver resolver = MavenResolver.standard(false);
        List<String> coordinates = List.of(
            "org.apache.commons:commons-lang3:3.14.0:sources",
            "com.google.code.gson:gson:2.10.1:sources",
            "org.apache.commons:commons-collections4:4.4:sources",
            "commons-codec:commons-codec:1.16.1:sources",
            "commons-cli:commons-cli:1.6.0:sources");
        int libraries = 0;
        int skipped = 0;
        int totalDiagnostics = 0;
        long totalLines = 0;
        int totalUncatalogued = 0;
        for (String coordinate : coordinates) {
            List<Path> jars = resolver.resolve(List.of(coordinate));
            if (jars.isEmpty()) {
                continue;
            }
            String label = coordinate.split(":")[1];
            Path dir = Files.createTempDirectory("jcheck-corpus-");
            try {
                extractSources(jars.get(0), dir);
                CheckRequest req = new CheckRequest();
                req.root = dir.toString();
                req.profile = "standard";
                CheckCommand.Analysis analysis = CheckCommand.analyze(req);
                check("corpus:analyzes:" + label, analysis.ok(), analysis.error);
                if (!analysis.ok()) {
                    continue;
                }
                if (!analysis.report.tiers.ran(Tier.T3)) {
                    /*
                     * The library itself does not compile on this JDK (Commons Collections 4.4 does
                     * not on 21 or 25). That is the library's incompatibility, not jcheck's, so its
                     * diagnostics are reported but not counted against the guard.
                     */
                    skipped++;
                    System.out.println("corpus: " + label + " did not reach T3 on JDK "
                        + Runtime.version().feature() + " (library does not compile here); skipped");
                    continue;
                }
                int count = analysis.report.diagnostics.size();
                int uncatalogued = 0;
                for (jcheck.model.Diagnostic d : analysis.report.diagnostics) {
                    if (d.code.startsWith("J9")) {
                        uncatalogued++;
                    }
                }
                long lines = 0;
                try (var walk = Files.walk(dir)) {
                    for (Path p : walk.filter(x -> x.toString().endsWith(".java")).toList()) {
                        lines += Files.readAllLines(p).size();
                    }
                }
                double perKloc = lines == 0 ? 0 : count * 1000.0 / lines;
                check("corpus:has-findings:" + label, count > 0,
                    "the pack found nothing in " + label + ", which is suspicious");
                check("corpus:noise-budget:" + label, perKloc < 250,
                    "diagnostics/kLOC=" + Math.round(perKloc) + " over " + lines + " lines");
                System.out.println("corpus: " + label + " " + count + " diagnostics over " + lines
                    + " lines (" + Math.round(perKloc) + "/kLOC), " + uncatalogued + " uncatalogued");
                libraries++;
                totalDiagnostics += count;
                totalLines += lines;
                totalUncatalogued += uncatalogued;
            } finally {
                deleteRecursively(dir);
            }
        }
        check("corpus:libraries", libraries >= 3, "analyzed " + libraries + " libraries");
        check("corpus:uncatalogued-none", totalUncatalogued == 0,
            totalUncatalogued + " uncatalogued upstream key(s)");
        double perKloc = totalLines == 0 ? 0 : totalDiagnostics * 1000.0 / totalLines;
        check("corpus:total-noise-budget", perKloc < 250,
            "diagnostics/kLOC=" + Math.round(perKloc));
        System.out.println("corpus: " + libraries + " libraries (" + skipped
            + " skipped on this JDK), " + totalDiagnostics + " diagnostics over " + totalLines
            + " lines (" + Math.round(perKloc) + "/kLOC)");
    }

    /** Extracts the {@code .java} entries of a jar into a directory. */
    private static void extractSources(Path jar, Path dir) throws IOException {
        try (java.util.jar.JarFile file = new java.util.jar.JarFile(jar.toFile())) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".java")) {
                    continue;
                }
                Path target = dir.resolve(entry.getName());
                Files.createDirectories(target.getParent());
                try (java.io.InputStream in = file.getInputStream(entry)) {
                    Files.copy(in, target);
                }
            }
        }
    }

    // ---- external analyzers ------------------------------------------------

    /**
     * Runs Infer when a binary is available (the CI/workspace copy or {@code $JCHECK_INFER});
     * otherwise it skips, because Infer is a native binary and not every machine has one.
     */
    private static void inferIntegrationTests() throws Exception {
        String configured = System.getenv("JCHECK_INFER");
        Path infer = configured == null || configured.isBlank()
            ? Path.of("/Users/threaded/projects/JCheck/tools/infer-osx-x86_64-v1.2.0/bin/infer")
            : Path.of(configured);
        if (!Files.isExecutable(infer)) {
            return;
        }
        Path tmp = Files.createTempDirectory("jcheck-infer-it-");
        Path src = tmp.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Files.writeString(src.resolve("N.java"),
            "package demo;\npublic final class N {\n"
            + "    static int len() {\n        String s = null;\n        return s.length();\n    }\n}\n",
            StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("jcheck.toml"),
            "[bytecode]\ninfer = true\ninfer_binary = \"" + infer + "\"\n",
            StandardCharsets.UTF_8);
        CheckRequest req = new CheckRequest();
        req.root = tmp.toString();
        req.format = "human";
        CheckCommand.Outcome out = InferCommand.run(req);
        contains("infer:j1401", out.stdout, "J1401");
        contains("infer:source", out.stdout, "source: infer");

        // With [bytecode] infer = true, the ordinary check must run it at T5 too.
        CheckCommand.Outcome check = TestMain.runCase(tmp, "check --profile standard");
        contains("infer:via-check", check.stdout, "J1401");
        contains("infer:via-check-tier", check.stdout, "analysis reached T5");
        deleteRecursively(tmp);
    }

    /**
     * A daemon bound to a tiny heap must answer rather than hang. The request either returns with
     * the out-of-memory report or the connection drops; the test fails only if it blocks, and a
     * fresh daemon with a normal heap must serve the project afterwards. Opt-in because it is slow.
     */
    private static void daemonOomTests() throws Exception {
        if (!"1".equals(System.getenv("JCHECK_TEST_PLUGINS"))) {
            return;
        }
        Path root = Files.createTempDirectory("jcheck-daemon-oom-");
        Path src = root.resolve("src/main/java/demo");
        Files.createDirectories(src);
        StringBuilder big = new StringBuilder("package demo;\npublic final class Big {\n");
        for (int i = 0; i < 20_000; i++) {
            big.append("    int m").append(i).append("() { return ").append(i).append("; }\n");
        }
        big.append("}\n");
        Files.writeString(src.resolve("Big.java"), big.toString(), StandardCharsets.UTF_8);

        boolean started;
        try {
            started = Daemon.spawn(root, 20_000, List.of("-Xmx48m"));
        } catch (IOException | RuntimeException e) {
            started = false;
        }
        if (!started) {
            deleteRecursively(root);
            return;
        }
        try {
            CheckRequest req = new CheckRequest();
            req.root = root.toString();
            req.format = "human";
            Thread worker = new Thread(() -> {
                try {
                    DaemonClient.check(root.toString(), req);
                } catch (IOException ignored) {
                    // the daemon may drop the connection; that is a return, not a hang
                }
            }, "jcheck-oom-probe");
            worker.setDaemon(true);
            worker.start();
            worker.join(120_000);
            boolean returned = !worker.isAlive();
            if (!returned) {
                killDaemon(root);
            }
            check("daemon:oom-request-returns", returned, "the daemon hung for 120s");
        } finally {
            DaemonClient.shutdown(root.toString());
            killDaemon(root);
        }

        boolean restarted = Daemon.spawn(root, 20_000);
        check("daemon:recovers-after-oom", restarted && DaemonClient.ping(root.toString()),
            "no daemon came up after the constrained one");
        DaemonClient.shutdown(root.toString());
        killDaemon(root);
        deleteRecursively(root);
    }

    /** Force-kills a daemon that did not answer, using the pid file it leaves behind. */
    private static void killDaemon(Path root) {
        try {
            Path pid = Daemon.pidPath(root.toString());
            if (Files.isRegularFile(pid)) {
                long handle = Long.parseLong(Files.readString(pid).strip());
                ProcessHandle.of(handle).ifPresent(ProcessHandle::destroyForcibly);
            }
        } catch (IOException | RuntimeException ignored) {
            // nothing to kill
        }
    }

    // ---- incremental planning ----------------------------------------------

    /**
     * The API-signature rule: a body-only change recompiles just that file, a signature change also
     * recompiles its dependents, and a changed classpath invalidates everything.
     */
    private static void incrementalPlanTests() throws Exception {
        String previousCache = System.getProperty("jcheck.cache.dir");
        Path cache = Files.createTempDirectory("jcheck-incremental-cache-");
        System.setProperty("jcheck.cache.dir", cache.toString());
        Path root = Files.createTempDirectory("jcheck-incremental-");
        try {
            Path src = root.resolve("src/main/java/demo");
            Files.createDirectories(src);
            Path a = src.resolve("A.java");
            Path b = src.resolve("B.java");
            Files.writeString(a,
                "package demo;\npublic final class A {\n"
                + "    public static int value() {\n        return 1;\n    }\n}\n",
                StandardCharsets.UTF_8);
            Files.writeString(b,
                "package demo;\npublic final class B {\n"
                + "    public static int use() {\n        return A.value();\n    }\n}\n",
                StandardCharsets.UTF_8);
            Path c = src.resolve("C.java");
            Files.writeString(c,
                "package demo;\npublic final class C {\n"
                + "    public static int alone() {\n        return 3;\n    }\n}\n",
                StandardCharsets.UTF_8);
            List<Path> sources = List.of(a, b, c);

            IncrementalPlan.Plan first = IncrementalPlan.compute(root, sources, List.of(), null,
                List.of(), List.of(), true);
            check("incremental:first-run-is-full", first.full, first.summary);
            IncrementalCache.save(root, first.fingerprint, first.index);
            Files.createDirectories(IncrementalCache.classesDir(root));

            Thread.sleep(20);
            Files.writeString(a,
                "package demo;\npublic final class A {\n"
                + "    public static int value() {\n        return 20;\n    }\n}\n",
                StandardCharsets.UTF_8);
            IncrementalPlan.Plan body = IncrementalPlan.compute(root, sources, List.of(), null,
                List.of(), List.of(), false);
            check("incremental:body-change-recompiles-one",
                !body.full && body.recompile.size() == 1
                    && "A.java".equals(body.recompile.get(0).getFileName().toString()),
                body.summary);

            IncrementalCache.save(root, body.fingerprint, body.index);
            Thread.sleep(20);
            Files.writeString(a,
                "package demo;\npublic final class A {\n"
                + "    public static long value() {\n        return 20;\n    }\n}\n",
                StandardCharsets.UTF_8);
            IncrementalPlan.Plan api = IncrementalPlan.compute(root, sources, List.of(), null,
                List.of(), List.of(), false);
            check("incremental:api-change-adds-dependent",
                !api.full && api.recompile.size() == 2, api.summary);

            IncrementalCache.save(root, api.fingerprint, api.index);
            IncrementalPlan.Plan flags = IncrementalPlan.compute(root, sources,
                List.of(Path.of("/tmp/some.jar")), null, List.of(), List.of(), false);
            check("incremental:classpath-change-is-full", flags.full, flags.summary);

            // A warm session: changing one file must not lose another file's cached finding.
            Path proj = Files.createTempDirectory("jcheck-inc-session-");
            Path psrc = proj.resolve("src/main/java/demo");
            Files.createDirectories(psrc);
            Files.writeString(psrc.resolve("F.java"),
                "package demo;\npublic final class F {\n"
                + "    static int twice(int x) {\n        int y = x * 2;\n        return y;\n    }\n}\n",
                StandardCharsets.UTF_8);
            Files.writeString(psrc.resolve("G.java"),
                "package demo;\npublic final class G {\n"
                + "    static int one() {\n        int z = 1;\n        return z;\n    }\n}\n",
                StandardCharsets.UTF_8);
            CheckRequest sessionRequest = new CheckRequest();
            sessionRequest.root = proj.toString();
            ProjectState state = new ProjectState(proj, sessionRequest);
            CheckReport firstReport = state.report();
            check("incremental:session-initial-findings",
                firstReport.diagnostics.stream().anyMatch(d -> "J0706".equals(d.code)),
                "no J0706 on the first analysis");

            Thread.sleep(20);
            Files.writeString(psrc.resolve("F.java"),
                "package demo;\npublic final class F {\n"
                + "    static int twice(int x) {\n        int y = x * 3;\n        return y;\n    }\n}\n",
                StandardCharsets.UTF_8);
            CheckReport refreshed = state.refresh();
            check("incremental:session-keeps-other-file-finding",
                refreshed.diagnostics.stream().anyMatch(d -> "J0706".equals(d.code)
                    && d.primary != null && d.primary.file().endsWith("G.java")),
                "the finding in the untouched file was lost");
            deleteRecursively(proj);
        } finally {
            if (previousCache == null) {
                System.clearProperty("jcheck.cache.dir");
            } else {
                System.setProperty("jcheck.cache.dir", previousCache);
            }
            deleteRecursively(cache);
            deleteRecursively(root);
        }
    }

    // ---- LSP bridge --------------------------------------------------------

    /** Frames an initialize/didOpen/codeAction/shutdown session and checks the responses. */
    private static void lspTests() throws Exception {
        Path root = Files.createTempDirectory("jcheck-lsp-");
        Path src = root.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Path file = src.resolve("L.java");
        Files.writeString(file,
            "package demo;\n"
            + "public final class L {\n"
            + "    static int twice(int x) {\n"
            + "        int y = x * 2;\n"
            + "        return y;\n"
            + "    }\n"
            + "}\n", StandardCharsets.UTF_8);
        String uri = file.toUri().toString();

        ByteArrayOutputStream messages = new ByteArrayOutputStream();
        frame(messages, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
            + "\"rootUri\":" + JsonWriter.quote(root.toUri().toString()) + "}}");
        frame(messages, "{\"jsonrpc\":\"2.0\",\"method\":\"initialized\",\"params\":{}}");
        frame(messages, "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{"
            + "\"textDocument\":{\"uri\":" + JsonWriter.quote(uri) + ",\"languageId\":\"java\""
            + ",\"version\":1,\"text\":\"\"}}}");
        frame(messages, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/codeAction\","
            + "\"params\":{\"textDocument\":{\"uri\":" + JsonWriter.quote(uri) + "},"
            + "\"range\":{\"start\":{\"line\":0,\"character\":0},\"end\":{\"line\":0,\"character\":0}},"
            + "\"context\":{\"diagnostics\":[]}}}");
        frame(messages, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\"}");
        frame(messages, "{\"jsonrpc\":\"2.0\",\"method\":\"exit\"}");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LspServer.serve(root, new ByteArrayInputStream(messages.toByteArray()), out);
        String response = out.toString(StandardCharsets.UTF_8);
        contains("lsp:initialize-capabilities", response, "\"codeActionProvider\":true");
        contains("lsp:publish-diagnostics", response, "textDocument/publishDiagnostics");
        contains("lsp:diagnostic-code", response, "\"code\":\"J0706\"");
        contains("lsp:quickfix-edit", response, "\"newText\":\"final \"");
        contains("lsp:shutdown-response", response, "\"id\":3");
        deleteRecursively(root);
    }

    private static void frame(ByteArrayOutputStream out, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        out.write(("Content-Length: " + body.length + "\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
        out.write(body);
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
        // Error Prone 2.24.1/NullAway 0.10.18 and SpotBugs 4.8.6 support JDK 17-21. On a newer JDK
        // they cannot run, so those cases are skipped here and the README says to bump the pins.
        boolean analyzers = Runtime.version().feature() <= 21;
        if (analyzers) {
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
        }

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

        if (analyzers) {
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

        if (analyzers) {
        Path fx = Files.createTempDirectory("jcheck-fix-it-");
        Path fxSrc = fx.resolve("src/main/java/demo");
        Files.createDirectories(fxSrc);
        Files.writeString(fxSrc.resolve("Fx.java"),
            "package demo;\n"
            + "public final class Fx {\n"
            + "    boolean same(String a, String b) {\n"
            + "        return a == b;\n"
            + "    }\n"
            + "}\n", StandardCharsets.UTF_8);
        Files.writeString(fx.resolve("jcheck.toml"), "[errorprone]\nenabled = true\n",
            StandardCharsets.UTF_8);
        CheckRequest fxReq = new CheckRequest();
        fxReq.root = fx.toString();
        fxReq.errorProne = true;
        CheckCommand.Outcome fxOut = FixCommand.run(fxReq, false, true, false);
        String patched = Files.readString(fxSrc.resolve("Fx.java"), StandardCharsets.UTF_8);
        check("plugins:fix-error-prone-patch", patched.contains("Objects.equals"), patched);
        eq("plugins:fix-clean-after-patch", 0, fxOut.exitCode);
        deleteRecursively(fx);
        }

        Path jc = Files.createTempDirectory("jcheck-jcstress-it-");
        Path jcSrc = jc.resolve("src/test/java/demo");
        Files.createDirectories(jcSrc);
        Files.writeString(jcSrc.resolve("Racy.java"),
            "package demo;\n"
            + "import org.openjdk.jcstress.annotations.*;\n"
            + "import org.openjdk.jcstress.infra.results.I_Result;\n"
            + "@JCStressTest\n"
            + "@Outcome(id = \"1\", expect = Expect.ACCEPTABLE, desc = \"saw the write\")\n"
            + "@Outcome(id = \"0\", expect = Expect.FORBIDDEN, desc = \"race\")\n"
            + "@State\n"
            + "public class Racy {\n"
            + "    int x;\n"
            + "    @Actor public void writer() { x = 1; }\n"
            + "    @Actor public void reader(I_Result r) { r.r1 = x; }\n"
            + "}\n", StandardCharsets.UTF_8);
        CheckRequest jcReq = new CheckRequest();
        jcReq.root = jc.toString();
        jcReq.format = "human";
        CheckCommand.Outcome jcOut = JcstressCommand.run(jcReq);
        contains("plugins:jcstress-failure", jcOut.stdout, "J1201");
        deleteRecursively(jc);

        Path rw = Files.createTempDirectory("jcheck-rewrite-it-");
        Path rwSrc = rw.resolve("src/main/java/demo");
        Files.createDirectories(rwSrc);
        Files.writeString(rwSrc.resolve("Greeter.java"),
            "package demo;\n"
            + "interface Greeter {\n"
            + "    String greet(String n);\n"
            + "}\n", StandardCharsets.UTF_8);
        Files.writeString(rwSrc.resolve("GreeterImpl.java"),
            "package demo;\n"
            + "public final class GreeterImpl implements Greeter {\n"
            + "    public String greet(String n) {\n"
            + "        return \"hi \" + n;\n"
            + "    }\n"
            + "    static int twice(int x) {\n"
            + "        int y = x * 2;\n"
            + "        return y;\n"
            + "    }\n"
            + "}\n", StandardCharsets.UTF_8);
        CheckRequest rwReq = new CheckRequest();
        rwReq.root = rw.toString();
        // Disable the built-in pack so OpenRewrite is provably the thing that edits the file.
        Files.writeString(rw.resolve("jcheck.toml"), "[errorprone]\ncustom_pack = false\n",
            StandardCharsets.UTF_8);
        CheckCommand.Outcome rwOut = RewriteCommand.run(rwReq);
        String rewritten = Files.readString(rwSrc.resolve("GreeterImpl.java"), StandardCharsets.UTF_8);
        check("plugins:rewrite-override", rewritten.contains("@Override"), rewritten);
        check("plugins:rewrite-final-local", rewritten.contains("final int y"), rewritten);
        check("plugins:rewrite-code-override", rwOut.stdout.contains("J1501"), rwOut.stdout);
        check("plugins:rewrite-code-final", rwOut.stdout.contains("J1502"), rwOut.stdout);
        eq("plugins:rewrite-clean", 0, rwOut.exitCode);
        deleteRecursively(rw);

        junitHarnessIntegrationTest();
    }

    /**
     * Opt-in end-to-end check for {@code jcheck test --launcher}: a leaking test must fail through
     * the jcheck JUnit extension and surface as a J0901 diagnostic.
     */
    private static void junitHarnessIntegrationTest() throws Exception {
        MavenResolver resolver = MavenResolver.standard(false);
        List<Path> launcher = resolver.resolve(List.of(
            "org.junit.platform:junit-platform-console-standalone:1.10.2"));
        if (launcher.isEmpty()) {
            return;
        }
        Path tmp = Files.createTempDirectory("jcheck-junit-it-");
        Path testSrc = tmp.resolve("src/test/java/demo/SampleTest.java");
        Files.createDirectories(testSrc.getParent());
        Files.writeString(testSrc,
            "package demo;\n"
            + "import org.junit.jupiter.api.Test;\n"
            + "class SampleTest {\n"
            + "    @Test\n"
            + "    void leaksThread() {\n"
            + "        Thread t = new Thread(() -> {\n"
            + "            try { Thread.sleep(60_000); } catch (Exception ignored) { }\n"
            + "        }, \"jcheck-leak-thread\");\n"
            + "        t.setDaemon(true);\n"
            + "        t.start();\n"
            + "    }\n"
            + "}\n", StandardCharsets.UTF_8);
        Path classes = tmp.resolve("build/classes/java/test");
        Files.createDirectories(classes);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        boolean compiled = false;
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null,
                StandardCharsets.UTF_8)) {
            fm.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of(launcher.get(0)));
            compiled = compiler.getTask(null, fm, null, List.of("-d", classes.toString()), null,
                fm.getJavaFileObjects(testSrc)).call();
        }
        check("plugins:junit-test-compiled", compiled, "the sample test did not compile");
        Files.writeString(tmp.resolve("jcheck.toml"), "[tests]\ntimeout_seconds = 5\n",
            StandardCharsets.UTF_8);

        CheckCommand.Outcome out = TestMain.runTestCommand(tmp, "--launcher");
        check("plugins:junit-thread-leak", out.stdout.contains("thread leak detected"),
            "stdout=" + out.stdout + " stderr=" + out.stderr);
        check("plugins:junit-diagnostic", out.stdout.contains("J0901"), "stdout=" + out.stdout);
        deleteRecursively(tmp);
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
