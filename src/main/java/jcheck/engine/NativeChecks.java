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
