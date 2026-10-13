package jcheck.engine;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import jcheck.model.Level;
import jcheck.model.Span;

/**
 * J0603 EscapingMutableInternal: an internal mutable array, collection or {@code Date} that leaves
 * the object without a copy.
 *
 * <p>Returning a field by reference lets the caller mutate state the object believes it owns;
 * storing an argument by reference is the same bug pointing the other way. Either way the fix is a
 * defensive copy at the boundary — {@code List.copyOf}, {@code new ArrayList<>(...)},
 * {@code array.clone()}.
 *
 * <p>A field whose every write is a defensive copy is treated as owning an immutable value, so the
 * documented fixed form — copy in the constructor, return the field from the accessor — is not
 * reported. A field written from a copy in one place and from a parameter in another is not
 * protected by either write and is still reported.
 */
final class EscapingMutableInternalCheck extends AstCheck {

    private static final String NAME = "EscapingMutableInternal";

    private final Map<Element, Boolean> copies = new IdentityHashMap<>();
    private final Set<Element> written = Collections.newSetFromMap(new IdentityHashMap<>());

    EscapingMutableInternalCheck(AstContext ctx) {
        super(ctx, "J0603", NAME, Level.ERROR);
    }

    @Override
    void run() {
        classifyFields();
        scan(ctx.cu, null);
    }

    /**
     * Pass one: for every field, whether every write to it produced a fresh value. A field with no
     * write at all is not classified as copied.
     */
    private void classifyFields() {
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree node, Void p) {
                Element el = safeElement(getCurrentPath());
                if (el != null && el.getKind() == ElementKind.FIELD && node.getInitializer() != null) {
                    record(el, node.getInitializer());
                }
                return super.visitVariable(node, p);
            }

            @Override
            public Void visitAssignment(AssignmentTree node, Void p) {
                Element lhs = elementOf(getCurrentPath(), node.getVariable());
                if (lhs != null && lhs.getKind() == ElementKind.FIELD) {
                    record(lhs, node.getExpression());
                }
                return super.visitAssignment(node, p);
            }

            private void record(Element field, ExpressionTree rhs) {
                written.add(field);
                copies.merge(field, isCopy(rhs), (a, b) -> a && b);
            }
        }.scan(ctx.cu, null);
    }

    private boolean isCopied(Element field) {
        return written.contains(field) && Boolean.TRUE.equals(copies.get(field));
    }

    @Override
    public Void visitReturn(ReturnTree node, Void p) {
        ExpressionTree e = node.getExpression();
        if (e != null && !isCopy(e)) {
            Element field = elementOf(getCurrentPath(), e);
            if (isInternalMutableField(field) && !isCopied(field)
                && !suppressed() && !suppressedElement(field)) {
                Span span = spanOf(e);
                emit(diag("internal mutable field `" + field.getSimpleName()
                        + "` escapes without a copy", span)
                    .label(span, "returned by reference"));
            }
        }
        return super.visitReturn(node, p);
    }

    @Override
    public Void visitAssignment(AssignmentTree node, Void p) {
        TreePath path = getCurrentPath();
        Element lhs = elementOf(path, node.getVariable());
        ExpressionTree rhs = node.getExpression();
        if (isInternalMutableField(lhs) && !isCopied(lhs)
            && !suppressed() && !suppressedElement(lhs)) {
            Element value = elementOf(path, rhs);
            boolean fromOutside = value != null
                && (value.getKind() == ElementKind.PARAMETER
                    || value.getKind() == ElementKind.LOCAL_VARIABLE);
            if (fromOutside && isMutableContainer(value.asType())) {
                Span span = spanOf(node);
                emit(diag("internal mutable field `" + lhs.getSimpleName()
                        + "` is stored without a defensive copy", span)
                    .label(span, "stored by reference"));
            }
        }
        return super.visitAssignment(node, p);
    }

    private boolean isInternalMutableField(Element e) {
        if (e == null || e.getKind() != ElementKind.FIELD || !isMutableContainer(e.asType())) {
            return false;
        }
        TypeElement here = enclosingClass();
        Element owner = e.getEnclosingElement();
        return here != null && here.equals(owner);
    }

    private static boolean isCopy(ExpressionTree e) {
        return e instanceof MethodInvocationTree || e instanceof NewClassTree;
    }
}
