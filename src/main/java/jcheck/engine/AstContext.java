package jcheck.engine;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import java.util.List;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import jcheck.catalog.Catalog;
import jcheck.model.Diagnostic;

/**
 * Services every T3 AST check needs for one compilation unit.
 *
 * <p>One context is created per unit and shared by every check in the pack, so the pack pays for
 * {@code Trees}, {@code Elements} and {@code Types} lookups once. The context is deliberately not
 * mutable state: checks keep their own bookkeeping and only read from here.
 */
final class AstContext {

    final Trees trees;
    final Elements elements;
    final Types types;
    final SourcePositions positions;
    final CompilationUnitTree cu;
    final SpanResolver spans;
    final Catalog catalog;
    final List<Diagnostic> sink;

    AstContext(JavacTask task, Trees trees, CompilationUnitTree cu, SpanResolver spans,
               Catalog catalog, List<Diagnostic> sink) {
        this.trees = trees;
        this.elements = task.getElements();
        this.types = task.getTypes();
        this.positions = trees.getSourcePositions();
        this.cu = cu;
        this.spans = spans;
        this.catalog = catalog;
        this.sink = sink;
    }
}
