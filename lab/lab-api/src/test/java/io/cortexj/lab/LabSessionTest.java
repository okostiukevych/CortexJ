package io.cortexj.lab;

import io.cortexj.data.ColType;
import io.cortexj.data.Column;
import io.cortexj.data.Frame;
import io.cortexj.data.SimpleColumn;
import io.cortexj.data.SimpleFrame;
import io.cortexj.lab.Displayable;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabSessionTest {

    @Test
    void jshellRoundTrip() {
        try (LabSession session = LabSession.create()) {
            SnippetResult result = session.eval("1 + 2");
            assertFalse(result.hasErrors(), result.errors().toString());
            assertEquals("3", result.value());
        }
    }

    @Test
    void unknownSymbolReportsErrorsInsteadOfThrowing() {
        try (LabSession session = LabSession.create()) {
            SnippetResult result = session.eval("definitelyNotDefined123 + 1");
            assertTrue(result.hasErrors());
            assertFalse(result.errors().isEmpty());
        }
    }

    @Test
    void environmentDescriptorReportsLiveValues() {
        try (LabSession session = LabSession.create()) {
            EnvironmentDescriptor descriptor = session.describeEnvironment();
            assertEquals(System.getProperty("java.version"), descriptor.javaVersion());
            assertFalse(descriptor.osArch().isEmpty());
            assertFalse(descriptor.timestampMillis() == 0);
        }
    }

    @Test
    void frameRendersAsTextTable() {
        Frame frame = new SimpleFrame(
                new io.cortexj.data.Schema(Arrays.asList("id", "stars"),
                        Arrays.asList(ColType.LONG, ColType.LONG)),
                Arrays.<Column<?>>asList(
                        new SimpleColumn<Long>("id", Long.class, Arrays.asList(1L, 2L)),
                        new SimpleColumn<Long>("stars", Long.class, Arrays.asList(5L, 1L))));
        Displayable table = Displayable.table(frame);
        assertEquals("text/plain", table.mimeType());
        assertTrue(table.content().contains("id"), table.content());
        assertTrue(table.content().contains("stars"), table.content());
        assertTrue(table.content().contains("5"), table.content());
    }

    @Test
    void exportProjectSkeletonWritesStarterProject() throws Exception {
        Path target = Files.createTempDirectory("cortexj-lab-export");
        try {
            try (LabSession session = LabSession.create()) {
                session.exportProjectSkeleton(target, "my-lab-project");
            }
            assertTrue(Files.isRegularFile(target.resolve("pom.xml")));
            assertTrue(Files.isRegularFile(target.resolve("README.md")));
            assertTrue(Files.isRegularFile(target.resolve("src/main/java/mylab/Main.java")));
            String pom = Files.readString(target.resolve("pom.xml"));
            assertTrue(pom.contains("my-lab-project"), pom);
            String main = Files.readString(target.resolve("src/main/java/mylab/Main.java"));
            assertTrue(main.contains("package mylab"), main);
        } finally {
            deleteRecursively(target);
        }
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                for (Path child : stream.toList()) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
