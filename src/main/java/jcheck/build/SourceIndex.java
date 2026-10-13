package jcheck.build;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * A parse-only index of the source tree: for every file, a hash of its API surface and the set of
 * project types it references.
 *
 * <p>The API hash is taken from the declaration text up to the body, so a change inside a method
 * leaves it alone while a change to a signature, a modifier, a field type or a supertype changes
 * it. That is what lets the incremental planner decide that a file's dependents only need
 * recompiling when the file's contract changed.
 *
 * <p>References are deliberately over-approximate: a simple name shared by several project types
 * references all of them, which can only ever cause extra recompilation, never a missed dependent.
 */
public final class SourceIndex {

    /** One file's indexed facts. */
    public static final class Entry {
        public final long size;
        public final long mtime;
        public final String apiHash;
        /** Qualified names declared by this file. */
        public final Set<String> types;
        /** Qualified names of project types this file references. */
        public final Set<String> references;

        public Entry(long size, long mtime, String apiHash, Set<String> types,
                     Set<String> references) {
            this.size = size;
            this.mtime = mtime;
            this.apiHash = apiHash;
            this.types = Set.copyOf(types);
            this.references = Set.copyOf(references);
        }
    }

    private SourceIndex() {
    }

    /** Parses every source and returns its entry, keyed by absolute path. */
    public static Map<String, Entry> index(List<Path> sources) {
        Map<String, Entry> out = new LinkedHashMap<>();
        if (sources.isEmpty()) {
            return out;
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return out;
        }
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null,
                StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(sources);
            JavacTask task = (JavacTask) compiler.getTask(null, fm, null, List.of("-proc:none"),
                null, units);
            SourcePositions positions = Trees.instance(task).getSourcePositions();
            List<CompilationUnitTree> parsed = new ArrayList<>();
            for (CompilationUnitTree cu : task.parse()) {
                parsed.add(cu);
            }

            Map<String, Set<String>> bySimpleName = new HashMap<>();
            Map<String, Set<String>> typesByFile = new LinkedHashMap<>();
            for (CompilationUnitTree cu : parsed) {
                Set<String> declared = declaredTypes(cu);
                typesByFile.put(fileOf(cu), declared);
                for (String qualified : declared) {
                    bySimpleName.computeIfAbsent(simpleName(qualified), k -> new LinkedHashSet<>())
                        .add(qualified);
                }
            }

            for (CompilationUnitTree cu : parsed) {
                String file = fileOf(cu);
                Set<String> declared = typesByFile.getOrDefault(file, Set.of());
                Set<String> references = references(cu, bySimpleName);
                references.removeAll(declared);
                out.put(file, new Entry(size(file), mtime(file), apiHash(cu, positions),
                    declared, references));
            }
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
        return out;
    }

    /** Qualified names of the types a file declares, nested types included. */
    public static Set<String> declaredTypes(CompilationUnitTree cu) {
        String pkg = cu.getPackageName() == null ? "" : cu.getPackageName().toString();
        Set<String> declared = new LinkedHashSet<>();
        new TreeScanner<Void, String>() {
            @Override
            public Void visitClass(ClassTree node, String prefix) {
                String name = node.getSimpleName().toString();
                if (name.isEmpty()) {
                    return super.visitClass(node, prefix);
                }
                String qualified = prefix.isEmpty() ? name : prefix + "." + name;
                declared.add(pkg.isEmpty() ? qualified : pkg + "." + qualified);
                return super.visitClass(node, qualified);
            }
        }.scan(cu, "");
        return declared;
    }

    /** The API hash: declaration text up to each member's body, sorted and hashed. */
    public static String apiHash(CompilationUnitTree cu, SourcePositions positions) {
        String content = content(cu);
        if (content == null) {
            return "unavailable";
        }
        List<String> signatures = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree node, Void p) {
                signatures.add(signature(positions, cu, content, node,
                    classBodyStart(positions, cu, node)));
                return super.visitClass(node, p);
            }

            @Override
            public Void visitMethod(MethodTree node, Void p) {
                if (!node.getModifiers().getFlags().contains(Modifier.PRIVATE)) {
                    long bodyStart = node.getBody() == null ? -1
                        : positions.getStartPosition(cu, node.getBody());
                    signatures.add(signature(positions, cu, content, node, bodyStart));
                }
                return super.visitMethod(node, p);
            }

            @Override
            public Void visitVariable(VariableTree node, Void p) {
                if (!node.getModifiers().getFlags().contains(Modifier.PRIVATE)) {
                    long bodyStart = node.getInitializer() == null ? -1
                        : positions.getStartPosition(cu, node.getInitializer());
                    signatures.add(signature(positions, cu, content, node, bodyStart));
                }
                return super.visitVariable(node, p);
            }
        }.scan(cu, null);
        signatures.sort(String::compareTo);
        return sha1(String.join("\n", signatures));
    }

    private static long classBodyStart(SourcePositions positions, CompilationUnitTree cu, ClassTree node) {
        if (node.getMembers().isEmpty()) {
            return -1;
        }
        long first = positions.getStartPosition(cu, node.getMembers().get(0));
        return first < 0 ? -1 : first;
    }

    private static String signature(SourcePositions positions, CompilationUnitTree cu, String content,
                                    Tree node, long bodyStart) {
        long start = positions.getStartPosition(cu, node);
        long end = bodyStart >= 0 ? bodyStart : positions.getEndPosition(cu, node);
        if (start < 0 || end < start) {
            return node.toString();
        }
        int s = (int) Math.min(start, content.length());
        int e = (int) Math.min(end, content.length());
        return content.substring(s, e).strip();
    }

    /** Project types the file names, resolved through imports and simple-name matching. */
    public static Set<String> references(CompilationUnitTree cu, Map<String, Set<String>> bySimpleName) {
        Set<String> references = new LinkedHashSet<>();
        for (ImportTree imp : cu.getImports()) {
            String qualified = imp.getQualifiedIdentifier().toString();
            if (bySimpleName.containsKey(simpleName(qualified))) {
                references.add(qualified);
            }
        }
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void p) {
                add(node.getName().toString());
                return super.visitIdentifier(node, p);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void p) {
                add(node.getIdentifier().toString());
                return super.visitMemberSelect(node, p);
            }

            private void add(String name) {
                Set<String> qualified = bySimpleName.get(name);
                if (qualified != null) {
                    references.addAll(qualified);
                }
            }
        }.scan(cu, null);
        return references;
    }

    private static String simpleName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }

    private static String fileOf(CompilationUnitTree cu) {
        try {
            return Path.of(cu.getSourceFile().getName()).toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            return cu.getSourceFile().getName();
        }
    }

    private static String content(CompilationUnitTree cu) {
        try {
            return cu.getSourceFile().getCharContent(true).toString();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static long size(String file) {
        try {
            return Files.size(Path.of(file));
        } catch (IOException e) {
            return -1;
        }
    }

    private static long mtime(String file) {
        try {
            return Files.getLastModifiedTime(Path.of(file)).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    static String sha1(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
