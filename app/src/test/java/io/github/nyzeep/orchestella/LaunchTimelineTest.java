package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.RuntimeProcessSupervisor.StopResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Pure-JVM contract for the one-line-per-launch launch timeline. */
public final class LaunchTimelineTest {
    private static final String INSTANCE_ID = "instance-7f3a";
    private static final String DISTRO_ID = "ubuntu";
    private static final String BRIDGE_TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String GUEST_PASSWORD = "SyntheticGuestPassword-9x!";

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void recordsOrderedStagesWithElapsedMilliseconds() throws Exception {
        FakeClock clock = new FakeClock(Instant.parse("2026-10-18T08:00:00Z"));
        RecordingSink sink = new RecordingSink();
        LaunchTimeline timeline = new LaunchTimeline(INSTANCE_ID, DISTRO_ID, 2048, 2, clock, sink);

        timeline.mark(LaunchTimeline.Stage.LAUNCH_START);
        clock.advanceMillis(10);
        timeline.mark(LaunchTimeline.Stage.ASSETS_VERIFIED);
        clock.advanceMillis(90);
        timeline.markInitramfsBuilt(1048576L);
        clock.advanceMillis(400);
        timeline.mark(LaunchTimeline.Stage.GATEWAY_READY);
        clock.advanceMillis(250);
        timeline.mark(LaunchTimeline.Stage.QEMU_STARTED);
        clock.advanceMillis(40);
        timeline.mark(LaunchTimeline.Stage.SESSION_REGISTERED);
        clock.advanceMillis(6000);
        timeline.mark(LaunchTimeline.Stage.STOP_REQUESTED);
        clock.advanceMillis(300);
        timeline.markStopConfirmed(StopResult.CONFIRMED);
        assertTrue(timeline.finish());

        assertEquals(1, sink.lines.size());
        String line = sink.lines.get(0);
        assertTrue(line.startsWith("{"));
        assertTrue(line.endsWith("}"));
        assertEquals("{\"stage\":\"LAUNCH_START\",\"elapsedMs\":0}", stageEntry(line, "LAUNCH_START"));
        assertEquals("{\"stage\":\"ASSETS_VERIFIED\",\"elapsedMs\":10}", stageEntry(line, "ASSETS_VERIFIED"));
        assertEquals("{\"stage\":\"INITRAMFS_BUILT\",\"elapsedMs\":100}", stageEntry(line, "INITRAMFS_BUILT"));
        assertEquals("{\"stage\":\"GATEWAY_READY\",\"elapsedMs\":500}", stageEntry(line, "GATEWAY_READY"));
        assertEquals("{\"stage\":\"QEMU_STARTED\",\"elapsedMs\":750}", stageEntry(line, "QEMU_STARTED"));
        assertEquals("{\"stage\":\"SESSION_REGISTERED\",\"elapsedMs\":790}", stageEntry(line, "SESSION_REGISTERED"));
        assertEquals("{\"stage\":\"STOP_REQUESTED\",\"elapsedMs\":6790}", stageEntry(line, "STOP_REQUESTED"));
        assertEquals("{\"stage\":\"STOP_CONFIRMED\",\"elapsedMs\":7090}", stageEntry(line, "STOP_CONFIRMED"));
        assertTrue(line.contains("\"instanceId\":\"" + INSTANCE_ID + "\""));
        assertTrue(line.contains("\"distroId\":\"" + DISTRO_ID + "\""));
        assertTrue(line.contains("\"guestMemoryMb\":2048"));
        assertTrue(line.contains("\"vcpuCount\":2"));
        assertTrue(line.contains("\"rootfsBytes\":1048576"));
        assertTrue(line.contains("\"stopResult\":\"CONFIRMED\""));
        assertEquals(List.of(
                LaunchTimeline.Stage.LAUNCH_START,
                LaunchTimeline.Stage.ASSETS_VERIFIED,
                LaunchTimeline.Stage.INITRAMFS_BUILT,
                LaunchTimeline.Stage.GATEWAY_READY,
                LaunchTimeline.Stage.QEMU_STARTED,
                LaunchTimeline.Stage.SESSION_REGISTERED,
                LaunchTimeline.Stage.STOP_REQUESTED,
                LaunchTimeline.Stage.STOP_CONFIRMED), timeline.stages());
        assertTrue(line.indexOf("LAUNCH_START") < line.indexOf("ASSETS_VERIFIED"));
        assertTrue(line.indexOf("ASSETS_VERIFIED") < line.indexOf("INITRAMFS_BUILT"));
        assertTrue(line.indexOf("INITRAMFS_BUILT") < line.indexOf("GATEWAY_READY"));
        assertTrue(line.indexOf("GATEWAY_READY") < line.indexOf("QEMU_STARTED"));
        assertTrue(line.indexOf("QEMU_STARTED") < line.indexOf("SESSION_REGISTERED"));
        assertTrue(line.indexOf("SESSION_REGISTERED") < line.indexOf("STOP_REQUESTED"));
        assertTrue(line.indexOf("STOP_REQUESTED") < line.indexOf("STOP_CONFIRMED"));
    }

