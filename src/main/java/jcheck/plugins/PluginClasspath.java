package jcheck.plugins;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Works out the classpath a javac plugin needs, either from an explicit list or by resolving
 * coordinates with {@link MavenResolver}.
 *
 * <p>An explicit list wins, because a project that already pins its analyzer jars should not have
 * jcheck download a second copy. Otherwise the pinned defaults are resolved; the versions live in
 * {@code jcheck.toml} so a project can move them without a jcheck release.
 */
public final class PluginClasspath {

    /** Maven Central, the only repository jcheck reaches out to by default. */
    public static final String CENTRAL = "https://repo1.maven.org/maven2/";

    /** Default Error Prone version, verified against JDK 20 (see README's environment note). */
    public static final String DEFAULT_ERROR_PRONE = "2.24.1";
    /** Default NullAway version; depends on dataflow-nullaway, resolved transitively. */
    public static final String DEFAULT_NULLAWAY = "0.10.18";
    /** JSpecify annotations, needed on both the plugin path and the checked code's classpath. */
    public static final String JSPECIFY = "org.jspecify:jspecify:1.0.0";

    public static final class Result {
        /** Jars for javac's {@code -processorpath}, which is where {@code -Xplugin} loads from. */
        public final List<Path> processorPath;
        /** Jars to add to the checked code's classpath (annotation-only artifacts). */
        public final List<Path> compileExtra;
        public final List<String> notes;

        Result(List<Path> processorPath, List<Path> compileExtra, List<String> notes) {
            this.processorPath = List.copyOf(processorPath);
            this.compileExtra = List.copyOf(compileExtra);
            this.notes = List.copyOf(notes);
        }
    }

    private PluginClasspath() {
    }

    public static Result resolve(String errorProneVersion, String nullawayVersion, boolean nullaway,
                                 List<String> explicitJars, Path root, boolean offline, String repoUrl) {
        if (explicitJars != null && !explicitJars.isEmpty()) {
            List<Path> jars = new ArrayList<>();
            for (String s : explicitJars) {
                Path p = Path.of(s);
                jars.add(p.isAbsolute() ? p : root.resolve(p));
            }
            return new Result(jars, List.of(),
                List.of("using the plugin classpath configured in jcheck.toml"));
        }

        List<String> coordinates = new ArrayList<>();
        coordinates.add("com.google.errorprone:error_prone_core:" + errorProneVersion);
        if (nullaway) {
            coordinates.add("com.uber.nullaway:nullaway:" + nullawayVersion);
            coordinates.add(JSPECIFY);
        }

        URI repo = repoUrl == null || repoUrl.isBlank() ? URI.create(CENTRAL) : URI.create(repoUrl);
        MavenResolver resolver = new MavenResolver(
            MavenResolver.defaultCacheDir().resolve("maven"), List.of(repo), offline);
        List<Path> jars = resolver.resolve(coordinates);

        List<Path> compileExtra = new ArrayList<>();
        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            if (name.startsWith("jspecify-")) {
                compileExtra.add(jar);
            }
        }
        List<String> notes = new ArrayList<>(resolver.notes());
        return new Result(jars, compileExtra, notes);
    }
}
