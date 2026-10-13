package jcheck.engine;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import java.util.List;
import javax.lang.model.type.TypeKind;
import jcheck.model.Applicability;
import jcheck.model.Edit;
import jcheck.model.Level;
import jcheck.model.Span;
import jcheck.model.Suggestion;

/**
 * J0301 UncheckedArithmetic: raw {@code +}, {@code -} and {@code *} on {@code int} or {@code long}
 * inside a {@code @Checked} class or package.
 *
 * <p>Off by default everywhere else: banning raw arithmetic project-wide would be unusable. The
 * annotation opts a parser, protocol handler or size-carrying module into {@code Math.addExact}
 * and friends, which throw instead of wrapping.
 *
 * <p>Mixed-width expressions that already widen to {@code long} before the operation are not
 * reported, and neither are expressions whose operands are both compile-time constants.
 */
final class UncheckedArithmeticCheck extends AstCheck {

    private static final String NAME = "UncheckedArithmetic";

    UncheckedArithmeticCheck(AstContext ctx) {
        super(ctx, "J0301", NAME, Level.ERROR);
    }

    @Override
    public Void visitBinary(BinaryTree node, Void p) {
        Tree.Kind op = node.getKind();
        boolean arithmetic = op == Tree.Kind.PLUS || op == Tree.Kind.MINUS
            || op == Tree.Kind.MULTIPLY;
        if (arithmetic && enclosingAnnotated("Checked") && !suppressed()) {
            TreePath path = getCurrentPath();
            TypeKind left = primitiveKind(typeOf(path, node.getLeftOperand()));
            TypeKind right = primitiveKind(typeOf(path, node.getRightOperand()));
            boolean sameWidth = left != null && left == right
                && (left == TypeKind.INT || left == TypeKind.LONG);
            boolean constant = isConstantExpression(path, node.getLeftOperand())
                && isConstantExpression(path, node.getRightOperand());
            if (sameWidth && !constant) {
                report(node, left, op);
            }
        }
        return super.visitBinary(node, p);
    }

    private void report(BinaryTree node, TypeKind kind, Tree.Kind op) {
        Span span = spanOf(node);
        String type = kind == TypeKind.INT ? "int" : "long";
        String helper = helper(op);
        jcheck.model.Diagnostic.Builder b = diag(
            "raw " + type + " arithmetic `" + shorten(node) + "` may overflow", span)
            .label(span, "unchecked arithmetic");
        String left = text(node.getLeftOperand());
        String right = text(node.getRightOperand());
        if (!isArithmetic(node.getLeftOperand()) && !isArithmetic(node.getRightOperand())
            && left != null && right != null) {
            b.suggestion(new Suggestion(Applicability.MACHINE_APPLICABLE, "use Math." + helper,
                List.of(new Edit(span, "Math." + helper + "(" + left + ", " + right + ")"))));
        } else {
            b.help("use Math." + helper + " to fail on overflow instead of wrapping");
        }
        emit(b);
    }

    private static boolean isArithmetic(ExpressionTree e) {
        Tree.Kind k = e.getKind();
        return k == Tree.Kind.PLUS || k == Tree.Kind.MINUS || k == Tree.Kind.MULTIPLY;
    }

    private static String helper(Tree.Kind op) {
        switch (op) {
            case PLUS:
                return "addExact";
            case MINUS:
                return "subtractExact";
            default:
                return "multiplyExact";
        }
    }

    private static String shorten(BinaryTree node) {
        String t = node.toString();
        return t.length() > 48 ? t.substring(0, 45) + "..." : t;
    }
}
