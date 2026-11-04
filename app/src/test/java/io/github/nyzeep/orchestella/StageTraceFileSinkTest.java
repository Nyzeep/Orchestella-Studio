package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Zero-device contract for the versioned bounded JSONL trace sink (ticket 27):
 * header versioning, explicit refusal of foreign/stale files, rotation and
 * retention bounds.
 */
public final class StageTraceFileSinkTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static final String HEADER = "{\"schema_version\":1,"
            + "\"schema\":\"orchestella_stage_trace\",\"kind\":\"file_header\"}";

    @Test
    public void freshFileStartsWithTheVersionedHeaderLine() throws Exception {
        Path directory = temporaryFolder.newFolder("traces").toPath();
        StageTraceFileSink sink = StageTraceFileSink.defaultV1(directory);
        assertTrue(sink.writeLine("{\"kind\":\"stage_event\"}"));
        List<String> lines = Files.readAllLines(directory.resolve(StageTraceFileSink.FILE_NAME),
                StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertEquals(HEADER, lines.get(0));
        assertEquals("{\"kind\":\"stage_event\"}", lines.get(1));
    }

    @Test
    public void compatibleFileIsAppendedWithoutRewritingTheHeader() throws Exception {
        Path directory = temporaryFolder.newFolder("traces").toPath();
        Path file = directory.resolve(StageTraceFileSink.FILE_NAME);
        Files.write(file, (HEADER + "\n").getBytes(StandardCharsets.UTF_8));
        StageTraceFileSink sink = StageTraceFileSink.defaultV1(directory);
        assertTrue(sink.writeLine("first"));
        assertTrue(sink.writeLine("second"));
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        assertEquals(HEADER, lines.get(0));
        assertEquals("first", lines.get(1));
        assertEquals("second", lines.get(2));
    }

    @Test
    public void foreignSyntheticFileIsExplicitlyRefusedAndPreserved() throws Exception {
        Path directory = temporaryFolder.newFolder("traces").toPath();
        Path file = directory.resolve(StageTraceFileSink.FILE_NAME);
        // A ticket 22 offline synthetic trace: a different schema that must
        // never be silently reinterpreted as production output.
        String foreign = "{\"schema_version\":1,\"kind\":\"offline_stage_trace\","
                + "\"provenance\":\"synthetic_seam\"}";
        byte[] foreignBytes = (foreign + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(file, foreignBytes);

        StageTraceFileSink sink = StageTraceFileSink.defaultV1(directory);
        assertFalse("a foreign file must be refused, not reinterpreted", sink.writeLine("x"));
        assertTrue(sink.refusedForeignFile());
        assertFalse("the refusal is permanent for this sink instance", sink.writeLine("y"));
        assertTrue("the foreign file stays byte-identical",
                java.util.Arrays.equals(foreignBytes, Files.readAllBytes(file)));
    }

    @Test
    public void staleSchemaVersionIsRefusedWithoutTouchingTheFile() throws Exception {
        Path directory = temporaryFolder.newFolder("traces").toPath();
        Path file = directory.resolve(StageTraceFileSink.FILE_NAME);
        String stale = "{\"schema_version\":2,\"schema\":\"orchestella_stage_trace\","
                + "\"kind\":\"file_header\"}";
        byte[] staleBytes = (stale + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(file, staleBytes);
        StageTraceFileSink sink = StageTraceFileSink.defaultV1(directory);
        assertFalse(sink.writeLine("x"));
        assertTrue(java.util.Arrays.equals(staleBytes, Files.readAllBytes(file)));
    }

    @Test
    public void rotationKeepsExactlyTheDeclaredArchiveCount() throws Exception {
        Path directory = temporaryFolder.newFolder("traces").toPath();
        // One archived file, tiny active cap: the active file rotates once it
        // would exceed the cap, and the previous archive is replaced.
        StageTraceFileSink sink = new StageTraceFileSink(directory, "stage-trace-v1.jsonl",
                110L, 1, ProductionStageRecorder.SCHEMA_NAME, 1);
        assertTrue(sink.writeLine("first-event"));
        assertTrue(sink.writeLine("second-event"));
        assertTrue(sink.writeLine("third-event"));

        List<String> active = Files.readAllLines(
                directory.resolve(StageTraceFileSink.FILE_NAME), StandardCharsets.UTF_8);
        assertEquals("the active file restarts with a fresh header",
                List.of(HEADER, "third-event"), active);
        List<String> archive = Files.readAllLines(
                directory.resolve(StageTraceFileSink.FILE_NAME + ".1"), StandardCharsets.UTF_8);
        assertEquals("the archive holds the previous generation",
                List.of(HEADER, "first-event", "second-event"), archive);
        assertFalse("no unbounded archive growth",
                Files.exists(directory.resolve(StageTraceFileSink.FILE_NAME + ".2")));
    }

    @Test
    public void unusableTargetReturnsFalseInsteadOfThrowing() throws Exception {
        // A regular file where the trace directory should be makes every
        // filesystem operation fail; the sink reports false and never throws.
        Path blocker = temporaryFolder.newFile("blocker").toPath();
        StageTraceFileSink sink = StageTraceFileSink.defaultV1(blocker);
        assertFalse(sink.writeLine("x"));
        assertFalse(sink.writeLine("y"));
    }

    @Test
    public void nullLinesAreRejectedWithoutTouchingTheFilesystem() throws Exception {
        Path directory = temporaryFolder.newFolder("traces").toPath();
        StageTraceFileSink sink = StageTraceFileSink.defaultV1(directory);
        assertFalse(sink.writeLine(null));
        assertFalse(Files.exists(directory.resolve(StageTraceFileSink.FILE_NAME)));
    }
}
