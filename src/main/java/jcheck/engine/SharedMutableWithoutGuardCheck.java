package jcheck.engine;

import com.sun.source.tree.VariableTree;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import jcheck.model.Level;

/**
 * J0601 SharedMutableWithoutGuard: a mutable instance field on a {@code @ThreadSafe} class with no
 * {@code @GuardedBy}.
 *
 * <p>Java does not check that a {@code @ThreadSafe} promise is kept, so the annotation is
 * decoration until something holds the class to it. A field that is {@code final}, {@code static},
 * {@code volatile}, already thread-safe by type (an atomic or concurrent collection), or annotated
 * {@code @GuardedBy} needs no further guard.
 */
final class SharedMutableWithoutGuardCheck extends AstCheck {

    private static final String NAME = "SharedMutableWithoutGuard";

    SharedMutableWithoutGuardCheck(AstContext ctx) {
        super(ctx, "J0601", NAME, Level.ERROR);
    }

    @Override
    public Void visitVariable(VariableTree node, Void p) {
        Element el = currentElement();
        if (el != null && el.getKind() == ElementKind.FIELD) {
            Element owner = el.getEnclosingElement();
            if (owner instanceof TypeElement && hasAnnotation(owner, "ThreadSafe")) {
                boolean mutable = !el.getModifiers().contains(Modifier.FINAL)
                    && !el.getModifiers().contains(Modifier.STATIC)
                    && !el.getModifiers().contains(Modifier.VOLATILE);
                if (mutable && !hasAnnotation(el, "GuardedBy")
                    && !isConcurrencySafeType(el.asType())
                    && !suppressed() && !suppressedElement(el)) {
                    emit(diag("shared mutable field `" + el.getSimpleName()
                            + "` has no @GuardedBy", spanOf(node))
                        .label(spanOf(node), "unguarded shared state"));
                }
            }
        }
        return super.visitVariable(node, p);
    }
}