    @Test
    public void duplicateMarksKeepOneStageAndTheFinalStopObservation() {
        FakeClock clock = new FakeClock(Instant.parse("2026-10-18T08:00:00Z"));
        RecordingSink sink = new RecordingSink();
        LaunchTimeline timeline = new LaunchTimeline(INSTANCE_ID, DISTRO_ID, 2048, 2, clock, sink);

        timeline.mark(LaunchTimeline.Stage.LAUNCH_START);
        clock.advanceMillis(25);
        timeline.mark(LaunchTimeline.Stage.QEMU_STARTED);
        clock.advanceMillis(25);
        timeline.mark(LaunchTimeline.Stage.QEMU_STARTED);
        assertEquals(25L, timeline.elapsedMs(LaunchTimeline.Stage.QEMU_STARTED));

        timeline.markStopConfirmed(StopResult.UNCONFIRMED);
        timeline.markStopConfirmed(StopResult.CONFIRMED);
        assertEquals(3, timeline.stages().size());
        assertTrue(timeline.finish());
        assertTrue(sink.lines.get(0).contains("\"stopResult\":\"CONFIRMED\""));
        assertEquals(1, countOccurrences(sink.lines.get(0), "\"stage\":\"QEMU_STARTED\""));
        assertEquals(1, countOccurrences(sink.lines.get(0), "\"stage\":\"STOP_CONFIRMED\""));
    }

    @Test
    public void markBeforeLaunchStartIsDroppedAndDiagnosedInsteadOfFailing() throws Exception {
        FakeClock clock = new FakeClock(Instant.parse("2026-10-18T08:00:00Z"));
        RecordingSink sink = new RecordingSink();
        LaunchTimeline timeline = new LaunchTimeline(INSTANCE_ID, DISTRO_ID, 2048, 2, clock, sink);

        timeline.mark(LaunchTimeline.Stage.GATEWAY_READY);
        assertFalse(timeline.stages().contains(LaunchTimeline.Stage.GATEWAY_READY));
        assertEquals(-1L, timeline.elapsedMs(LaunchTimeline.Stage.GATEWAY_READY));
        assertFalse(timeline.diagnostics().isEmpty());
        assertTrue(String.join("\n", timeline.diagnostics()).contains("before-launch-start"));

        timeline.mark(LaunchTimeline.Stage.LAUNCH_START);
        clock.advanceMillis(5);
        timeline.mark(LaunchTimeline.Stage.GATEWAY_READY);
        assertTrue(timeline.finish());
        assertEquals(1, sink.lines.size());
        assertFalse(sink.lines.get(0).contains("\"elapsedMs\":-1"));
        assertEquals("{\"stage\":\"GATEWAY_READY\",\"elapsedMs\":5}", stageEntry(sink.lines.get(0), "GATEWAY_READY"));
    }

    @Test
    public void missingStagesRenderSafely() {
        FakeClock clock = new FakeClock(Instant.parse("2026-10-18T08:00:00Z"));
        RecordingSink sink = new RecordingSink();
        LaunchTimeline timeline = new LaunchTimeline(INSTANCE_ID, DISTRO_ID, 1024, 1, clock, sink);

        timeline.mark(LaunchTimeline.Stage.LAUNCH_START);
        clock.advanceMillis(12);
        timeline.mark(LaunchTimeline.Stage.STOP_REQUESTED);
        assertTrue(timeline.finish());

        String line = sink.lines.get(0);
        assertTrue(line.contains("\"rootfsBytes\":null"));
        assertTrue(line.contains("\"stopResult\":null"));
        assertEquals(2, timeline.stages().size());
        assertNull(timeline.stopResult());
    }

    @Test
    public void writeFailureIsSwallowedDiagnosedAndNeverRetried() {
        FakeClock clock = new FakeClock(Instant.parse("2026-10-18T08:00:00Z"));
        AtomicInteger attempts = new AtomicInteger();
        LaunchTimeline timeline = new LaunchTimeline(INSTANCE_ID, DISTRO_ID, 2048, 2, clock, line -> {
            attempts.incrementAndGet();
            throw new IOException("synthetic-disk-full");
        });

        timeline.mark(LaunchTimeline.Stage.LAUNCH_START);
        assertFalse(timeline.finish());
        assertFalse(timeline.finish());
        assertEquals(1, attempts.get());
        assertFalse(timeline.diagnostics().isEmpty());
        assertTrue(String.join("\n", timeline.diagnostics()).contains("synthetic-disk-full"));
    }

