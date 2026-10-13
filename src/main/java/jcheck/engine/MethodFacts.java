package jcheck.engine;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;

/**
 * Everything J0801 and J1001 need to know about one method body: which locals were created,
 * which methods were called on which receiver, and where a local was handed off.
 *
 * <p>Collected in one pass instead of each check re-walking the body, and deliberately shallow in
 * one direction: nested and local classes are not descended into, because a resource created there
 * belongs to that class's own methods, which are visited separately.
 */
final class MethodFacts {

    static final class Local {
        final Element element;
        final VariableTree tree;
        final TreePath path;
        final boolean tryResource;
        final boolean closeable;

        Local(Element element, VariableTree tree, TreePath path, boolean tryResource, boolean closeable) {
            this.element = element;
            this.tree = tree;
            this.path = path;
            this.tryResource = tryResource;
            this.closeable = closeable;
        }
    }

    static final class Call {
        final String name;
        final Element receiver;
        final List<Element> arguments;

        Call(String name, Element receiver, List<Element> arguments) {
            this.name = name;
            this.receiver = receiver;
            this.arguments = arguments;
        }
    }

    final List<Local> locals = new ArrayList<>();
    final List<Call> calls = new ArrayList<>();
    final List<Element> returned = new ArrayList<>();
    final List<Element> storedIntoField = new ArrayList<>();

    static MethodFacts collect(AstCheck check, TreePath methodPath) {
        MethodFacts facts = new MethodFacts();
        new Scanner(check, facts).scan(methodPath, null);
        return facts;
    }

    /** Whether one of the named methods is called with this exact receiver. */
    boolean calledOn(Iterable<String> names, Element receiver) {
        if (receiver == null) {
            return false;
        }
        for (Call c : calls) {
            if (c.receiver != null && c.receiver.equals(receiver)) {
                for (String n : names) {
                    if (n.equals(c.name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Whether the value escapes this method: returned, stored in a field, or passed on. */
    boolean handedOff(Element e) {
        if (e == null) {
            return false;
        }
        if (returned.contains(e) || storedIntoField.contains(e)) {
            return true;
        }
        for (Call c : calls) {
            if (c.arguments.contains(e)) {
                return true;
            }
        }
        return false;
    }

    private static final class Scanner extends TreePathScanner<Void, Void> {

        private final AstCheck check;
        private final MethodFacts facts;

        Scanner(AstCheck check, MethodFacts facts) {
            this.check = check;
            this.facts = facts;
        }

        @Override
        public Void visitClass(ClassTree node, Void p) {
            return null;
        }

        @Override
        public Void visitVariable(VariableTree node, Void p) {
            TreePath path = getCurrentPath();
            Element el = check.safeElement(path);
            if (el != null && el.getKind() == ElementKind.LOCAL_VARIABLE) {
                TreePath parent = path.getParentPath();
                boolean tryResource = parent != null && parent.getLeaf() instanceof TryTree
                    && ((TryTree) parent.getLeaf()).getResources().contains(node);
                facts.locals.add(new Local(el, node, path, tryResource,
                    check.isCloseable(check.typeAt(path))));
            }
            return super.visitVariable(node, p);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
            TreePath path = getCurrentPath();
            Element receiver = null;
            ExpressionTree select = node.getMethodSelect();
            if (select instanceof MemberSelectTree) {
                TreePath selectPath = new TreePath(path, select);
                receiver = check.elementOf(selectPath, ((MemberSelectTree) select).getExpression());
            }
            List<Element> args = new ArrayList<>();
            for (ExpressionTree arg : node.getArguments()) {
                Element ae = check.elementOf(path, arg);
                if (ae != null) {
                    args.add(ae);
                }
            }
            facts.calls.add(new Call(check.methodSimpleName(node), receiver, args));
            return super.visitMethodInvocation(node, p);
        }

        @Override
        public Void visitReturn(ReturnTree node, Void p) {
            if (node.getExpression() != null) {
                Element e = check.elementOf(getCurrentPath(), node.getExpression());
                if (e != null) {
                    facts.returned.add(e);
                }
            }
            return super.visitReturn(node, p);
        }

        @Override
        public Void visitAssignment(AssignmentTree node, Void p) {
            TreePath path = getCurrentPath();
            Element lhs = check.elementOf(path, node.getVariable());
            Element rhs = check.elementOf(path, node.getExpression());
            if (lhs != null && lhs.getKind() == ElementKind.FIELD && rhs != null) {
                facts.storedIntoField.add(rhs);
            }
            return super.visitAssignment(node, p);
        }
    }
}
