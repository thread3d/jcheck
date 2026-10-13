package jcheck.engine;

import com.sun.source.tree.MethodTree;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import jcheck.model.Applicability;
import jcheck.model.Edit;
import jcheck.model.Level;
import jcheck.model.Span;
import jcheck.model.Suggestion;

/**
 * J1301 AddOverride: a method that overrides a supertype method but does not say so.
 *
 * <p>This is jcheck's own version of an OpenRewrite-style recipe. Error Prone's {@code MissingOverride}
 * covers the same ground when Error Prone is attached; this keeps the fix available without it, and
 * {@code jcheck fix} applies the insertion. The annotation is what turns a signature drift into a
 * compile error instead of a silently uncalled method.
 */
final class AddOverrideCheck extends AstCheck {

    private static final String NAME = "AddOverride";

    AddOverrideCheck(AstContext ctx) {
        super(ctx, "J1301", NAME, Level.WARNING);
    }

    @Override
    public Void visitMethod(MethodTree node, Void p) {
        Element el = currentElement();
        if (el instanceof ExecutableElement && el.getKind() == ElementKind.METHOD
            && !hasAnnotation(el, "Override")
            && !el.getModifiers().contains(Modifier.STATIC)
            && !el.getModifiers().contains(Modifier.PRIVATE)) {
            TypeElement owner = enclosingClass();
            if (owner != null && overridesSomething((ExecutableElement) el, owner) && !suppressed()) {
                Span span = spanOf(node);
                Span insert = new Span(span.file(), span.lineStart(), span.colStart(),
                    span.lineStart(), span.colStart());
                emit(diag("`" + el.getSimpleName() + "` overrides a supertype method "
                        + "but has no @Override", span)
                    .label(span, "missing @Override")
                    .suggestion(new Suggestion(Applicability.MACHINE_APPLICABLE, "add @Override",
                        List.of(new Edit(insert, "@Override\n" + lineIndent(span))))));
            }
        }
        return super.visitMethod(node, p);
    }

    /**
     * Walks the supertype hierarchy and asks the compiler whether the method overrides a declared
     * member. {@code getAllMembers} cannot be used here: it hides the member that is overridden, so
     * the very method to compare against is the one it omits.
     */
    private boolean overridesSomething(ExecutableElement method, TypeElement owner) {
        return overridesInHierarchy(method, owner, new java.util.HashSet<>());
    }

    private boolean overridesInHierarchy(ExecutableElement method, TypeElement owner,
                                         java.util.Set<String> seen) {
        for (TypeMirror supertype : ctx.types.directSupertypes(owner.asType())) {
            Element element = ctx.types.asElement(supertype);
            if (!(element instanceof TypeElement)) {
                continue;
            }
            TypeElement superElement = (TypeElement) element;
            if (!seen.add(superElement.getQualifiedName().toString())) {
                continue;
            }
            for (Element member : superElement.getEnclosedElements()) {
                if (member instanceof ExecutableElement
                    && ctx.elements.overrides(method, (ExecutableElement) member, owner)) {
                    return true;
                }
            }
            if (overridesInHierarchy(method, superElement, seen)) {
                return true;
            }
        }
        return false;
    }
}
