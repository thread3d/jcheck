package jcheck.build;

import java.io.File;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.UnsupportedMethodException;
import org.gradle.tooling.model.eclipse.EclipseExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.tooling.model.eclipse.EclipseSourceDirectory;

/**
 * Prints a Gradle project's model through the Tooling API: each project, its source directories and
 * its external classpath, one tab-separated record per line.
 *
 * <p>This source ships with jcheck and is compiled at run time against the jars in the Gradle
 * installation's {@code lib/}, so jcheck has no Gradle dependency of its own and the model always
 * comes from the Gradle version that will actually build the project. Arguments: the project
 * directory and the Gradle installation directory.
 */
public final class ToolingModelRunner {

    private ToolingModelRunner() {
    }

    public static void main(String[] args) throws Exception {
        File projectDir = new File(args[0]);
        GradleConnector connector = GradleConnector.newConnector().forProjectDirectory(projectDir);
        if (args.length > 1 && !args[1].isBlank()) {
            File installation = new File(args[1]);
            if (installation.isDirectory()) {
                connector.useInstallation(installation);
            }
        }
        ProjectConnection connection = connector.connect();
        try {
            print(connection.getModel(EclipseProject.class));
        } finally {
            connection.close();
        }
    }

    private static void print(EclipseProject project) {
        System.out.println("PROJECT\t" + project.getGradleProject().getPath() + "\t"
            + projectDirectory(project));
        for (EclipseSourceDirectory dir : project.getSourceDirectories()) {
            if (dir.getDirectory() != null) {
                System.out.println("SOURCE\t" + dir.getDirectory());
            }
        }
        for (EclipseExternalDependency dependency : project.getClasspath()) {
            if (dependency.getFile() != null) {
                System.out.println("CLASSPATH\t" + dependency.getFile());
            }
        }
        for (EclipseProject child : project.getChildren()) {
            print(child);
        }
    }

    private static File projectDirectory(EclipseProject project) {
        try {
            return project.getProjectDirectory();
        } catch (UnsupportedMethodException e) {
            return null;
        }
    }
}
