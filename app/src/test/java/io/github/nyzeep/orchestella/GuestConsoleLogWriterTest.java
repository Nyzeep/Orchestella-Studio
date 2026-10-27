package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * JVM tests for {@link GuestConsoleLogWriter}: append ordering, parent-dir creation,
 * size-based rotation, and blank-input no-ops. The writer must stay free of Android
 * imports so these tests run on the host JVM.
 */
public class GuestConsoleLogWriterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void appendCreatesFileAndPreservesChunkOrder() throws IOException {
        File logsDir = new File(tmp.getRoot(), "logs");
        GuestConsoleLogWriter.append(logsDir, "inst-1", "boot line 1\n");
        GuestConsoleLogWriter.append(logsDir, "inst-1", "boot line 2\n");
        File log = new File(logsDir, "console-inst-1.log");
        assertTrue(log.isFile());
        assertEquals("boot line 1\nboot line 2\n",
                new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void appendCreatesMissingParentDirectories() throws IOException {
        File logsDir = new File(tmp.getRoot(), "a/b/logs");
        assertFalse(logsDir.exists());
        GuestConsoleLogWriter.append(logsDir, "inst-2", "hello\n");
        assertTrue(new File(logsDir, "console-inst-2.log").isFile());
    }

    @Test
    public void oversizedLogRotatesToFreshFileWithMarker() throws IOException {
        File logsDir = tmp.newFolder("logs");
        File log = new File(logsDir, "console-inst-3.log");
        byte[] bulk = new byte[40];
        java.util.Arrays.fill(bulk, (byte) 'x');
        Files.write(log.toPath(), bulk);
        GuestConsoleLogWriter.append(logsDir, "inst-3", "after-rotation\n", 32L);
        String content = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
        assertEquals(GuestConsoleLogWriter.ROTATION_MARKER + "after-rotation\n", content);
        assertFalse(content.contains("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"));
    }

    @Test
    public void logUnderThresholdIsAppendedWithoutRotation() throws IOException {
        File logsDir = tmp.newFolder("logs");
        GuestConsoleLogWriter.append(logsDir, "inst-4", "keep\n", 1024L);
        GuestConsoleLogWriter.append(logsDir, "inst-4", "more\n", 1024L);
        assertEquals("keep\nmore\n", new String(
                Files.readAllBytes(new File(logsDir, "console-inst-4.log").toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void blankInputsAreNoOps() throws IOException {
        File logsDir = tmp.newFolder("logs");
        GuestConsoleLogWriter.append(logsDir, null, "x\n");
        GuestConsoleLogWriter.append(logsDir, "", "x\n");
        GuestConsoleLogWriter.append(logsDir, "inst-5", null);
        GuestConsoleLogWriter.append(logsDir, "inst-5", "");
        assertEquals(0, logsDir.listFiles().length);
    }
}
