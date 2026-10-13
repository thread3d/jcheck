package jcheck.engine;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import jcheck.catalog.CatalogEntry;
import jcheck.model.Diagnostic;
import jcheck.model.Level;
import jcheck.model.Span;
import jcheck.model.Tier;

/**
 * Base class for the T3 AST checks: everything they need that is not check-specific.
 *
 * <p>Subclasses are one compilation unit at a time and read state from {@link AstContext}. The
 * helpers here exist so the checks themselves read as the rule they implement rather than as
 * javax.lang.model plumbing.
 */
abstract class AstCheck extends TreePathScanner<Void, Void> {

    static final String SOURCE = "jcheck";

    final AstContext ctx;
    final String code;
    final String sourceKey;
    final Level level;
    final int tier;

    AstCheck(AstContext ctx, String code, String sourceKey, Level level) {
        this(ctx, code, sourceKey, level, Tier.T3);
    }

    AstCheck(AstContext ctx, String code, String sourceKey, Level level, int tier) {
        this.ctx = ctx;
        this.code = code;
        this.sourceKey = sourceKey;
        this.level = level;
        this.tier = tier;
    }

    /** Walks the unit; checks that need several passes override this. */
    void run() {
        scan(ctx.cu, null);
    }

    // ---- locations --------------------------------------------------------

    final String file() {
        return ctx.cu.getSourceFile().getName();
    }

    final Span spanOf(Tree t) {
        long s = ctx.positions.getStartPosition(ctx.cu, t);
        long e = ctx.positions.getEndPosition(ctx.cu, t);
        return ctx.spans.span(file(), ctx.cu.getSourceFile(), s, e, -1, -1);
    }

    final Span spanOf(long start, long end) {
        return ctx.spans.span(file(), ctx.cu.getSourceFile(), start, end, -1, -1);
    }

    final Span pointSpanOf(Tree t) {
        Span s = spanOf(t);
        if (s == null) {
            return null;
        }
        return new Span(s.file(), s.lineStart(), s.colStart(), s.lineStart(), s.colStart());
    }

    /** The source text of a tree, or null when the offsets are unavailable. */
    final String text(Tree t) {
        long s = ctx.positions.getStartPosition(ctx.cu, t);
        long e = ctx.positions.getEndPosition(ctx.cu, t);
        String content = ctx.spans.textFor(file(), ctx.cu.getSourceFile()).content();
        if (s < 0 || e < 0 || s > e) {
            return null;
        }
        int si = (int) Math.min(s, content.length());
        int ei = (int) Math.min(e, content.length());
        return content.substring(si, ei);
    }

    // ---- elements and types ----------------------------------------------

    final Element safeElement(TreePath path) {
        if (path == null) {
            return null;
        }
        try {
            return ctx.trees.getElement(path);
        } catch (RuntimeException e) {
            return null;
        }
    }

    final Element elementOf(TreePath parent, Tree t) {
        if (parent == null || t == null) {
            return null;
        }
        try {
            return ctx.trees.getElement(new TreePath(parent, t));
        } catch (RuntimeException e) {
            return null;
        }
    }

    final Element currentElement() {
        return safeElement(getCurrentPath());
    }

    final TypeMirror typeOf(TreePath parent, Tree t) {
        if (parent == null || t == null) {
            return null;
        }
        try {
            return ctx.trees.getTypeMirror(new TreePath(parent, t));
        } catch (RuntimeException e) {
            return null;
        }
    }

    final TypeMirror currentType() {
        return typeAt(getCurrentPath());
    }

