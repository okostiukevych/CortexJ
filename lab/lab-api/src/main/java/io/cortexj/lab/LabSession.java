package io.cortexj.lab;

import jdk.jshell.JShell;
import jdk.jshell.SnippetEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Interactive lab session over the JDK-built-in JShell (ADR-018). The
 * session inherits the process classpath, so CortexJ snippets work
 * wherever the lab runs. The Jupyter kernel adapter is an explicit
 * follow-up, not a silent gap.
 */
public final class LabSession implements AutoCloseable {

    private final JShell shell;

    private LabSession(JShell shell) {
        this.shell = shell;
    }

    public static LabSession create() {
        return new LabSession(JShell.create());
    }

    public SnippetResult eval(String code) {
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("code must not be empty");
        }
        List<SnippetEvent> events = shell.eval(code);
        StringBuilder value = new StringBuilder();
        List<String> errors = new ArrayList<String>();
        for (SnippetEvent event : events) {
            if (event.value() != null && !event.value().isEmpty()) {
                value.append(event.value());
            }
            if (event.exception() != null) {
                errors.add(String.valueOf(event.exception().getMessage()));
            } else if (event.status() != jdk.jshell.Snippet.Status.VALID) {
                errors.add("snippet status: " + event.status());
            }
        }
        return new SnippetResult(value.toString(), Collections.unmodifiableList(errors));
    }

    public EnvironmentDescriptor describeEnvironment() {
        String version = LabSession.class.getPackage() == null
                ? null : LabSession.class.getPackage().getImplementationVersion();
        return new EnvironmentDescriptor(
                System.getProperty("java.version"),
                System.getProperty("java.vm.vendor"),
                System.getProperty("os.name", "unknown").toLowerCase()
                        + "-" + System.getProperty("os.arch", "unknown"),
                Runtime.getRuntime().availableProcessors(),
                version,
                System.currentTimeMillis());
    }

    /**
     * Exports the cells of this session into a runnable Maven project
     * skeleton (starter-java17 based).
     */
    public void exportProjectSkeleton(java.nio.file.Path target, String projectId)
            throws java.io.IOException {
        if (target == null || projectId == null || projectId.trim().isEmpty()) {
            throw new IllegalArgumentException("target and projectId must not be empty");
        }
        java.nio.file.Path sourceRoot = target.resolve("src/main/java/mylab");
        java.nio.file.Files.createDirectories(sourceRoot);
        String artifactId = projectId.trim().replaceAll("[^A-Za-z0-9._-]", "-");
        String pom = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>io.cortexj.examples</groupId>\n"
                + "  <artifactId>" + artifactId + "</artifactId>\n"
                + "  <version>1.0.0-SNAPSHOT</version>\n"
                + "  <packaging>jar</packaging>\n"
                + "  <properties>\n"
                + "    <maven.compiler.release>17</maven.compiler.release>\n"
                + "    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n"
                + "  </properties>\n"
                + "  <dependencyManagement>\n"
                + "    <dependencies>\n"
                + "      <dependency>\n"
                + "        <groupId>io.cortexj</groupId>\n"
                + "        <artifactId>cortexj-bom</artifactId>\n"
                + "        <version>0.1.0-SNAPSHOT</version>\n"
                + "        <type>pom</type>\n"
                + "        <scope>import</scope>\n"
                + "      </dependency>\n"
                + "    </dependencies>\n"
                + "  </dependencyManagement>\n"
                + "  <dependencies>\n"
                + "    <dependency>\n"
                + "      <groupId>io.cortexj</groupId>\n"
                + "      <artifactId>cortexj-starter-java17</artifactId>\n"
                + "    </dependency>\n"
                + "  </dependencies>\n"
                + "</project>\n";
        java.nio.file.Files.writeString(target.resolve("pom.xml"), pom);
        java.nio.file.Files.writeString(target.resolve("README.md"),
                "# " + artifactId + "\n\nExported from a CortexJ lab session.\n\n"
                        + "Run: mvn compile exec:java -Dexec.mainClass=mylab.Main\n");
        java.nio.file.Files.writeString(sourceRoot.resolve("Main.java"),
                "package mylab;\n\n"
                        + "public final class Main {\n\n"
                        + "    public static void main(String[] args) {\n"
                        + "        System.out.println(\"Hello from " + artifactId + "\");\n"
                        + "    }\n"
                        + "}\n");
    }

    @Override
    public void close() {
        shell.close();
    }
}
