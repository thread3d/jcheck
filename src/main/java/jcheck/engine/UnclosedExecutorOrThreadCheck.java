package jcheck.engine;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;
import jcheck.model.Level;
import jcheck.model.Span;

/**
 * J1001 UnclosedExecutorOrThread: an executor or raw thread created in a method with no visible
 * shutdown path.
 *
 * <p>An {@code ExecutorService} carries no type-level close obligation, so a forgotten
 * {@code shutdown()} keeps non-daemon threads alive: a hanging JVM in production and a leaked
 * thread in tests. A raw {@code new Thread(...)} that is started is reported unless the method also
 * joins or interrupts it. Returning the executor, storing it, or passing it on is a hand-off and is
 * not reported; try-with-resources covers the {@code AutoCloseable} case, which is J0801's job.
 */
final class UnclosedExecutorOrThreadCheck extends AstCheck {

    private static final String NAME = "UnclosedExecutorOrThread";
    private static final List<String> SHUTDOWN = List.of("shutdown", "shutdownNow");
    private static final List<String> START = List.of("start");
    private static final List<String> JOIN = List.of("join", "interrupt");

    UnclosedExecutorOrThreadCheck(AstContext ctx) {
        super(ctx, "J1001", NAME, Level.ERROR);
    }

    @Override
    public Void visitMethod(MethodTree node, Void p) {
        MethodFacts facts = MethodFacts.collect(this, getCurrentPath());
        for (MethodFacts.Local local : facts.locals) {
            analyze(facts, local);
        }
        return super.visitMethod(node, p);
    }

    private void analyze(MethodFacts facts, MethodFacts.Local local) {
        Element v = local.element;
        if (v == null || suppressed(local.path, code) || suppressedElement(v)) {
            return;
        }
        TypeMirror type = v.asType();
        Span span = spanOf(local.tree);
        if (isExecutorService(type)) {
            if (facts.calledOn(SHUTDOWN, v) || facts.handedOff(v)) {
                return;
            }
            emit(diag("executor `" + v.getSimpleName() + "` has no visible shutdown", span)
                .label(span, "never shut down"));
        } else if (isThread(type)) {
            if (!facts.calledOn(START, v) || facts.calledOn(JOIN, v) || facts.handedOff(v)) {
                return;
            }
            emit(diag("thread `" + v.getSimpleName() + "` is never joined or interrupted", span)
                .label(span, "started here, no shutdown path"));
        }
    }

    /** {@code new Thread(...).start()} with no variable to hand off has no shutdown either. */
    @Override
    public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
        if ("start".equals(methodSimpleName(node)) && !suppressed()) {
            ExpressionTree receiver = methodReceiver(node);
            if (receiver instanceof NewClassTree && isThread(receiverType(node))
                && !isVariableInitializer(node)) {
                Span span = spanOf(node);
                emit(diag("thread started with no join or interrupt", span)
                    .label(span, "no shutdown path"));
            }
        }
        return super.visitMethodInvocation(node, p);
    }

    private boolean isVariableInitializer(MethodInvocationTree node) {
        TreePath parent = getCurrentPath().getParentPath();
        return parent != null && parent.getLeaf() instanceof VariableTree
            && ((VariableTree) parent.getLeaf()).getInitializer() == node;
    }
}
