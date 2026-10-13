package jcheck.engine;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import jcheck.model.Level;
import jcheck.model.Span;

/**
 * J0602 MutationDuringIteration: a collection modified inside a for-each over that same collection.
 *
 * <p>Java throws {@code ConcurrentModificationException} only at runtime, usually at a later
 * iteration far from the offending line. This catches the straightforward static case: a mutating
 * call whose receiver resolves to the same variable the loop iterates. Mutating a different
 * collection, iterating a defensive copy, and calling an iterator's own {@code remove()} are all
 * safe and are not reported, nor are concurrent collections with weakly-consistent iterators.
 */
final class MutationDuringIterationCheck extends AstCheck {

    private static final String NAME = "MutationDuringIteration";

    private static final Set<String> MUTATORS = Set.of(
        "add", "addAll", "remove", "removeAll", "removeIf", "retainAll", "clear", "set", "sort",
        "replaceAll", "put", "putAll", "putIfAbsent", "replace", "compute", "computeIfAbsent",
        "computeIfPresent", "merge", "offer", "poll", "pop", "push", "addFirst", "addLast",
        "removeFirst", "removeLast", "offerFirst", "offerLast", "pollFirst", "pollLast");

    private static final Set<String> COLLECTIONS_MUTATORS = Set.of(
        "sort", "shuffle", "reverse", "fill", "addAll", "copy", "swap", "rotate", "replaceAll");

    MutationDuringIterationCheck(AstContext ctx) {
        super(ctx, "J0602", NAME, Level.ERROR);
    }

    @Override
    public Void visitEnhancedForLoop(EnhancedForLoopTree node, Void p) {
        TreePath path = getCurrentPath();
        Element target = elementOf(path, node.getExpression());
        if (target != null && !suppressed() && !isConcurrencySafeType(target.asType())) {
            new MutationScanner(this, target)
                .scan(new TreePath(path, node.getStatement()), null);
        }
        return super.visitEnhancedForLoop(node, p);
    }

    private void report(Element target, MethodInvocationTree node) {
        Span span = spanOf(node);
        emit(diag("collection `" + target.getSimpleName()
                + "` is modified while being iterated", span)
            .label(span, "mutation during iteration"));
    }

    /** Looks for mutating calls in a loop body; nested lambdas are skipped as deferred work. */
    private static final class MutationScanner extends TreePathScanner<Void, Void> {

        private final MutationDuringIterationCheck check;
        private final Element target;

        MutationScanner(MutationDuringIterationCheck check, Element target) {
            this.check = check;
            this.target = target;
        }

        @Override
        public Void visitClass(ClassTree node, Void p) {
            return null;
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree node, Void p) {
            return null;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
            TreePath path = getCurrentPath();
            ExpressionTree select = node.getMethodSelect();
            Element receiver = null;
            if (select instanceof MemberSelectTree) {
                TreePath selectPath = new TreePath(path, select);
                receiver = check.elementOf(selectPath, ((MemberSelectTree) select).getExpression());
            }
            String name = check.methodSimpleName(node);
            if (receiver != null && receiver.equals(target) && MUTATORS.contains(name)) {
                check.report(target, node);
            } else if (isCollections(receiver)) {
                for (ExpressionTree arg : node.getArguments()) {
                    Element ae = check.elementOf(path, arg);
                    if (ae != null && ae.equals(target) && COLLECTIONS_MUTATORS.contains(name)) {
                        check.report(target, node);
                        break;
                    }
                }
            }
            return super.visitMethodInvocation(node, p);
        }

        private static boolean isCollections(Element receiver) {
            return receiver instanceof TypeElement
                && "java.util.Collections".equals(((TypeElement) receiver).getQualifiedName().toString());
        }
    }
}
