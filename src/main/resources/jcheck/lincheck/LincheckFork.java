package jcheck.lincheck;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.kotlinx.lincheck.LinChecker;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions;
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions;

/**
 * Runs Lincheck over compiled test classes, one class per line of machine-readable output.
 *
 * <p>This source ships with jcheck and is compiled at run time against the resolved Lincheck jars,
 * so jcheck itself has no Lincheck dependency. Arguments: the iteration count, the mode
 * ({@code model-checking} or {@code stress}), the newest class-file major version Lincheck can
 * instrument, then the class roots to scan.
 *
 * <p>A class file newer than that limit is reported as {@code LINCHECK_UNSUPPORTED} rather than
 * being run: Lincheck's instrumentation silently does nothing when it cannot read the bytecode, and
 * a concurrency checker that reports a clean pass it did not verify is worse than no check at all.
 */
public final class LincheckFork {

    private LincheckFork() {
    }

    /** A test class candidate: its name and the major version of its class file. */
    private static final class Candidate {
        final String name;
        final int majorVersion;

        Candidate(String name, int majorVersion) {
            this.name = name;
            this.majorVersion = majorVersion;
        }
    }

    public static void main(String[] args) throws Exception {
        int iterations = Integer.parseInt(args[0]);
        String mode = args[1];
        int maxClassVersion = Integer.parseInt(args[2]);
        List<Path> roots = new ArrayList<>();
        for (int i = 3; i < args.length; i++) {
            roots.add(Path.of(args[i]));
        }

        List<Candidate> tests = discover(roots);
        int failures = 0;
        int unsupported = 0;
        for (Candidate candidate : tests) {
            if (candidate.majorVersion > maxClassVersion) {
                unsupported++;
                System.out.println("LINCHECK_UNSUPPORTED\t" + candidate.name + "\t"
                    + candidate.majorVersion);
                continue;
            }
            Class<?> clazz;
            try {
                clazz = Class.forName(candidate.name, false, LincheckFork.class.getClassLoader());
            } catch (Throwable t) {
                System.out.println("LINCHECK_SKIP\t" + candidate.name + "\t" + oneLine(t));
                continue;
            }
            if (!hasOperations(clazz)) {
                System.out.println("LINCHECK_SKIP\t" + candidate.name + "\tno @Operation methods");
                continue;
            }
            try {
                if ("stress".equals(mode)) {
                    LinChecker.check(clazz, new StressOptions().iterations(iterations));
                } else {
                    LinChecker.check(clazz, new ModelCheckingOptions().iterations(iterations));
                }
                System.out.println("LINCHECK_OK\t" + candidate.name);
            } catch (Throwable t) {
                failures++;
                System.out.println("LINCHECK_FAILED\t" + candidate.name + "\t" + summary(t));
            }
        }
        System.out.println("LINCHECK_DONE\t" + failures + "\t" + tests.size() + "\t" + unsupported);
    }

    /**
     * Finds Lincheck candidates cheaply: a class file whose constant pool mentions the
     * {@code @Operation} annotation. Only those classes are loaded, so scanning a large test tree
     * does not load every test class.
     */
    private static List<Candidate> discover(List<Path> roots) throws Exception {
        List<Candidate> candidates = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var stream = Files.walk(root)) {
                for (Path file : stream.filter(p -> p.toString().endsWith(".class")).toList()) {
                    String relative = root.relativize(file).toString();
                    if (relative.contains("$") || relative.endsWith("module-info.class")) {
                        continue;
                    }
                    byte[] bytes = Files.readAllBytes(file);
                    String text = new String(bytes, StandardCharsets.ISO_8859_1);
                    if (!text.contains("lincheck/annotations/Operation")) {
                        continue;
                    }
                    String name = relative.substring(0, relative.length() - ".class".length())
                        .replace(File.separatorChar, '.').replace('/', '.');
                    Candidate candidate = new Candidate(name, majorVersion(bytes));
                    boolean seen = false;
                    for (Candidate existing : candidates) {
                        if (existing.name.equals(name)) {
                            seen = true;
                        }
                    }
                    if (!seen) {
                        candidates.add(candidate);
                    }
                }
            }
        }
        return candidates;
    }

    /** The class-file major version: bytes 6-7, big endian; 0 when the header is unreadable. */
    private static int majorVersion(byte[] bytes) {
        if (bytes.length < 8) {
            return 0;
        }
        return ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff);
    }

    /** True when the class declares at least one {@code @Operation}, which is what Lincheck needs. */
    private static boolean hasOperations(Class<?> clazz) {
        if (clazz.isInterface() || java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())) {
            return false;
        }
        for (java.lang.reflect.Method method : clazz.getMethods()) {
            if (method.isAnnotationPresent(Operation.class)) {
                return true;
            }
        }
        for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Operation.class)) {
                return true;
            }
        }
        return false;
    }

    /** The headline of a Lincheck report, which is delimited by {@code = ... =} lines. */
    private static String summary(Throwable t) {
        String message = t.getMessage();
        String kind = t.getClass().getSimpleName();
        if (message != null) {
            for (String raw : message.split("\n")) {
                String line = raw.strip();
                if (line.length() > 2 && line.startsWith("=") && line.endsWith("=")) {
                    String headline = line.replace("=", "").strip();
                    if (!headline.isEmpty()) {
                        return headline + " (" + kind + ")";
                    }
                }
            }
        }
        return oneLine(t);
    }

    private static String oneLine(Throwable t) {
        String message = t.getMessage();
        if (message == null) {
            return t.getClass().getName();
        }
        int newline = message.indexOf('\n');
        String first = (newline < 0 ? message : message.substring(0, newline)).strip();
        return first.isEmpty() ? t.getClass().getName() : first;
    }
}
