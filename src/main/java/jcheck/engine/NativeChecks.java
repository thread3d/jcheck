package jcheck.engine;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;
import java.util.List;
import jcheck.catalog.Catalog;
import jcheck.model.Diagnostic;

/**
 * Attaches jcheck's own AST checks to the compilation javac is already running.
 *
 * <p>These run at {@code ANALYZE} time, which is exactly the phase-gating guarantee we want:
 * a unit that fails to attribute never reaches this listener, so no T3 finding can be reported
 * for code whose T1/T2 state is broken.
 */
public final class NativeChecks {

    /**
     * jcheck-pack codes emitted by the T3 AST check pack.
     *
     * <p>Everything else in the catalog under {@code source=jcheck} is a documented intention, not
     * a check. The MCP capabilities resource derives the gap from these lists rather than
     * restating it by hand, so "catalogued but not implemented" cannot drift out of date
     * silently.
     */
    public static final List<String> IMPLEMENTED_CHECK_CODES = List.of("J0706");

    /** jcheck codes emitted outside the compile path, by the test harness. */
    public static final List<String> IMPLEMENTED_HARNESS_CODES = List.of("J0901");

    /** Every jcheck code this build can actually emit, from either path. */
    public static final List<String> IMPLEMENTED_CODES =
        List.of("J0706", "J0901");

    private NativeChecks() {
    }

    public static void attach(JavacTask task, Catalog catalog, List<Diagnostic> sink, SpanResolver spans) {
        Trees trees = Trees.instance(task);
        task.addTaskListener(new TaskListener() {
            @Override
            public void finished(TaskEvent e) {
                if (e.getKind() != TaskEvent.Kind.ANALYZE) {
                    return;
                }
                CompilationUnitTree cu = e.getCompilationUnit();
                if (cu == null) {
                    return;
                }
                new LocalCanBeFinalCheck(trees, trees.getSourcePositions(), cu, spans, catalog, sink)
                    .run();
            }
        });
    }
}
