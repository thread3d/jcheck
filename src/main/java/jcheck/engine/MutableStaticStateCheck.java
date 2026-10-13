package jcheck.engine;

import com.sun.source.tree.VariableTree;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import jcheck.model.Level;

/**
 * J0705 MutableStaticState: a static field that is not a constant.
 *
 * <p>Mutable statics make tests order-dependent and publication across threads unsafe. Rust has no
 * safe mutable global; Java should make each deliberate exception visible. A {@code static final}
 * field is not automatically a constant: a final field holding a map, array or other mutable value
 * still changes, so it is reported too.
 *
 * <p>An allow-list annotation — {@code @GuardedBy}, {@code @SuppressJcheck} or {@code @Unsafe} —
 * marks the deliberate global cache or registry. Constants (primitives, {@code String},
 * {@code java.time}, enums and {@code @Immutable} values) are not reported.
 */
final class MutableStaticStateCheck extends AstCheck {

    private static final String NAME = "MutableStaticState";

    MutableStaticStateCheck(AstContext ctx) {
        super(ctx, "J0705", NAME, Level.WARNING);
    }

    @Override
    public Void visitVariable(VariableTree node, Void p) {
        Element el = currentElement();
        if (el != null && el.getKind() == ElementKind.FIELD
            && el.getModifiers().contains(Modifier.STATIC)) {
            Element owner = el.getEnclosingElement();
            if (owner instanceof TypeElement && !isConstantHolder((TypeElement) owner)
                && !allowed(el) && !suppressedElement(el)) {
                TypeMirror type = el.asType();
                boolean constant = el.getModifiers().contains(Modifier.FINAL)
                    && isImmutableType(type);
                if (!constant) {
                    emit(diag("mutable static field `" + el.getSimpleName() + "`", spanOf(node))
                        .label(spanOf(node), "mutable global state"));
                }
            }
        }
        return super.visitVariable(node, p);
    }

    /** Interfaces and annotations hold only implicitly-static constants. */
    private static boolean isConstantHolder(TypeElement owner) {
        return owner.getKind().isInterface()
            || owner.getKind() == ElementKind.ANNOTATION_TYPE;
    }

    private boolean allowed(Element el) {
        return hasAnnotation(el, "SuppressJcheck")
            || hasAnnotation(el, "Unsafe")
            || hasAnnotation(el, "GuardedBy")
            || hasAnnotation(el, "AllowMutableStatic")
            || hasAnnotation(el, "MutableStatic");
    }
}
