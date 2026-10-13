package jcheck.engine;

import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.TreePath;
import java.util.List;
import java.util.Locale;
import javax.lang.model.element.Element;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import jcheck.model.Applicability;
import jcheck.model.Edit;
import jcheck.model.Level;
import jcheck.model.Span;
import jcheck.model.Suggestion;

/**
 * J0302 LossyCast: a narrowing primitive conversion that silently discards bits.
 *
 * <p>Covers {@code (int)} on a {@code long}, {@code (short)}/{@code (byte)}/{@code (char)} on an
 * {@code int}, the float/double to integral conversions, and the implicit narrowing a compound
 * assignment performs. A cast whose operand is a constant that already fits the target is not
 * reported: no information can be lost.
 *
 * <p>{@code (int) someLong} gets a machine-applicable {@code Math.toIntExact(someLong)} fix, which
 * is the only conversion the JDK offers an exact helper for. The rest get the help line.
 */
final class LossyCastCheck extends AstCheck {

    private static final String NAME = "LossyCast";

    LossyCastCheck(AstContext ctx) {
        super(ctx, "J0302", NAME, Level.ERROR);
    }

    @Override
    public Void visitTypeCast(TypeCastTree node, Void p) {
        TreePath path = getCurrentPath();
        TypeKind from = primitiveKind(typeOf(path, node.getExpression()));
        TypeKind to = primitiveKind(currentType());
        if (from != null && to != null && from != to
            && lossy(from, to)
            && !constantFits(path, node.getExpression(), to)
            && !suppressed()) {
            Span span = spanOf(node);
            String operand = name(node.getExpression());
            jcheck.model.Diagnostic.Builder b = diag(
                "cast from `" + lower(from) + "` to `" + lower(to) + "` may lose information", span)
                .label(span, "narrowing conversion");
            if (from == TypeKind.LONG && to == TypeKind.INT) {
                String operandText = text(node.getExpression());
                if (operandText != null) {
                    b.suggestion(new Suggestion(Applicability.MACHINE_APPLICABLE,
                        "convert with Math.toIntExact",
                        List.of(new Edit(span, "Math.toIntExact(" + operandText + ")"))));
                } else {
                    b.help("use Math.toIntExact(" + operand + ") or an explicit range check");
                }
            }
            emit(b);
        }
        return super.visitTypeCast(node, p);
    }

    /**
     * {@code a += b} is {@code a = (TypeA) (a + b)}. When the right operand is wider than the
     * left, that implicit cast can drop bits just like an explicit one.
     */
    @Override
    public Void visitCompoundAssignment(CompoundAssignmentTree node, Void p) {
        TreePath path = getCurrentPath();
        TypeKind left = primitiveKind(typeOf(path, node.getVariable()));
        TypeKind right = primitiveKind(typeOf(path, node.getExpression()));
        if (left != null && right != null && left != right
            && lossy(right, left)
            && !constantFits(path, node.getExpression(), left)
            && !suppressed()) {
            Span span = spanOf(node);
            emit(diag("`" + name(node) + "` narrows " + lower(right) + " to " + lower(left)
                    + " and may lose information", span)
                .label(span, "narrowing compound assignment"));
        }
        return super.visitCompoundAssignment(node, p);
    }

    private static String lower(TypeKind k) {
        return k.name().toLowerCase(Locale.ROOT);
    }

    private static String name(Tree e) {
        String t = e.toString();
        return t.length() > 40 ? t.substring(0, 37) + "..." : t;
    }

    /** True when the source kind has values the target kind cannot represent. */
    static boolean lossy(TypeKind from, TypeKind to) {
        switch (from) {
            case LONG:
                return to == TypeKind.INT || to == TypeKind.SHORT || to == TypeKind.BYTE
                    || to == TypeKind.CHAR || to == TypeKind.FLOAT;
            case INT:
                return to == TypeKind.SHORT || to == TypeKind.BYTE || to == TypeKind.CHAR;
            case SHORT:
                return to == TypeKind.BYTE || to == TypeKind.CHAR;
            case CHAR:
                return to == TypeKind.BYTE || to == TypeKind.SHORT;
            case DOUBLE:
                return to == TypeKind.FLOAT || to == TypeKind.LONG || to == TypeKind.INT
                    || to == TypeKind.SHORT || to == TypeKind.BYTE || to == TypeKind.CHAR;
            case FLOAT:
                return to == TypeKind.LONG || to == TypeKind.INT || to == TypeKind.SHORT
                    || to == TypeKind.BYTE || to == TypeKind.CHAR;
            default:
                return false;
        }
    }

    /** True when the operand is a constant whose value already fits the target kind. */
    private boolean constantFits(TreePath parent, ExpressionTree e, TypeKind to) {
        Object value = constantValue(parent, e);
        if (value == null) {
            return false;
        }
        long v;
        if (value instanceof Character) {
            v = (Character) value;
        } else if (value instanceof Number) {
            v = ((Number) value).longValue();
        } else {
            return false;
        }
        switch (to) {
            case BYTE:
                return v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE;
            case SHORT:
                return v >= Short.MIN_VALUE && v <= Short.MAX_VALUE;
            case INT:
                return v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE;
            case CHAR:
                return v >= Character.MIN_VALUE && v <= Character.MAX_VALUE;
            default:
                return false;
        }
    }

    private Object constantValue(TreePath parent, ExpressionTree e) {
        if (e == null) {
            return null;
        }
        if (e instanceof LiteralTree) {
            return ((LiteralTree) e).getValue();
        }
        if (e instanceof ParenthesizedTree) {
            return constantValue(parent, ((ParenthesizedTree) e).getExpression());
        }
        if (e instanceof UnaryTree) {
            Tree.Kind k = e.getKind();
            if (k == Tree.Kind.UNARY_MINUS || k == Tree.Kind.UNARY_PLUS) {
                Object inner = constantValue(parent, ((UnaryTree) e).getExpression());
                if (inner instanceof Number) {
                    long v = ((Number) inner).longValue();
                    return k == Tree.Kind.UNARY_MINUS ? -v : v;
                }
            }
            return null;
        }
        Element el = elementOf(parent, e);
        return el instanceof VariableElement ? ((VariableElement) el).getConstantValue() : null;
    }
}
