package jcheck.engine;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.Tree;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import jcheck.model.Level;
import jcheck.model.Tier;

/**
 * J0003 SuppressionNeedsReason: a suppression or unsafe region with no written reason.
 *
 * <p>A suppression is a promise that the diagnostic is wrong here. Without a reason that promise
 * cannot be reviewed or expired, so jcheck accepts one only when it says why. This is a T2 policy
 * check because it is about the source's own annotations, not about analysis results.
 *
 * <p>It flags three shapes: {@code @SuppressJcheck} without {@code reason}, {@code @Unsafe}
 * without {@code reason}, and a {@code @SuppressWarnings} naming an analyzer or a jcheck code —
 * the form that has nowhere to put a reason and should be {@code @SuppressJcheck} instead.
 */
final class SuppressionNeedsReasonCheck extends AstCheck {

    static final String NAME = "SuppressionNeedsReason";
    private static final Pattern JCODE = Pattern.compile("J\\d{3,4}");

    SuppressionNeedsReasonCheck(AstContext ctx) {
        super(ctx, "J0003", NAME, Level.ERROR, Tier.T2);
    }

    @Override
    public Void visitAnnotation(AnnotationTree node, Void p) {
        if (!suppressed()) {
            String name = annotationName(node);
            switch (name) {
                case "SuppressJcheck": {
                    String reason = stringArg(node, "reason");
                    if (reason == null || reason.isBlank()) {
                        report(node, "suppression has no reason");
                    }
                    break;
                }
                case "Unsafe": {
                    String reason = stringArg(node, "reason");
                    if (reason == null || reason.isBlank()) {
                        report(node, "@Unsafe has no reason; add reason = \"...\"");
                    }
                    break;
                }
                case "SuppressWarnings": {
                    for (String v : stringArgs(node)) {
                        if (isAnalyzerName(v)) {
                            report(node, "@SuppressWarnings(\"" + v + "\") needs a reason; use "
                                + "@SuppressJcheck(code = \"" + (JCODE.matcher(v).matches() ? v : "J0003")
                                + "\", reason = \"...\")");
                            break;
                        }
                    }
                    break;
                }
                default:
                    break;
            }
        }
        return super.visitAnnotation(node, p);
    }

    private void report(AnnotationTree node, String message) {
        emit(diag(message, spanOf(node))
            .label(spanOf(node), "no reason given"));
    }

    private static String annotationName(AnnotationTree node) {
        Tree t = node.getAnnotationType();
        if (t instanceof IdentifierTree) {
            return ((IdentifierTree) t).getName().toString();
        }
        if (t instanceof MemberSelectTree) {
            return ((MemberSelectTree) t).getIdentifier().toString();
        }
        return "";
    }

    /** The string value of a named annotation argument, or null when absent. */
    private static String stringArg(AnnotationTree node, String key) {
        for (ExpressionTree arg : node.getArguments()) {
            if (arg instanceof AssignmentTree) {
                AssignmentTree a = (AssignmentTree) arg;
                String name = a.getVariable() instanceof IdentifierTree
                    ? ((IdentifierTree) a.getVariable()).getName().toString() : "";
                if (name.equals(key) && a.getExpression() instanceof LiteralTree) {
                    Object v = ((LiteralTree) a.getExpression()).getValue();
                    return v == null ? null : v.toString();
                }
            }
        }
        return null;
    }

    /** Every string value in a {@code @SuppressWarnings} argument, single or array form. */
    private static List<String> stringArgs(AnnotationTree node) {
        List<String> out = new ArrayList<>();
        for (ExpressionTree arg : node.getArguments()) {
            collectStrings(arg, out);
        }
        return out;
    }

    private static void collectStrings(ExpressionTree arg, List<String> out) {
        // javac models a single-element annotation value as `value = <expr>`, not a bare literal.
        if (arg instanceof AssignmentTree) {
            collectStrings(((AssignmentTree) arg).getExpression(), out);
        } else if (arg instanceof LiteralTree) {
            Object v = ((LiteralTree) arg).getValue();
            if (v != null) {
                out.add(v.toString());
            }
        } else if (arg instanceof NewArrayTree) {
            for (ExpressionTree e : ((NewArrayTree) arg).getInitializers()) {
                collectStrings(e, out);
            }
        }
    }

    private static boolean isAnalyzerName(String v) {
        if (v == null) {
            return false;
        }
        if (JCODE.matcher(v).matches()) {
            return true;
        }
        switch (v) {
            case "NullAway":
            case "ErrorProne":
            case "SpotBugs":
            case "CheckerFramework":
            case "jcheck":
                return true;
            default:
                return v.startsWith("jcheck:");
        }
    }
}
