package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Pure JVM unit test suite verifying MainActivity UI state and safety validations (Ticket 52 / AC-6).
 */
public final class MainActivityUiStateTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC);

    private Path tempDir;
    private InstanceLifecycle.FileStore store;
    private InstanceLifecycle.Controller controller;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("main-activity-ui-state-test-");
        store = new InstanceLifecycle.FileStore(tempDir);
        controller = new InstanceLifecycle.Controller(store, FIXED_CLOCK);
    }

    @After
    public void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (var s = Files.walk(tempDir)) {
                s.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {}
                });
            }
        }
    }

    @Test
    public void instanceListMapsCorrectlyToCardUiModels() throws Exception {
        Path disk1 = tempDir.resolve("alpine.raw");
        Files.createFile(disk1);
        InstanceLifecycle.Instance inst1 = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.20"),
                disk1);

        Path disk2 = tempDir.resolve("debian.raw");
        Files.createFile(disk2);
        InstanceLifecycle.Instance inst2 = controller.create(
                new InstanceLifecycle.DistroReference("debian", "12"),
                disk2);

        List<InstanceLifecycle.Instance> all = store.list();
        assertEquals(2, all.size());

        InstanceCardUiModel card1 = InstanceCardUiModel.Companion.from(inst1, "QEMU 软模拟", false);
        InstanceCardUiModel card2 = InstanceCardUiModel.Companion.from(inst2, "pKVM 硬件虚拟化", true);

        assertEquals("Alpine", card1.getDistroDisplayName());
        assertEquals("Debian", card2.getDistroDisplayName());
    }

    @Test
    public void hostHardwareProfileSafetyZonesBoundHardwareSliders() {
        HostHardwareProfile hostProfile = new HostHardwareProfile(
                8,
                16L * 1024L * 1024L * 1024L,
                10L * 1024L * 1024L * 1024L,
                512L * 1024L * 1024L,
                false
        );

        assertEquals(8, hostProfile.totalCores());
        assertEquals(7, hostProfile.recommendedCpuCores());
        assertEquals(HostHardwareProfile.SafetyZone.GREEN, hostProfile.evaluateCpuZone(4));
        assertEquals(HostHardwareProfile.SafetyZone.GREEN, hostProfile.evaluateRamZone(4096));
    }
}
