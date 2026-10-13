package jcheck.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.cli.MavenCli;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.classworlds.realm.ClassRealm;

/**
 * Runs one Maven goal through the Maven embedder (in process, no {@code mvn} subprocess) and writes
 * its classpath to a file.
 *
 * <p>This source ships with jcheck and is compiled at run time against the resolved
 * {@code maven-embedder} jars. Maven's own classes are loaded from the installation's {@code lib/}
 * in a ClassWorld realm, because Sisu binds Maven's components by scanning those jars; the embedder
 * API itself comes from the compiled-against jars. Arguments: the project directory, the output
 * file, the Maven installation directory, then any extra Maven arguments.
 */
public final class MavenEmbedderRunner {

    private MavenEmbedderRunner() {
    }

    public static void main(String[] args) throws Exception {
        String projectDir = args[0];
        String outputFile = args[1];
        String mavenHome = args[2];
        System.setProperty("maven.multiModuleProjectDirectory", projectDir);
        if (!mavenHome.isBlank()) {
            System.setProperty("maven.home", mavenHome);
        }

        ClassWorld world = new ClassWorld();
        ClassRealm realm = world.newRealm("plexus.core",
            MavenEmbedderRunner.class.getClassLoader());
        if (!mavenHome.isBlank()) {
            Path lib = Path.of(mavenHome, "lib");
            if (Files.isDirectory(lib)) {
                try (var stream = Files.list(lib)) {
                    for (Path jar : stream.filter(p -> p.toString().endsWith(".jar")).toList()) {
                        realm.addURL(jar.toUri().toURL());
                    }
                }
            }
        }

        List<String> cliArgs = new ArrayList<>(List.of(
            "-B",
            "-Dmdep.outputFile=" + outputFile,
            "-Dmdep.includeScope=test"));
        for (int i = 3; i < args.length; i++) {
            cliArgs.add(args[i]);
        }
        cliArgs.add("dependency:build-classpath");

        int rc = new MavenCli(world).doMain(cliArgs.toArray(new String[0]), projectDir,
            System.out, System.err);
        System.exit(rc);
    }
}
