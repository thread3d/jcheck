package jcheck.engine;

import com.sun.source.tree.MethodInvocationTree;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import jcheck.model.Level;

/**
 * J0902 TodoMarker: a call to {@code Todo()}, {@code Unreachable()} or {@code Unimplemented()}
 * from jcheck-support.
 *
 * <p>These throw at runtime with the caller's file and line, which is useful while a branch is
 * unfinished and a crash with a stack trace in production. The check is a warning so an
 * unfinished branch does not block compilation; the strict profile promotes it.
 *
 * <p>Matching is by the declaring type rather than the bare method name, so a project's own
 * {@code Todo()} helper is not swept up. When the call cannot be resolved the name alone is used,
 * because at that point the compile error has already withheld every T3 finding anyway.
 */
final class TodoMarkerCheck extends AstCheck {

    private static final String NAME = "TodoMarker";
    private static final Set<String> MARKERS = Set.of("Todo", "Unreachable", "Unimplemented");

    TodoMarkerCheck(AstContext ctx) {
        super(ctx, "J0902", NAME, Level.WARNING);
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
        String name = methodSimpleName(node);
        if (MARKERS.contains(name) && !suppressed() && isMarker(node)) {
            emit(diag("unfinished marker `" + name + "()` in shipped code", spanOf(node))
                .label(spanOf(node), "unfinished branch"));
        }
        return super.visitMethodInvocation(node, p);
    }

    private boolean isMarker(MethodInvocationTree node) {
        Element el = currentElement();
        if (!(el instanceof ExecutableElement)) {
            // Unresolved call: the name is the only evidence available.
            return true;
        }
        Element owner = el.getEnclosingElement();
        if (!(owner instanceof TypeElement)) {
            return true;
        }
        String qn = ((TypeElement) owner).getQualifiedName().toString();
        return qn.startsWith("jcheck.support");
    }
}
