package jcheck.selfreport;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An append-only JSONL file of jcheck's own troubles, so a later session can come back to the
 * checkout and fix them instead of rediscovering them.
 *
 * <p>Where it lives matters: a problem jcheck hits while checking someone else's project belongs
 * to jcheck, not to that project, so the default is the jcheck checkout itself
 * ({@code <checkout>/issues/self-report.jsonl}). An installed copy has no checkout, so it falls
 * back to the usual state directory. {@code --issue-log <path>} and {@code $JCHECK_ISSUE_LOG}
 * override both, and {@code off} disables the log entirely.
 *
 * <p>Writing never affects the run the user asked for: every failure here is swallowed. The log
 * is a courtesy to the next session, not part of the diagnostic contract.
 */
public final class IssueLog {

    /** System property naming the log file; tests set this so the real log stays untouched. */
    public static final String PROPERTY = "jcheck.issue.log";
    /** Environment variable naming the log file, when the property is absent. */
    public static final String ENV = "JCHECK_ISSUE_LOG";
    /** The file inside the log directory. */
    public static final String FILE_NAME = "self-report.jsonl";

    private static final String OFF = "off";
    private static final long MAX_BYTES = 1_000_000L;

    /** Set from the command line; wins over the property and the environment. */
    private static volatile String override;

    private IssueLog() {
    }

    /** Applies a {@code --issue-log} value for this process; empty or {@code off} disables the log. */
    public static void setOverride(String path) {
        if (path == null) {
            override = null;
            return;
        }
        String trimmed = path.trim();
        override = trimmed.isEmpty() ? OFF : trimmed;
    }

    /** The effective log file, or null when the log is disabled or cannot be written. */
    public static Path path() {
        String explicit = explicitSetting();
        if (explicit != null) {
            if (isOff(explicit)) {
                return null;
            }
            return usable(Path.of(explicit).toAbsolutePath().normalize());
        }
        Path checkout = checkoutLog();
        if (checkout != null && usable(checkout) != null) {
            return checkout;
        }
        Path state = stateLog();
        return state != null ? usable(state) : null;
    }

    /**
     * Appends one issue unless an entry with the same fingerprint is already present.
     *
     * @return true when a new line was written
     */
    public static synchronized boolean append(Issue issue) {
        if (issue == null) {
            return false;
        }
        Path file = path();
        if (file == null) {
            return false;
        }
        try {
            Files.createDirectories(file.getParent());
            if (hasId(file, issue.id())) {
                return false;
            }
            if (Files.isRegularFile(file) && Files.size(file) > MAX_BYTES) {
                compact(file, readAll(file));
            }
            Files.writeString(file, issue.toJsonLine() + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            return true;
        } catch (IOException | RuntimeException e) {
            // A logging failure must never turn into a failed check.
            return false;
        }
    }

    /** Every recorded issue, deduplicated by id and ordered bugs-first then newest-first. */
    public static List<Issue> read() {
        Path file = path();
        if (file == null) {
            return List.of();
        }
        return readAll(file);
    }

    /** Raw JSONL lines, for {@code jcheck issues --json}. */
    public static List<String> readLines() {
        Path file = path();
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : content(file).split("\n")) {
            if (!line.isBlank()) {
                out.add(line);
            }
        }
        return out;
    }

