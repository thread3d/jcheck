package jcheck.engine;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.util.List;
import javax.lang.model.element.Element;
import jcheck.model.Level;
import jcheck.model.Span;

/**
 * J0801 UnclosedResource: an {@code AutoCloseable} local that is never closed.
 *
 * <p>Covers anything whose type implements {@code AutoCloseable} — readers, streams, JDBC objects,
 * and the {@code Stream} returned by {@code Files.lines}/{@code walk}/{@code list}, which looks
 * like a collection operation and is not. Try-with-resources, an explicit {@code close()} anywhere
 * in the method, and ownership hand-off (returning the value, storing it, or passing it on) all
 * count as closed.
 */
final class UnclosedResourceCheck extends AstCheck {

    private static final String NAME = "UnclosedResource";
    private static final List<String> CLOSE = List.of("close");

    UnclosedResourceCheck(AstContext ctx) {
        super(ctx, "J0801", NAME, Level.ERROR);
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
        if (!local.closeable || local.tryResource || local.element == null) {
            return;
        }
        Element v = local.element;
        if (facts.calledOn(CLOSE, v) || facts.handedOff(v)) {
            return;
        }
        // An ExecutorService implements AutoCloseable on JDK 19+, but its shutdown path is J1001's
        // subject; reporting it here too would show the same bug twice under two codes.
        if (isExecutorService(v.asType()) || isThread(v.asType())) {
            return;
        }
        if (suppressed(local.path, code) || suppressedElement(v)) {
            return;
        }
        Span span = spanOf(local.tree);
        emit(diag("resource `" + v.getSimpleName() + "` is not closed on all paths", span)
            .label(span, "declared here, never closed"));
    }

    /**
     * The factory case: {@code Files.lines(path).forEach(...)} has no local to hang the finding on,
     * so the call itself is reported unless a variable, a return or another call takes ownership.
     */
    @Override
    public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
        if (isFilesStreamFactory(node) && !suppressed() && !managed(node)) {
            Span span = spanOf(node);
            emit(diag("stream from `Files." + methodSimpleName(node) + "` is not closed", span)
                .label(span, "must be closed"));
        }
        return super.visitMethodInvocation(node, p);
    }

    private boolean isFilesStreamFactory(MethodInvocationTree node) {
        String name = methodSimpleName(node);
        if (!"lines".equals(name) && !"walk".equals(name) && !"list".equals(name)) {
            return false;
        }
        ExpressionTree receiver = methodReceiver(node);
        if (receiver == null) {
            return false;
        }
        String qn = qualifiedTypeName(typeOf(getCurrentPath(), receiver));
        return "java.nio.file.Files".equals(qn);
    }

    private boolean managed(MethodInvocationTree node) {
        TreePath path = getCurrentPath();
        TreePath parent = path.getParentPath();
        if (parent == null) {
            return false;
        }
        Tree leaf = parent.getLeaf();
        if (leaf instanceof VariableTree && ((VariableTree) leaf).getInitializer() == node) {
            return true;
        }
        if (leaf instanceof ReturnTree) {
            return true;
        }
        TreePath grand = parent.getParentPath();
        if (grand != null && grand.getLeaf() instanceof MethodInvocationTree) {
            for (ExpressionTree arg : ((MethodInvocationTree) grand.getLeaf()).getArguments()) {
                if (arg == node) {
                    return true;
                }
            }
        }
        return false;
    }
}
