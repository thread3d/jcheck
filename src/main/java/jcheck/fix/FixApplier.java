package jcheck.fix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jcheck.model.Applicability;
import jcheck.model.Diagnostic;
import jcheck.model.Edit;
import jcheck.model.Span;
import jcheck.model.Suggestion;

/**
 * Applies machine-applicable suggestions to source files.
 *
 * <p>Edits are applied back-to-front so earlier offsets stay valid, and every touched file is
 * backed up in memory so the caller can revert when a re-check gets worse.
 */
public final class FixApplier {

    public static final class Applied {
        public final String file;
        public final int editCount;
        public final String code;

        public Applied(String file, int editCount, String code) {
            this.file = file;
            this.editCount = editCount;
            this.code = code;
        }
    }

    public static final class Result {
        public final List<Applied> applied;
        public final List<String> skipped;
        /** Original file contents, keyed by the path as it appeared in the diagnostic. */
        public final Map<String, String> backups;
        public final int editCount;

        Result(List<Applied> applied, List<String> skipped, Map<String, String> backups, int editCount) {
            this.applied = List.copyOf(applied);
            this.skipped = List.copyOf(skipped);
            this.backups = Map.copyOf(backups);
            this.editCount = editCount;
        }
    }

    private FixApplier() {
    }

    public static Result apply(Path root, List<Diagnostic> diagnostics, boolean includeMaybeIncorrect) {
        Map<String, List<Edit>> byFile = new LinkedHashMap<>();
        Map<String, String> codeByFile = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();

        for (Diagnostic d : diagnostics) {
            for (Suggestion s : d.suggestions) {
                boolean acceptable = s.applicability() == Applicability.MACHINE_APPLICABLE
                    || (includeMaybeIncorrect && s.applicability() == Applicability.MAYBE_INCORRECT);
                if (!acceptable) {
                    skipped.add(d.code + ": " + s.message() + " [" + s.applicability().wire() + "]");
                    continue;
                }
                for (Edit e : s.edits()) {
                    if (e.span() == null || e.span().file() == null) {
                        skipped.add(d.code + ": suggestion edit has no location");
                        continue;
                    }
                    byFile.computeIfAbsent(e.span().file(), k -> new ArrayList<>()).add(e);
                    codeByFile.putIfAbsent(e.span().file(), d.code);
                }
            }
        }

        Map<String, String> backups = new LinkedHashMap<>();
        List<Applied> applied = new ArrayList<>();
        int total = 0;

        for (Map.Entry<String, List<Edit>> entry : byFile.entrySet()) {
            Path file = resolve(root, entry.getKey());
            if (!Files.isRegularFile(file)) {
                skipped.add(entry.getKey() + ": file not found");
                continue;
            }
            String content;
            try {
                content = Files.readString(file);
            } catch (IOException e) {
                skipped.add(entry.getKey() + ": " + e.getMessage());
                continue;
            }
            List<Edit> edits = new ArrayList<>(entry.getValue());
            edits.sort(Comparator
                .comparingInt((Edit e) -> offsetOf(content, e.span().lineStart(), e.span().colStart()))
                .thenComparingInt((Edit e) -> offsetOf(content, e.span().lineEnd(), e.span().colEnd()))
                .reversed());

            String updated = content;
            int count = 0;
            for (Edit e : edits) {
                int start = offsetOf(content, e.span().lineStart(), e.span().colStart());
                int end = offsetOf(content, e.span().lineEnd(), e.span().colEnd());
                if (start < 0 || end < start || end > updated.length() || start > updated.length()) {
                    skipped.add(entry.getKey() + ": edit out of range at " + e.span().display());
                    continue;
                }
                updated = updated.substring(0, start) + e.replacement() + updated.substring(end);
                count++;
            }
            if (count == 0) {
                continue;
            }
            backups.put(entry.getKey(), content);
            try {
                Files.writeString(file, updated);
                applied.add(new Applied(entry.getKey(), count, codeByFile.get(entry.getKey())));
                total += count;
            } catch (IOException e) {
                backups.remove(entry.getKey());
                skipped.add(entry.getKey() + ": " + e.getMessage());
            }
        }
        return new Result(applied, skipped, backups, total);
    }

    /** Restores every backed-up file to its original contents. */
    public static void revert(Path root, Map<String, String> backups) {
        for (Map.Entry<String, String> e : backups.entrySet()) {
            try {
                Files.writeString(resolve(root, e.getKey()), e.getValue());
            } catch (IOException ignored) {
                // nothing better to do than leave the edit in place
            }
        }
    }

    static Path resolve(Path root, String file) {
        Path p = Path.of(file);
        return p.isAbsolute() ? p : root.resolve(p);
    }

    /** 1-based line/column to a 0-based character offset. */
    public static int offsetOf(String content, int line, int col) {
        int offset = 0;
        int currentLine = 1;
        while (currentLine < line) {
            int nl = content.indexOf('\n', offset);
            if (nl < 0) {
                return content.length();
            }
            offset = nl + 1;
            currentLine++;
        }
        long result = (long) offset + (col - 1L);
        if (result < 0) {
            return 0;
        }
        return (int) Math.min(result, content.length());
    }

    static int offsetOf(String content, Span s) {
        return offsetOf(content, s.lineStart(), s.colStart());
    }
}
