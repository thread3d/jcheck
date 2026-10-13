package jcheck.engine;

import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import jcheck.model.Level;

/**
 * J0701 UnsafeOutsideUnsafe: an unsafe operation with no {@code @Unsafe(reason = "...")} region
 * around it.
 *
 * <p>Some operations cannot be made safe by types: {@code sun.misc.Unsafe}, {@code jdk.internal.*},
 * reflective {@code setAccessible}, JNI loads and native methods, FFM downcalls, and
 * {@code Thread.stop}/{@code suspend}. Rust marks such a boundary with {@code unsafe}; this check
 * requires the same explicit boundary in Java, with a non-empty reason (itself checked as J0003).
 *
 * <p>The region is the nearest enclosing method or class. A region with a blank reason does not
 * count, so the finding cannot be silenced by an annotation that says nothing.
 */
final class UnsafeOutsideUnsafeCheck extends AstCheck {

    private static final String NAME = "UnsafeOutsideUnsafe";

    UnsafeOutsideUnsafeCheck(AstContext ctx) {
        super(ctx, "J0701", NAME, Level.ERROR);
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
        if (!inUnsafeRegion() && !suppressed()) {
            String name = methodSimpleName(node);
            String receiverName = qualifiedTypeName(receiverType(node));

            if ("setAccessible".equals(name)) {
                report(node, "`setAccessible`");
            } else if ("java.lang.Thread".equals(receiverName)
                && ("stop".equals(name) || "suspend".equals(name) || "resume".equals(name))) {
                report(node, "`Thread." + name + "`");
            } else if (receiverName != null
                && (receiverName.startsWith("sun.misc.Unsafe") || receiverName.startsWith("jdk.internal."))) {
                report(node, "`" + receiverName + "`");
            } else if (receiverName != null
                && ("java.lang.System".equals(receiverName) || "java.lang.Runtime".equals(receiverName))
                && ("load".equals(name) || "loadLibrary".equals(name))) {
                report(node, "`" + receiverName + "." + name + "`");
            } else if (receiverName != null && receiverName.startsWith("java.lang.foreign.")
                && ("downcallHandle".equals(name) || "upcallStub".equals(name))) {
                report(node, "`" + name + "`");
            }
        }
        return super.visitMethodInvocation(node, p);
    }

    @Override
    public Void visitNewClass(NewClassTree node, Void p) {
        if (!inUnsafeRegion() && !suppressed()) {
            String qn = qualifiedTypeName(currentType());
            if (qn != null && (qn.startsWith("sun.misc.Unsafe") || qn.startsWith("jdk.internal."))) {
                report(node, "`" + qn + "`");
            }
        }
        return super.visitNewClass(node, p);
    }

    @Override
    public Void visitMethod(MethodTree node, Void p) {
        if (!inUnsafeRegion() && !suppressed()) {
            Element el = currentElement();
            if (el != null && el.getModifiers().contains(Modifier.NATIVE)) {
                report(node, "native method `" + el.getSimpleName() + "`");
            }
        }
        return super.visitMethod(node, p);
    }

    private void report(Tree node, String what) {
        emit(diag(what + " is used outside an @Unsafe region", spanOf(node))
            .label(spanOf(node), "unsafe operation"));
    }
}
