package jcheck.engine;

import com.sun.source.util.JavacTask;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import jcheck.catalog.Catalog;
import jcheck.model.Diagnostic;
import jcheck.model.Level;

/**
 * Drives javac in-process through {@link javax.tools.JavaCompiler} with a diagnostic listener and
 * jcheck's own AST checks attached to the same task.
 *
 * <p>Compilation happens once. Output goes to a scratch directory and is never installed, so a
 * check never produces artifacts.
 */
public final class JavacRunner {

    /** Outcome of one check invocation. */
    public static final class Result {
        public final List<Diagnostic> diagnostics;
        public final List<String> notes;
        public final boolean compiledCleanly;
        public final boolean usedHiddenOptions;

        Result(List<Diagnostic> diagnostics, List<String> notes, boolean compiledCleanly,
               boolean usedHiddenOptions) {
            this.diagnostics = diagnostics;
            this.notes = notes;
            this.compiledCleanly = compiledCleanly;
            this.usedHiddenOptions = usedHiddenOptions;
        }
    }

    private static final class Attempt {
        boolean ok;
        String setupError;
        List<javax.tools.Diagnostic<? extends JavaFileObject>> raw = List.of();
        final List<Diagnostic> natives = new ArrayList<>();
    }

    private JavacRunner() {
    }

    public static Result run(CheckOptions opts, Catalog catalog) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                "no system Java compiler available; jcheck must run on a JDK, not a JRE");
        }
        List<String> notes = new ArrayList<>();
        SpanResolver spans = new SpanResolver();

        if (opts.scratchDir != null) {
            try {
                Files.createDirectories(opts.scratchDir);
            } catch (IOException e) {
                throw new IllegalStateException("cannot create scratch dir " + opts.scratchDir, e);
            }
        }

        boolean usedHidden = true;
        Attempt a = attempt(compiler, opts, catalog, buildOptions(opts, notes, true), spans);
        if (!a.ok && containsInvalidFlag(a.raw)) {
            notes.add("this JDK rejected jcheck's hidden javac options; retried without phase gating");
            a = attempt(compiler, opts, catalog, buildOptions(opts, notes, false), spans);
            usedHidden = false;
        }
        if (a.setupError != null) {
            notes.add("javac rejected the option set: " + a.setupError);
        }

        DiagnosticNormalizer normalizer = new DiagnosticNormalizer(catalog, spans);
        List<Diagnostic> all = new ArrayList<>();
        int seq = 0;
        for (javax.tools.Diagnostic<? extends JavaFileObject> d : a.raw) {
            all.add(normalizer.normalize(d, seq++));
        }
        all.addAll(a.natives);

        boolean clean = true;
        for (Diagnostic d : all) {
            if (d.level == Level.ERROR && "javac".equals(d.source)) {
                clean = false;
                break;
            }
        }
        return new Result(all, notes, clean, usedHidden);
    }

    private static Attempt attempt(JavaCompiler compiler, CheckOptions opts, Catalog catalog,
                                   List<String> options, SpanResolver spans) {
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        Attempt a = new Attempt();
        StandardJavaFileManager fm = compiler.getStandardFileManager(collector, null, StandardCharsets.UTF_8);
        try {
            if (!opts.classpath.isEmpty()) {
                fm.setLocationFromPaths(StandardLocation.CLASS_PATH, opts.classpath);
            }
            if (!opts.processorPath.isEmpty()) {
                fm.setLocationFromPaths(StandardLocation.ANNOTATION_PROCESSOR_PATH, opts.processorPath);
            }
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(opts.sources);
            JavaCompiler.CompilationTask task;
            try {
                task = compiler.getTask(null, fm, collector, options, null, units);
            } catch (IllegalArgumentException iae) {
                a.ok = false;
                a.setupError = iae.getMessage();
                return a;
            }
            if (opts.nativeChecks && task instanceof JavacTask) {
                NativeChecks.attach((JavacTask) task, catalog, a.natives, spans);
            }
            a.ok = task.call();
        } catch (IOException e) {
            throw new IllegalStateException("failed to configure compilation", e);
        } finally {
            try {
                fm.close();
            } catch (IOException ignored) {
                // closing the file manager is best-effort
            }
        }
        a.raw = collector.getDiagnostics();
        return a;
    }

    private static List<String> buildOptions(CheckOptions opts, List<String> notes, boolean hidden) {
        List<String> o = new ArrayList<>();
        int feature = Runtime.version().feature();
        o.add("-encoding");
        o.add("UTF-8");
        if (opts.scratchDir != null) {
            o.add("-d");
            o.add(opts.scratchDir.toString());
        }
        if (!opts.classpath.isEmpty()) {
            o.add("-classpath");
            o.add(joinPaths(opts.classpath));
        }
        if (opts.release != null) {
            if (opts.release > feature) {
                notes.add("--release " + opts.release + " exceeds this JDK (" + feature + "); ignoring");
            } else {
                o.add("--release");
                o.add(String.valueOf(opts.release));
            }
        }
        if (!opts.lints.isEmpty()) {
            o.add("-Xlint:" + String.join(",", opts.lints));
        }
        o.add("-Xdiags:verbose");
        o.add("-parameters");
        o.add("-Xmaxerrs");
        o.add(String.valueOf(opts.maxErrors));
        o.add("-Xmaxwarns");
        o.add(String.valueOf(opts.maxErrors));
        if (!opts.processAnnotations) {
            o.add("-proc:none");
        } else if (feature >= 21) {
            // JDK 23+ no longer runs processors implicitly; -proc:full only exists from 21.
            o.add("-proc:full");
        }
        if (hidden) {
            if (opts.shouldStopAtFlow) {
                o.add("-XDshould-stop.ifError=FLOW");
            }
            o.add("-XDcompilePolicy=simple");
        }
        o.addAll(opts.javacExtra);
        return o;
    }

    private static String joinPaths(List<Path> paths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) {
                sb.append(File.pathSeparatorChar);
            }
            sb.append(paths.get(i).toString());
        }
        return sb.toString();
    }

    private static boolean containsInvalidFlag(List<javax.tools.Diagnostic<? extends JavaFileObject>> diags) {
        for (javax.tools.Diagnostic<? extends JavaFileObject> d : diags) {
            String m = d.getMessage(null);
            if (m != null && m.toLowerCase().contains("invalid flag")) {
                return true;
            }
        }
        return false;
    }
}
