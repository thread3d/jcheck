package jcheck.engine;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.List;
import jcheck.catalog.Catalog;
import jcheck.model.Diagnostic;

/**
 * Attaches jcheck's own AST checks to the compilation javac is already running.
 *
 * <p>These run at {@code ANALYZE} time, which is exactly the phase-gating guarantee we want:
 * a unit that fails to attribute never reaches this listener, so no T3 finding can be reported
 * for code whose T1/T2 state is broken. One {@link AstContext} per unit is shared by every check
 * in the pack, and each check keeps its own bookkeeping.
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
    public static final List<String> IMPLEMENTED_CHECK_CODES = List.of(
        "J0003", "J0301", "J0302", "J0601", "J0602", "J0603", "J0701", "J0705", "J0706",
        "J0801", "J0902", "J1001", "J1301");

    /** jcheck codes emitted outside the compile path, by the test harness. */
    public static final List<String> IMPLEMENTED_HARNESS_CODES = List.of("J0901", "J1101", "J1102");

    /** Every jcheck code this build can actually emit, from either path. */
    public static final List<String> IMPLEMENTED_CODES = combined();

    private NativeChecks() {
    }

    private static List<String> combined() {
        List<String> all = new ArrayList<>(IMPLEMENTED_CHECK_CODES);
        all.addAll(IMPLEMENTED_HARNESS_CODES);
        return List.copyOf(all);
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
                AstContext ctx = new AstContext(task, trees, cu, spans, catalog, sink);
                for (AstCheck check : checks(ctx)) {
                    check.run();
                }
            }
        });
    }

    /** The check pack, one instance per compilation unit. */
    static List<AstCheck> checks(AstContext ctx) {
        return List.of(
            new SuppressionNeedsReasonCheck(ctx),
            new UncheckedArithmeticCheck(ctx),
            new LossyCastCheck(ctx),
            new SharedMutableWithoutGuardCheck(ctx),
            new MutationDuringIterationCheck(ctx),
            new EscapingMutableInternalCheck(ctx),
            new UnsafeOutsideUnsafeCheck(ctx),
            new MutableStaticStateCheck(ctx),
            new LocalCanBeFinalCheck(ctx),
            new UnclosedResourceCheck(ctx),
            new TodoMarkerCheck(ctx),
            new UnclosedExecutorOrThreadCheck(ctx),
            new AddOverrideCheck(ctx));
    }
}
