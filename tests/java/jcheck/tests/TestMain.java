package jcheck.tests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import jcheck.cli.Args;
import jcheck.cli.CheckCommand;
import jcheck.cli.CheckRequest;
import jcheck.cli.Main;
import jcheck.cli.TestCommand;
import jcheck.selfreport.IssueLog;

/**
 * jcheck's own test runner. No JUnit, no network: it compiles with the same JDK the tool needs
 * and runs both unit tests and rustc-style UI golden tests.
 *
 * <pre>
 *   java -cp out/classes:out/test-classes jcheck.tests.TestMain [repoRoot] [--bless]
 * </pre>
 *
 * <p>{@code --bless} regenerates the golden files. Blessing is a deliberate act: it rewrites the
 * expectation to match reality, so it must only be used after reading the diff.
 */
public final class TestMain {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<>();

    private TestMain() {
    }

    public static void main(String[] argv) throws Exception {
        Path repoRoot = Path.of(".").toAbsolutePath().normalize();
        boolean bless = false;
        for (String a : argv) {
            if ("--bless".equals(a)) {
                bless = true;
            } else {
                repoRoot = Path.of(a).toAbsolutePath().normalize();
            }
        }

        /*
         * jcheck records its own issues in a durable log. The suite must never write into the
         * checkout's real backlog, so it points the log at build output unless the caller chose a
         * path (the self-report tests do, and restore it afterwards).
         */
        if (System.getProperty(IssueLog.PROPERTY) == null) {
            Path testLog = repoRoot.resolve("out/test-issues/self-report.jsonl");
            Files.createDirectories(testLog.getParent());
            System.setProperty(IssueLog.PROPERTY, testLog.toString());
        }

        UnitTests.run(repoRoot);
        McpTests.run(repoRoot);
        uiGoldenTests(repoRoot, bless);

        System.out.println();
        System.out.println("passed: " + passed + ", failed: " + failed);
        if (failed > 0) {
            System.out.println();
            for (String f : failures) {
                System.out.println("FAIL " + f);
            }
            System.exit(1);
        }
    }

    // ---- assertions --------------------------------------------------------

    public static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
        } else {
            failed++;
            failures.add(name + (detail == null || detail.isEmpty() ? "" : " -- " + detail));
        }
    }

    /**
     * Equality assertion.
     *
     * <p>Numbers are compared by value, not by box type: {@code Long(1).equals(Integer(1))} is
     * false, and a test that fails with "expected &lt;1&gt; but was &lt;1&gt;" is worse than
     * useless because it points at the wrong thing.
     */
    public static void eq(String name, Object expected, Object actual) {
        boolean ok;
        if (expected instanceof Number && actual instanceof Number) {
            Number e = (Number) expected;
            Number a = (Number) actual;
            ok = e.longValue() == a.longValue()
                || Double.compare(e.doubleValue(), a.doubleValue()) == 0;
        } else {
            ok = expected == null ? actual == null : expected.equals(actual);
        }
        check(name, ok, "expected <" + expected + "> but was <" + actual + ">");
    }

    public static void contains(String name, String haystack, String needle) {
        boolean ok = haystack != null && haystack.contains(needle);
        check(name, ok, "expected to contain <" + needle + ">");
    }

    public static void notContains(String name, String haystack, String needle) {
        boolean ok = haystack == null || !haystack.contains(needle);
        check(name, ok, "expected NOT to contain <" + needle + ">");
    }

    // ---- UI golden tests ---------------------------------------------------

    private static void uiGoldenTests(Path repoRoot, boolean bless) throws IOException {
        Path casesDir = repoRoot.resolve("tests/ui/cases");
        if (!Files.isDirectory(casesDir)) {
            check("ui:cases-dir-exists", false, "missing " + casesDir);
            return;
        }
        List<Path> cases = new ArrayList<>();
        try (Stream<Path> s = Files.list(casesDir)) {
            s.filter(Files::isDirectory).sorted().forEach(cases::add);
        }
        check("ui:cases-found", !cases.isEmpty(), "no cases under " + casesDir);

        for (Path c : cases) {
            String name = c.getFileName().toString();
            Path input = c.resolve("input");
            Path cmdFile = c.resolve("cmd");
            Path expectedFile = c.resolve("expected.txt");
            if (!Files.isRegularFile(cmdFile)) {
                check("ui:" + name, false, "missing cmd file");
                continue;
            }
            String cmdLine = Files.readString(cmdFile, StandardCharsets.UTF_8).trim();
            CheckCommand.Outcome outcome = runCase(input, cmdLine);

            if (bless) {
                Files.writeString(expectedFile, outcome.stdout, StandardCharsets.UTF_8);
                System.out.println("blessed tests/ui/cases/" + name + "/expected.txt");
                passed++;
                continue;
            }
            if (!Files.isRegularFile(expectedFile)) {
                check("ui:" + name, false, "missing expected.txt (run with --bless after reviewing)");
                continue;
            }
            String expected = Files.readString(expectedFile, StandardCharsets.UTF_8);
            if (expected.equals(outcome.stdout)) {
                passed++;
            } else {
                failed++;
                failures.add("ui:" + name + " -- output differs\n"
                    + indent(diff(expected, outcome.stdout)));
            }

            // The exit code must agree with the count line, so a golden can never claim clean
            // while the process would have exited non-zero.
            int expectedExit = expected.contains("jcheck: 0 errors") ? 0 : 1;
            check("ui:" + name + ":exit", outcome.exitCode == expectedExit,
                "expected exit " + expectedExit + " but was " + outcome.exitCode);
        }
    }

    static CheckCommand.Outcome runCase(Path inputDir, String cmdLine) {
        String[] args = cmdLine.isEmpty() ? new String[0] : cmdLine.split("\\s+");
        Args a = Args.parse(args, Main.VALUE_FLAGS);
        CheckRequest req = Main.buildRequest(a);
        req.root = inputDir.toString();
        return CheckCommand.run(req);
    }

    /** Like {@link #runCase} but drives {@code jcheck test} instead of the static check pipeline. */
    static CheckCommand.Outcome runTestCommand(Path inputDir, String cmdLine) {
        String[] args = cmdLine.isEmpty() ? new String[0] : cmdLine.split("\\s+");
        Args a = Args.parse(args, Main.VALUE_FLAGS);
        CheckRequest req = Main.buildRequest(a);
        req.root = inputDir.toString();
        return TestCommand.run(req);
    }

    private static String diff(String expected, String actual) {
        String[] e = expected.split("\n", -1);
        String[] g = actual.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        sb.append("--- expected\n+++ actual\n");
        int max = Math.max(e.length, g.length);
        for (int i = 0; i < max; i++) {
            String el = i < e.length ? e[i] : "<missing>";
            String gl = i < g.length ? g[i] : "<missing>";
            if (!el.equals(gl)) {
                sb.append(String.format("line %d%n- %s%n+ %s%n", i + 1, el, gl));
            }
        }
        return sb.toString();
    }

    private static String indent(String s) {
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            sb.append("    ").append(line).append('\n');
        }
        return sb.toString();
    }
}