    /** Deletes the log file; the directory stays so the next run can recreate the file. */
    public static synchronized boolean clear() {
        Path file = path();
        if (file == null) {
            return false;
        }
        try {
            boolean existed = Files.deleteIfExists(file);
            Files.deleteIfExists(file.resolveSibling(FILE_NAME + ".tmp"));
            return existed;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** The path to quote in a message, or null when the log is disabled. */
    public static String locationForMessage() {
        Path p = path();
        return p == null ? null : p.toString();
    }

    // ---- rendering ---------------------------------------------------------

    /** A markdown page a human or a future agent can read without parsing JSONL. */
    public static String renderMarkdown(List<Issue> issues) {
        StringBuilder out = new StringBuilder();
        out.append("# jcheck self-report\n\n");
        if (issues.isEmpty()) {
            out.append("No self-reported issues. jcheck has not caught itself misbehaving, or the "
                + "log was cleared after a fix.\n");
            return out.toString();
        }
        out.append(issues.size()).append(issues.size() == 1 ? " open issue" : " open issues")
           .append(". One entry per distinct problem; clear the log after fixing one so a recurrence "
               + "is visible again.\n");
        int n = 0;
        for (Issue i : issues) {
            n++;
            out.append("\n## ").append(n).append(". ").append(oneLine(i.summary())).append('\n');
            out.append("- kind: `").append(i.kind().wire()).append("` (")
               .append(i.kind().severity()).append(")\n");
            out.append("- id: `").append(i.id()).append("`\n");
            if (!i.time().isEmpty()) {
                out.append("- first seen: ").append(i.time()).append('\n');
            }
            StringBuilder where = new StringBuilder();
            if (!i.command().isEmpty()) {
                where.append('`').append(i.command()).append('`');
            }
            if (!i.root().isEmpty()) {
                if (where.length() > 0) {
                    where.append(" in ");
                }
                where.append('`').append(i.root()).append('`');
            }
            if (where.length() > 0) {
                out.append("- where: ").append(where).append('\n');
            }
            String runtime = runtimeLine(i);
            if (!runtime.isEmpty()) {
                out.append("- runtime: ").append(runtime).append('\n');
            }
            if (!i.detail().isEmpty()) {
                out.append("- detail:\n");
                for (String line : i.detail().split("\n", -1)) {
                    out.append("  ").append(line).append('\n');
                }
            }
            if (!i.hint().isEmpty()) {
                out.append("- hint: ").append(oneLine(i.hint())).append('\n');
            }
            if (!i.stack().isEmpty()) {
                out.append("\n<details><summary>stack trace</summary>\n\n```\n")
                   .append(i.stack().stripTrailing()).append("\n```\n\n</details>\n");
            }
        }
        return out.toString();
    }

    private static String runtimeLine(Issue i) {
        List<String> parts = new ArrayList<>();
        if (!i.version().isEmpty()) {
            parts.add("jcheck " + i.version());
        }
        if (!i.java().isEmpty()) {
            parts.add("JDK " + i.java());
        }
        if (!i.os().isEmpty()) {
            parts.add(i.os());
        }
        return String.join(", ", parts);
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    // ---- resolution --------------------------------------------------------

    private static String explicitSetting() {
        if (override != null && !override.isEmpty()) {
            return override;
        }
        String property = System.getProperty(PROPERTY);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        String env = System.getenv(ENV);
        return env == null || env.isBlank() ? null : env.trim();
    }

    private static boolean isOff(String value) {
        return value.isEmpty() || OFF.equalsIgnoreCase(value) || "0".equals(value)
            || "false".equalsIgnoreCase(value);
    }

    /**
     * {@code <directory containing build.sh and src/main/java/jcheck>/issues/self-report.jsonl}.
     * This is what makes a checkout used as a dogfooding tool report into itself.
     */
    private static Path checkoutLog() {
        try {
            if (IssueLog.class.getProtectionDomain() == null
                || IssueLog.class.getProtectionDomain().getCodeSource() == null) {
                return null;
            }
            java.net.URL url = IssueLog.class.getProtectionDomain().getCodeSource().getLocation();
            Path location = Path.of(url.toURI()).toAbsolutePath();
            Path dir = Files.isDirectory(location) ? location : location.getParent();
            for (int depth = 0; depth < 5 && dir != null; depth++, dir = dir.getParent()) {
                if (Files.isRegularFile(dir.resolve("build.sh"))
                    && Files.isDirectory(dir.resolve("src/main/java/jcheck"))) {
                    return dir.resolve("issues").resolve(FILE_NAME);
                }
            }
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
        return null;
    }

    private static Path stateLog() {
        String xdg = System.getenv("XDG_STATE_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg).resolve("jcheck").resolve(FILE_NAME);
        }
        String home = System.getProperty("user.home", "");
        if (home.isBlank()) {
            return null;
        }
        return Path.of(home, ".local", "state", "jcheck", FILE_NAME);
    }

    /** Returns the path when its directory can be created and written, else null. */
    private static Path usable(Path file) {
        try {
            Path parent = file.getParent();
            if (parent == null) {
                return null;
            }
            Files.createDirectories(parent);
            return Files.isWritable(parent) ? file : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ---- file IO -----------------------------------------------------------

    /** Cheap duplicate check: the writer always emits {@code "id":"<hex>"} with no spaces. */
    private static boolean hasId(Path file, String id) {
        if (id == null || !Files.isRegularFile(file)) {
            return false;
        }
        String needle = "\"id\":\"" + id + "\"";
        for (String line : content(file).split("\n")) {
            if (line.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static List<Issue> readAll(Path file) {        List<Issue> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (String line : content(file).split("\n")) {
            Issue issue = Issue.fromJson(line);
            if (issue != null && seen.add(issue.id())) {
                out.add(issue);
            }
        }
        out.sort(Issue::compare);
        return out;
    }

    /** Reads bytes as UTF-8 with replacement, so one bad byte cannot hide the whole log. */
    private static String content(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** Rewrites the file with one line per id; bounded so a long-lived checkout cannot grow forever. */
    private static void compact(Path file, List<Issue> unique) {
        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
        StringBuilder sb = new StringBuilder();
        for (Issue i : unique) {
            sb.append(i.toJsonLine()).append('\n');
        }
        try {
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
