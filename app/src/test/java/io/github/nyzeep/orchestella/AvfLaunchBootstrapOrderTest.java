package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.engineavf.AvfEngineException;
import io.github.nyzeep.orchestella.runtime.engineavf.AvfHostFacts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Ticket 03: admission runs before the shared rootfs/initramfs bootstrap, so a
 * rejected hardware configuration never pays the assembly cost and a fallback
 * strategy cannot be handed a second bootstrap after AVF already ran one.
 */
public final class AvfLaunchBootstrapOrderTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void rejectedDefaultConfigDoesNotBootstrap() throws Exception {
        AtomicInteger bootstraps = new AtomicInteger();
        try {
            AvfInstanceLauncher.prepare(parameters(VmHardwareConfig.defaultConfig()),
                    AvfHostFacts.supported(), () -> {
                        bootstraps.incrementAndGet();
                        return runtimePaths();
                    });
            fail("the default 2-vCPU config must be rejected");
        } catch (AvfEngineException expected) {
            assertEquals("the rejection is typed before bootstrap",
                    AvfEngineException.HARDWARE_UNSUPPORTED, expected.reason());
        }
        assertEquals("a rejected config must not bootstrap the shared assets",
                0, bootstraps.get());
    }

    @Test
    public void knownUnsupportedHostFactsRejectBeforeBootstrap() throws Exception {
        AtomicInteger bootstraps = new AtomicInteger();
        try {
            AvfInstanceLauncher.prepare(parameters(oneCpu()), AvfHostFacts.unsupported(), () -> {
                bootstraps.incrementAndGet();
                return runtimePaths();
            });
            fail("a host without non-protected VMs must be rejected");
        } catch (AvfEngineException expected) {
            assertEquals("the host fact rejection is typed",
                    AvfEngineException.HARDWARE_UNSUPPORTED, expected.reason());
        }
        assertEquals(0, bootstraps.get());
    }

    @Test
    public void admittedConfigBootstrapsExactlyOnce() throws Exception {
        AtomicInteger bootstraps = new AtomicInteger();
        AvfInstanceLauncher.PreparedSettings prepared = AvfInstanceLauncher.prepare(
                parameters(oneCpu()), AvfHostFacts.unknown(), () -> {
                    bootstraps.incrementAndGet();
                    return runtimePaths();
                });
        assertEquals("the admitted config bootstraps once", 1, bootstraps.get());
        assertEquals("the plan keeps one vCPU", 1, prepared.plan.cpuCount());
        assertEquals("the plan keeps the configured memory", 1024L, prepared.plan.memoryMb());
    }

    private InstanceStrategyLauncher.LaunchParameters parameters(VmHardwareConfig config) throws java.io.IOException {
        Path dir = temporaryFolder.newFolder().toPath();
        return new InstanceStrategyLauncher.LaunchParameters(
                "admission-" + System.nanoTime(),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "alpine-rootfs.tar",
                dir.resolve("root.raw").toString(),
                dir.toString(),
                dir.toString(),
                null,
                config);
    }

    private QemuRuntimeBootstrapper.RuntimePaths runtimePaths() throws java.io.IOException {
        Path dir = temporaryFolder.newFolder().toPath();
        Path kernel = Files.createFile(dir.resolve("Image"));
        Path initramfs = Files.createFile(dir.resolve("initramfs.cpio.gz"));
        Path root = Files.createFile(dir.resolve("root.raw"));
        return new QemuRuntimeBootstrapper.RuntimePaths(kernel, initramfs, root);
    }

    private static VmHardwareConfig oneCpu() {
        return VmHardwareConfig.builder().cpuCores(1).ramMegabytes(1024).build();
    }
}
