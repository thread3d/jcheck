package jcheck.rewrite;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.config.Environment;
import org.openrewrite.java.JavaParser;

/**
 * Applies OpenRewrite recipes to a list of files, in place.
 *
 * <p>This source ships with jcheck and is compiled at run time against the resolved OpenRewrite
 * jars, so jcheck itself has no OpenRewrite dependency and the runner always matches the version
 * that was resolved. Arguments: the recipe names (comma-separated), the project root, a file
 * listing the sources to rewrite, and optionally the compilation classpath.
 *
 * <p>Only a changed file is written, and each change is printed as
 * {@code CHANGED <path> <recipe>} so the caller can report and revert it.
 */
public final class RewriteRunner {

    private RewriteRunner() {
    }

    public static void main(String[] args) throws Exception {
        List<String> recipes = new ArrayList<>();
        for (String recipe : args[0].split(",")) {
            if (!recipe.isBlank()) {
                recipes.add(recipe.trim());
            }
        }
        Path base = Path.of(args[1]);
        List<Path> paths = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(args[2]))) {
            if (!line.isBlank()) {
                paths.add(Path.of(line));
            }
        }
        if (paths.isEmpty() || recipes.isEmpty()) {
            return;
        }

        ExecutionContext ctx = new InMemoryExecutionContext(t -> t.printStackTrace());
        JavaParser.Builder<?, ?> builder = JavaParser.fromJavaVersion()
            .logCompilationWarningsAndErrors(false);
        if (args.length > 3 && !args[3].isBlank()) {
            builder.classpath(args[3].split(File.pathSeparator));
        }
        JavaParser parser = builder.build();
        List<SourceFile> current = new ArrayList<>(parser.parse(paths, base, ctx).toList());

        Environment environment = Environment.builder().scanRuntimeClasspath().build();
        /*
         * One recipe per pass, rather than one composite: a composite reports itself as
         * "CompositeRecipe", which would lose which recipe made each change. Passing each changed
         * file's new state to the next pass lets the recipes compose in the order given.
         */
        for (String recipeName : recipes) {
            Recipe recipe = environment.activateRecipes(recipeName);
            List<Result> results = recipe
                .run(new org.openrewrite.internal.InMemoryLargeSourceSet(current), ctx)
                .getChangeset().getAllResults();
            for (Result result : results) {
                SourceFile after = result.getAfter();
                if (after == null) {
                    continue;
                }
                String before = result.getBefore() == null ? "" : result.getBefore().printAll();
                String updated = after.printAll();
                if (updated.equals(before)) {
                    continue;
                }
                Path target = Path.of(after.getSourcePath().toString());
                if (!target.isAbsolute()) {
                    target = base.resolve(target);
                }
                Files.writeString(target, updated);
                System.out.println("CHANGED\t" + target + "\t" + recipeName);
                for (int i = 0; i < current.size(); i++) {
                    if (current.get(i).getSourcePath().equals(after.getSourcePath())) {
                        current.set(i, after);
                    }
                }
            }
        }
    }
}