    final TypeMirror typeAt(TreePath path) {
        if (path == null) {
            return null;
        }
        try {
            return ctx.trees.getTypeMirror(path);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The nearest enclosing class, record, interface or enum element, or null at top level. */
    final TypeElement enclosingClass() {
        TreePath p = getCurrentPath();
        while (p != null) {
            Element e = safeElement(p);
            if (e instanceof TypeElement && (e.getKind().isClass() || e.getKind().isInterface())) {
                return (TypeElement) e;
            }
            p = p.getParentPath();
        }
        return null;
    }

    /** The nearest enclosing method, constructor or initializer element, or null. */
    final ExecutableElement enclosingExecutable() {
        TreePath p = getCurrentPath();
        while (p != null) {
            Element e = safeElement(p);
            if (e instanceof ExecutableElement) {
                return (ExecutableElement) e;
            }
            p = p.getParentPath();
        }
        return null;
    }

    // ---- annotations ------------------------------------------------------

    final boolean matchesAnnotation(AnnotationMirror m, String simpleName) {
        Element a = m.getAnnotationType().asElement();
        if (a == null) {
            return false;
        }
        String sn = a.getSimpleName().toString();
        if (sn.equals(simpleName)) {
            return true;
        }
        String qn = a instanceof TypeElement
            ? ((TypeElement) a).getQualifiedName().toString() : sn;
        return qn.equals(simpleName) || qn.endsWith("." + simpleName);
    }

    final AnnotationMirror annotation(Element e, String simpleName) {
        if (e == null) {
            return null;
        }
        for (AnnotationMirror m : e.getAnnotationMirrors()) {
            if (matchesAnnotation(m, simpleName)) {
                return m;
            }
        }
        return null;
    }

    final boolean hasAnnotation(Element e, String simpleName) {
        return annotation(e, simpleName) != null;
    }

    final String annotationValue(AnnotationMirror m, String key) {
        if (m == null) {
            return null;
        }
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e
                : m.getElementValues().entrySet()) {
            if (e.getKey().getSimpleName().contentEquals(key)) {
                Object v = e.getValue().getValue();
                return v == null ? null : v.toString();
            }
        }
        return null;
    }

    /** Whether the element carries a {@code @SuppressJcheck(code = ...)} for this check's code. */
    final boolean suppressed(TreePath from, String forCode) {
        TreePath p = from;
        while (p != null) {
            Element e = safeElement(p);
            AnnotationMirror s = annotation(e, "SuppressJcheck");
            if (s != null) {
                String c = annotationValue(s, "code");
                if (c != null && c.equals(forCode)) {
                    return true;
                }
            }
            p = p.getParentPath();
        }
        return false;
    }

    final boolean suppressed() {
        return suppressed(getCurrentPath(), code);
    }

    /** Whether a declaration that is not on the current path (a field, say) is suppressed. */
    final boolean suppressedElement(Element e) {
        AnnotationMirror s = annotation(e, "SuppressJcheck");
        if (s == null) {
            return false;
        }
        String c = annotationValue(s, "code");
        return c != null && c.equals(code);
    }

    /** True when the current node sits inside an {@code @Unsafe(reason = "...")} region. */
    final boolean inUnsafeRegion() {
        TreePath p = getCurrentPath();
        while (p != null) {
            Element e = safeElement(p);
            AnnotationMirror u = annotation(e, "Unsafe");
            if (u != null) {
                String reason = annotationValue(u, "reason");
                if (reason != null && !reason.isBlank()) {
                    return true;
                }
            }
            p = p.getParentPath();
        }
        return false;
    }

    /** True when any enclosing class, method or package carries the named annotation. */
    final boolean enclosingAnnotated(String simpleName) {
        TreePath p = getCurrentPath();
        while (p != null) {
            Element e = safeElement(p);
            if (e != null) {
                boolean declaration = e.getKind().isClass() || e.getKind().isInterface()
                    || e.getKind() == ElementKind.METHOD || e.getKind() == ElementKind.PACKAGE;
                if (declaration && annotation(e, simpleName) != null) {
                    return true;
                }
            }
            p = p.getParentPath();
        }
        return false;
    }

    /** The nearest enclosing class that carries the annotation, or null. */
    final TypeElement enclosingAnnotatedClass(String simpleName) {
        TreePath p = getCurrentPath();
        while (p != null) {
            Element e = safeElement(p);
            if (e instanceof TypeElement && annotation(e, simpleName) != null) {
                return (TypeElement) e;
            }
            p = p.getParentPath();
        }
        return null;
    }

    // ---- type predicates --------------------------------------------------

    /** The primitive kind of a value after unboxing, or null when it is not a primitive value. */
    final TypeKind primitiveKind(TypeMirror t) {
        if (t == null) {
            return null;
        }
        if (t.getKind().isPrimitive()) {
            return t.getKind();
        }
        try {
            TypeMirror u = ctx.types.unboxedType(t);
            return u.getKind().isPrimitive() ? u.getKind() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Erased qualified name, with {@code []} for arrays; null when unknown. */
    final String qualifiedTypeName(TypeMirror t) {
        if (t == null) {
            return null;
        }
        if (t.getKind() == TypeKind.ARRAY) {
            return qualifiedTypeName(((ArrayType) t).getComponentType()) + "[]";
        }
        if (t.getKind().isPrimitive()) {
            return t.toString();
        }
        TypeMirror erased = ctx.types.erasure(t);
        Element el = ctx.types.asElement(erased);
        if (el instanceof TypeElement) {
            return ((TypeElement) el).getQualifiedName().toString();
        }
        return erased.toString();
    }

    final boolean isSubtypeOf(TypeMirror t, String qualifiedName) {
        if (t == null) {
            return false;
        }
        TypeElement te = ctx.elements.getTypeElement(qualifiedName);
        if (te == null) {
            return false;
        }
        try {
            return ctx.types.isSubtype(ctx.types.erasure(t), ctx.types.erasure(te.asType()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Values that cannot be mutated through a reference: primitives, boxed, String, java.time. */
    final boolean isImmutableType(TypeMirror t) {
        if (t == null) {
            return false;
        }
        if (t.getKind() == TypeKind.ARRAY) {
            return false;
        }
        if (primitiveKind(t) != null) {
            return true;
        }
        String qn = qualifiedTypeName(t);
        if (qn == null) {
            return false;
        }
        switch (qn) {
            case "java.lang.String":
            case "java.math.BigInteger":
            case "java.math.BigDecimal":
            case "java.util.UUID":
            case "java.net.URI":
            case "java.util.Optional":
            case "java.util.OptionalInt":
            case "java.util.OptionalLong":
            case "java.util.OptionalDouble":
            case "java.lang.Class":
                return true;
            default:
                break;
        }
        if (qn.startsWith("java.time.")) {
            return true;
        }
        Element el = ctx.types.asElement(ctx.types.erasure(t));
        if (el != null && el.getKind() == ElementKind.ENUM) {
            return true;
        }
        return el != null && hasAnnotation(el, "Immutable");
    }

    /** Arrays, collections, maps and the other types whose contents a caller can change. */
    final boolean isMutableContainer(TypeMirror t) {
        if (t == null) {
            return false;
        }
        if (t.getKind() == TypeKind.ARRAY) {
            return true;
        }
        if (isImmutableType(t)) {
            return false;
        }
        if (isSubtypeOf(t, "java.util.Collection") || isSubtypeOf(t, "java.util.Map")
            || isSubtypeOf(t, "java.util.Date")) {
            return true;
        }
        String qn = qualifiedTypeName(t);
        return "java.lang.StringBuilder".equals(qn) || "java.lang.StringBuffer".equals(qn);
    }

    /** Types whose own operations are safe to share without an external lock. */
    final boolean isConcurrencySafeType(TypeMirror t) {
        String qn = qualifiedTypeName(t);
        if (qn == null) {
            return false;
        }
        if (qn.startsWith("java.util.concurrent.atomic.")
            || qn.startsWith("java.util.concurrent.Concurrent")
            || qn.startsWith("java.util.concurrent.CopyOnWrite")
            || qn.startsWith("java.util.concurrent.locks.")) {
            return true;
        }
        return isSubtypeOf(t, "java.util.concurrent.ConcurrentMap")
            || isSubtypeOf(t, "java.util.concurrent.BlockingQueue");
    }

    final boolean isCloseable(TypeMirror t) {
        return isSubtypeOf(t, "java.lang.AutoCloseable");
    }

    final boolean isExecutorService(TypeMirror t) {
        return isSubtypeOf(t, "java.util.concurrent.ExecutorService");
    }

    final boolean isThread(TypeMirror t) {
        String qn = qualifiedTypeName(t);
        return "java.lang.Thread".equals(qn);
    }

    // ---- literal and invocation helpers -----------------------------------

    /** Whether an expression is a compile-time constant, so it cannot overflow at runtime. */
    final boolean isConstantExpression(TreePath parent, ExpressionTree e) {
        if (e == null) {
            return false;
        }
        if (e instanceof LiteralTree) {
            return true;
        }
        if (e instanceof ParenthesizedTree) {
            return isConstantExpression(parent, ((ParenthesizedTree) e).getExpression());
        }
        if (e instanceof UnaryTree) {
            Tree.Kind k = e.getKind();
            if (k == Tree.Kind.UNARY_MINUS || k == Tree.Kind.UNARY_PLUS
                || k == Tree.Kind.BITWISE_COMPLEMENT) {
                return isConstantExpression(parent, ((UnaryTree) e).getExpression());
            }
            return false;
        }
        if (e instanceof TypeCastTree) {
            return isConstantExpression(parent, ((TypeCastTree) e).getExpression());
        }
        Element el = elementOf(parent, e);
        return el instanceof VariableElement && ((VariableElement) el).getConstantValue() != null;
    }

    final String methodSimpleName(MethodInvocationTree node) {
        ExpressionTree select = node.getMethodSelect();
        if (select instanceof MemberSelectTree) {
            return ((MemberSelectTree) select).getIdentifier().toString();
        }
        if (select instanceof IdentifierTree) {
            return ((IdentifierTree) select).getName().toString();
        }
        return "";
    }

    /** The receiver of a method call, or null for a bare/static-style call on an identifier. */
    final ExpressionTree methodReceiver(MethodInvocationTree node) {
        ExpressionTree select = node.getMethodSelect();
        if (select instanceof MemberSelectTree) {
            return ((MemberSelectTree) select).getExpression();
        }
        return null;
    }

    /** The static type of a method call's receiver, resolved through the member-select path. */
    final TypeMirror receiverType(MethodInvocationTree node) {
        ExpressionTree select = node.getMethodSelect();
        if (!(select instanceof MemberSelectTree) || getCurrentPath() == null) {
            return null;
        }
        try {
            TreePath selectPath = new TreePath(getCurrentPath(), select);
            return typeOf(selectPath, ((MemberSelectTree) select).getExpression());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The simple name of a variable or field reference, or null. */
    final String referencedName(ExpressionTree e) {
        if (e instanceof IdentifierTree) {
            return ((IdentifierTree) e).getName().toString();
        }
        if (e instanceof MemberSelectTree) {
            return ((MemberSelectTree) e).getIdentifier().toString();
        }
        return null;
    }

    // ---- reporting --------------------------------------------------------

    /**
     * A diagnostic builder pre-filled from this check's catalog row.
     *
     * <p>Help lines come from the catalog so `docs/` and the catalog cannot drift: the message
     * itself is written by the check, because it has the concrete operands the catalog's
     * placeholder headline cannot express.
     */
    final Diagnostic.Builder diag(String message, Span primary) {
        Diagnostic.Builder b = Diagnostic.builder(code)
            .source(SOURCE)
            .sourceKey(sourceKey)
            .level(level)
            .tier(tier)
            .message(message)
            .primary(primary)
            .seq(1_000_000 + ctx.sink.size());
        CatalogEntry entry = ctx.catalog.lookup(SOURCE, sourceKey);
        if (entry != null) {
            for (String h : entry.help) {
                b.help(h);
            }
        }
        return b;
    }

    /** The leading whitespace of the line a span starts on, for indented insertions. */
    final String lineIndent(Span span) {
        if (span == null) {
            return "";
        }
        String line = ctx.spans.textFor(file(), ctx.cu.getSourceFile()).line(span.lineStart());
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    final void emit(Diagnostic.Builder b) {
        ctx.sink.add(b.build());
    }

    final boolean hasModifier(Element e, Modifier m) {
        return e != null && e.getModifiers().contains(m);
    }
}
