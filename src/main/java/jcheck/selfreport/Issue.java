package jcheck.selfreport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import jcheck.util.Json;
import jcheck.util.JsonWriter;

/**
 * One durable entry in jcheck's own issue log.
 *
 * <p>The log is a backlog, not telemetry: an entry is written once per distinct problem (the
 * {@link #id} is a fingerprint of kind, summary and detail), and the entry stays until it is
 * fixed and the log is cleared. The {@code hint} is part of the record on purpose — when a future
 * session reads the log, the entry itself says what kind of fix it wants.
 */
public record Issue(
    String id,
    String time,
    IssueKind kind,
    String summary,
    String detail,
    String hint,
    String command,
    String root,
    String version,
    String java,
    String os,
    String stack) {

    /** A display ordering: bugs first, then newest. */
    public static int compare(Issue a, Issue b) {
        int byKind = Integer.compare(a.kind.rank(), b.kind.rank());
        if (byKind != 0) {
            return byKind;
        }
        String at = a.time == null ? "" : a.time;
        String bt = b.time == null ? "" : b.time;
        return bt.compareTo(at);
    }

    /**
     * Stable identity for "the same problem". Paths that vary per run (scratch directories,
     * object identity hashes) are normalised away so a recurring bug does not become a new entry
     * on every run; the root project is deliberately not part of the identity either, because one
     * fix addresses every project that hits it.
     */
    public static String fingerprint(IssueKind kind, String summary, String detail) {
        String material = kind.wire() + "\n" + normalize(summary) + "\n" + normalize(detail);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6 && i < hash.length; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("@[0-9a-fA-F]{5,}", "@<hash>");
        t = t.replaceAll("jcheck-[a-z]*-?scratch-[A-Za-z0-9._-]+", "jcheck-scratch-<tmp>");
        t = t.replaceAll("/var/folders/[^\\s\"']+", "<tmp>");
        t = t.replaceAll("/tmp/[^\\s\"']+", "<tmp>");
        return t.replaceAll("\\s+", " ").trim();
    }

    public String toJsonLine() {
        JsonWriter w = new JsonWriter();
        w.beginObject();
        w.field("id", id);
        w.field("time", time);
        w.field("kind", kind.wire());
        w.field("severity", kind.severity());
        w.field("summary", summary);
        w.optionalField("detail", detail);
        w.optionalField("hint", hint);
        w.optionalField("command", command);
        w.optionalField("root", root);
        w.optionalField("version", version);
        w.optionalField("java", java);
        w.optionalField("os", os);
        w.optionalField("stack", stack);
        w.endObject();
        return w.toString();
    }

    /** Parses one JSONL line; returns null for a blank or malformed line. */
    public static Issue fromJson(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        Map<String, Object> m;
        try {
            m = Json.parseObject(line);
        } catch (RuntimeException e) {
            return null;
        }
        String summary = Json.optString(m, "summary");
        if (summary == null || summary.isBlank()) {
            return null;
        }
        IssueKind kind = IssueKind.fromWire(Json.optString(m, "kind"));
        String detail = Json.optString(m, "detail");
        String id = Json.optString(m, "id");
        return new Issue(
            id == null || id.isBlank() ? fingerprint(kind, summary, detail) : id,
            orEmpty(Json.optString(m, "time")),
            kind,
            summary,
            orEmpty(detail),
            Json.optString(m, "hint") == null ? kind.hint() : Json.optString(m, "hint"),
            orEmpty(Json.optString(m, "command")),
            orEmpty(Json.optString(m, "root")),
            orEmpty(Json.optString(m, "version")),
            orEmpty(Json.optString(m, "java")),
            orEmpty(Json.optString(m, "os")),
            orEmpty(Json.optString(m, "stack")));
    }

    private static String orEmpty(String v) {
        return v == null ? "" : v;
    }

    /** {@code 2026-10-22T14:30:00Z}, the only timestamp format the log writes. */
    public static String now() {
        return Instant.now().toString();
    }
}