    @Test
    public void recordNeverContainsSecretsOrTheQemuCommandLine() {
        FakeClock clock = new FakeClock(Instant.parse("2026-10-18T08:00:00Z"));
        RecordingSink sink = new RecordingSink();
        LaunchTimeline timeline = new LaunchTimeline(INSTANCE_ID, DISTRO_ID, 2048, 2, clock, sink);

        timeline.mark(LaunchTimeline.Stage.LAUNCH_START);
        timeline.markInitramfsBuilt(4096L);
        timeline.markStopConfirmed(StopResult.ALREADY_STOPPED);
        assertTrue(timeline.finish());

        String line = sink.lines.get(0);
        assertFalse(line.contains(BRIDGE_TOKEN));
        assertFalse(line.contains(GUEST_PASSWORD));
        assertFalse(line.toLowerCase(java.util.Locale.ROOT).contains("password"));
        assertFalse(line.toLowerCase(java.util.Locale.ROOT).contains("token"));
        assertFalse(line.contains("qemu-system"));
        assertFalse(line.contains("-netdev"));
    }

    @Test
    public void recordSchemaIsAnAllowlistWithNoCredentialFields() {
        FakeClock clock = new FakeClock(Instant.parse("2026-10-18T08:00:00Z"));
        RecordingSink sink = new RecordingSink();
        LaunchTimeline timeline = new LaunchTimeline(INSTANCE_ID, DISTRO_ID, 2048, 2, clock, sink);

        timeline.mark(LaunchTimeline.Stage.LAUNCH_START);
        timeline.markInitramfsBuilt(4096L);
        timeline.markStopConfirmed(StopResult.CONFIRMED);
        String line = timeline.toJsonLine();

        assertEquals(Set.of("schema", "instanceId", "distroId", "guestMemoryMb", "vcpuCount",
                "stages", "rootfsBytes", "stopResult", "diagnostics"), topLevelKeys(line));
    }

    private static Set<String> topLevelKeys(String line) {
        Set<String> keys = new HashSet<>();
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                    if (depth == 1 && i + 1 < line.length() && line.charAt(i + 1) == ':') {
                        int start = line.lastIndexOf('"', i - 1);
                        if (start >= 0) keys.add(line.substring(start + 1, i));
                    }
                }
            } else if (character == '"') {
                inString = true;
            } else if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
            }
        }
        return keys;
    }

    @Test
    public void appendSinkWritesExactlyOneJsonLinePerLaunch() throws Exception {
        Path runtimeDirectory = temporaryFolder.newFolder("runtime").toPath();
        FakeClock clock = new FakeClock(Instant.parse("2026-10-18T08:00:00Z"));

        LaunchTimeline first = LaunchTimeline.open(runtimeDirectory, INSTANCE_ID, DISTRO_ID, 2048, 2);
        first.mark(LaunchTimeline.Stage.LAUNCH_START);
        first.markStopConfirmed(StopResult.CONFIRMED);
        assertTrue(first.finish());
        assertFalse(first.finish());

        LaunchTimeline second = LaunchTimeline.open(runtimeDirectory, "instance-8b1c", "debian", 1024, 1);
        second.mark(LaunchTimeline.Stage.LAUNCH_START);
        second.markStopConfirmed(StopResult.ALREADY_STOPPED);
        assertTrue(second.finish());

        List<String> lines = Files.readAllLines(
                runtimeDirectory.resolve(LaunchTimeline.FILE_NAME), StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains(INSTANCE_ID));
        assertTrue(lines.get(1).contains("instance-8b1c"));
        assertTrue(lines.get(0).startsWith("{"));
        assertTrue(lines.get(1).startsWith("{"));
    }

    private static String stageEntry(String line, String stage) {
        int start = line.indexOf("{\"stage\":\"" + stage + "\"");
        assertTrue("stage missing: " + stage, start >= 0);
        int end = line.indexOf('}', start);
        return line.substring(start, end + 1);
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static final class RecordingSink implements LaunchTimeline.LineSink {
        private final List<String> lines = new ArrayList<>();

        @Override public void writeLine(String line) {
            lines.add(line);
        }
    }

    private static final class FakeClock extends Clock {
        private Instant now;

        FakeClock(Instant start) {
            this.now = start;
        }

        void advanceMillis(long millis) {
            now = now.plusMillis(millis);
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }
}
