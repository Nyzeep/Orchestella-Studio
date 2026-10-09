package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.engineavf.AvfEngineException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The degradation ledger: append-only, reason-bearing, never a .log file. */
public final class AvfDegradationLogTest {
    @org.junit.Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void recordsFallbackReasonsAsAppendOnlyLines() throws IOException {
        Path file = temporaryFolder.getRoot().toPath().resolve("avf-degradations.txt");
        AvfDegradationLog log = new AvfDegradationLog(file);

        AvfDegradationLog.Record first = log.record(new AvfEngineException(
                AvfEngineException.HARDWARE_UNSUPPORTED, "hardware refused non-protected VM"));
        AvfDegradationLog.Record second = log.record(new AvfEngineException(
                AvfEngineException.REFLECTION_UNAVAILABLE, "AVF SDK classes are not resolvable"));

        assertEquals(AvfEngineException.HARDWARE_UNSUPPORTED, first.reason());
        assertEquals(AvfEngineException.REFLECTION_UNAVAILABLE, second.reason());
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).endsWith("|" + AvfEngineException.HARDWARE_UNSUPPORTED
                + "|HARDWARE_UNSUPPORTED: hardware refused non-protected VM"));
        assertTrue(lines.get(1).contains(AvfEngineException.REFLECTION_UNAVAILABLE));
    }

    @Test
    public void evidenceDisciplineKeepsTheLedgerOutOfLogExtension() throws IOException {
        Path file = temporaryFolder.getRoot().toPath().resolve("avf-degradations.txt");
        new AvfDegradationLog(file).record(new AvfEngineException(
                AvfEngineException.VM_CREATE_FAILED, "creation failed"));

        assertTrue(file.endsWith("avf-degradations.txt"));
        assertTrue(!file.toString().endsWith(".log"));
        assertTrue(Files.isRegularFile(file));
    }
}
