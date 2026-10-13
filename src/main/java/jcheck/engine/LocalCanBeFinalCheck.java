package jcheck.engine;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import jcheck.model.Applicability;
import jcheck.model.Edit;
import jcheck.model.Level;
import jcheck.model.Span;
import jcheck.model.Suggestion;

/**
 * J0706 LocalCanBeFinal: a local variable that has an initializer and is never written again
 * should be declared {@code final}.
 *
 * <p>The fix is a zero-width insertion of {@code final } at the start of the declaration, which
 * is machine-applicable. Enhanced-for variables are skipped deliberately: {@code final} there is
 * legal but adds noise without expressing intent the reader does not already have.
 */
final class LocalCanBeFinalCheck extends AstCheck {

    private static final String NAME = "LocalCanBeFinal";

    private final Map<Element, TreePath> candidates = new IdentityHashMap<>();
    private final Map<Element, Boolean> written = new IdentityHashMap<>();

    LocalCanBeFinalCheck(AstContext ctx) {
        super(ctx, "J0706", NAME, Level.WARNING);
    }

    @Override
    public Void visitVariable(VariableTree node, Void p) {
        if (node.getInitializer() != null
            && !node.getModifiers().getFlags().contains(Modifier.FINAL)
            && !isEnhancedForVariable()) {
            Element el = ctx.trees.getElement(getCurrentPath());
            if (el != null && el.getKind() == ElementKind.LOCAL_VARIABLE) {
                candidates.put(el, getCurrentPath());
            }
        }
        return super.visitVariable(node, p);
    }

    @Override
    public Void visitAssignment(AssignmentTree node, Void p) {
        recordWrite(node.getVariable());
        return super.visitAssignment(node, p);
    }

    @Override
    public Void visitCompoundAssignment(CompoundAssignmentTree node, Void p) {
        recordWrite(node.getVariable());
        return super.visitCompoundAssignment(node, p);
    }

    @Override
    public Void visitUnary(UnaryTree node, Void p) {
        switch (node.getKind()) {
            case PREFIX_INCREMENT:
            case POSTFIX_INCREMENT:
            case PREFIX_DECREMENT:
            case POSTFIX_DECREMENT:
                recordWrite(node.getExpression());
                break;
            default:
                break;
        }
        return super.visitUnary(node, p);
    }

    private boolean isEnhancedForVariable() {
        TreePath parent = getCurrentPath().getParentPath();
        return parent != null && parent.getLeaf() instanceof EnhancedForLoopTree;
    }

    private void recordWrite(ExpressionTree target) {
        TreePath path = getCurrentPath();
        if (path == null || target == null) {
            return;
        }
        Element el = ctx.trees.getElement(new TreePath(path, target));
        if (el != null) {
            written.put(el, Boolean.TRUE);
        }
    }

    /**
     * Walks the unit and emits findings. Deliberately not named {@code scan}: adding a
     * {@code scan(CompilationUnitTree, Void)} overload would be picked at the call site but not
     * during traversal, which is exactly the kind of subtlety that bites later.
     */
    @Override
    void run() {
        scan(ctx.cu, null);
        report();
    }

    private void report() {
        List<Map.Entry<Element, TreePath>> found = new ArrayList<>(candidates.entrySet());
        found.sort((a, b) -> Long.compare(startOf((VariableTree) a.getValue().getLeaf()),
                                          startOf((VariableTree) b.getValue().getLeaf())));
        for (Map.Entry<Element, TreePath> e : found) {
            if (written.containsKey(e.getKey())
                || suppressed(e.getValue(), code)
                || suppressedElement(e.getKey())) {
                continue;
            }
            VariableTree node = (VariableTree) e.getValue().getLeaf();
            long start = startOf(node);
            long end = ctx.positions.getEndPosition(ctx.cu, node);
            String file = ctx.cu.getSourceFile().getName();
            Span span = ctx.spans.span(file, ctx.cu.getSourceFile(), start, end, -1, -1);
            Span insert = new Span(file, span.lineStart(), span.colStart(), span.lineStart(), span.colStart());
            String name = node.getName().toString();

            jcheck.model.Diagnostic.Builder b = diag(
                "local variable `" + name + "` is never reassigned; it can be `final`", span)
                .label(span, "declared here, never reassigned")
                .suggestion(new Suggestion(Applicability.MACHINE_APPLICABLE,
                    "make `" + name + "` final", List.of(new Edit(insert, "final "))));
            emit(b);
        }
    }

    private long startOf(VariableTree node) {
        return ctx.positions.getStartPosition(ctx.cu, node);
    }
}
