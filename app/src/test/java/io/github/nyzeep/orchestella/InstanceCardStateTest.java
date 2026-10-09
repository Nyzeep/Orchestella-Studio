package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Pure JVM unit test suite verifying InstanceCard presentation state transitions (Ticket 52 / AC-6).
 */
public final class InstanceCardStateTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC);

    private Path tempDir;
    private InstanceLifecycle.FileStore store;
    private InstanceLifecycle.Controller controller;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("instance-card-state-test-");
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
    public void runningStateYieldsBreathingBadgeAndDisabledDeletion() throws Exception {
        Path rootVol = tempDir.resolve("alpine.raw");
        Files.createFile(rootVol);

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.20"),
                rootVol);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        instance = controller.start(instance.id());

        InstanceCardUiModel model = InstanceCardUiModel.Companion.from(instance, "pKVM 硬件虚拟化", true);
        assertTrue(model.isBreathing());
        assertFalse(model.getCanDelete());
        assertFalse(model.getCanConfigureHardware());
        assertTrue(model.getCanOpenTerminal());
    }

    @Test
    public void stoppedStateYieldsStaticBadgeAndEnabledDeletion() throws Exception {
        Path rootVol = tempDir.resolve("debian.raw");
        Files.createFile(rootVol);

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("debian", "12"),
                rootVol);
        controller.beginProvisioning(instance.id());
        instance = controller.completeProvisioning(instance.id());

        InstanceCardUiModel model = InstanceCardUiModel.Companion.from(instance, "QEMU 软模拟", false);
        assertFalse(model.isBreathing());
        assertTrue(model.getCanDelete());
        assertTrue(model.getCanConfigureHardware());
        assertTrue(model.getCanLaunch());
    }
}
