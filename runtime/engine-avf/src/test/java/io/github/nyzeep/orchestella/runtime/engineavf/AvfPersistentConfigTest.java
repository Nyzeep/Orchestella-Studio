package io.github.nyzeep.orchestella.runtime.engineavf;

import android.system.virtualmachine.FakeVirtualizationContext;
import android.system.virtualmachine.VirtualMachine;
import android.system.virtualmachine.VirtualMachineConfig;
import android.system.virtualmachine.VirtualMachineCustomImageConfig;
import android.system.virtualmachine.VirtualMachineManager;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ticket 03 zero-device acceptance for persistent VM configuration: a changed
 * memory size rebuilds the VM through delete/create, an identity-incompatible
 * stored config is also rebuilt, a running VM refuses the rebuild, and the
 * user's raw root volume is never deleted by the adapter.
 */
public final class AvfPersistentConfigTest {
    public static void main(String[] arguments) throws Exception {
        memoryChangeRebuildsAndKeepsRootRaw();
        identityIncompatibleStoredConfigRebuilds();
        runningVmRefusesRebuild();
        System.out.println("AVF_PERSISTENT_CONFIG_TEST_OK memory_rebuild=1 identity_rebuild=1"
                + " running_refusal=1 root_raw_preserved=1");
    }

    private static void memoryChangeRebuildsAndKeepsRootRaw() throws Exception {
        Path root = Files.createTempFile("avf-root", ".raw");
        Files.write(root, new byte[] {1, 2, 3});
        try {
            VirtualMachineManager manager = new VirtualMachineManager(
                    (name, config) -> VirtualMachine.over(null));
            ReflectiveAvfVirtualMachineApi api = api(manager);

            AvfVirtualMachineEngine.AvfVmSession first =
                    new AvfVirtualMachineEngine(api).launch(spec("rebuild-memory", root, 512));
            assertEquals(AvfStopResult.CONFIRMED, first.stopRuntime(500L), "first VM stops");

            AvfVirtualMachineEngine.AvfVmSession second =
                    new AvfVirtualMachineEngine(api).launch(spec("rebuild-memory", root, 768));
            assertEquals(1, manager.deleteCallsForTest(),
                    "a memory change must rebuild through delete/create");
            assertEquals(2, manager.createCallsForTest(), "the VM is created again");
            assertEquals(768L * 1024L * 1024L,
                    lastConfig(manager).getMemoryBytes(), "the new memory is stored");
            assertTrue(Files.exists(root), "the root raw volume must never be deleted");
            second.stopRuntime(500L);
        } finally {
            Files.deleteIfExists(root);
        }
    }

    private static void identityIncompatibleStoredConfigRebuilds() throws Exception {
        Path root = Files.createTempFile("avf-identity-root", ".raw");
        Files.write(root, new byte[] {4, 5, 6});
        try {
            VirtualMachineManager manager = new VirtualMachineManager(
                    (name, config) -> VirtualMachine.over(null));
            VirtualMachine stored = VirtualMachine.over(null);
            stored.recordConfigForTest(protectedConfig());
            manager.persistForTest("rebuild-identity", stored);

            AvfVirtualMachineEngine.AvfVmSession session =
                    new AvfVirtualMachineEngine(api(manager)).launch(spec("rebuild-identity", root, 512));
            assertEquals(1, manager.deleteCallsForTest(),
                    "an identity-incompatible config is rebuilt, not reused");
            assertEquals(1, manager.createCallsForTest(), "a replacement VM is created");
            assertTrue(Files.exists(root), "identity rebuild keeps the root raw volume");
            session.stopRuntime(500L);
        } finally {
            Files.deleteIfExists(root);
        }
    }

    private static void runningVmRefusesRebuild() throws Exception {
        Path root = Files.createTempFile("avf-running-root", ".raw");
        Files.write(root, new byte[] {7, 8, 9});
        try {
            VirtualMachineManager manager = new VirtualMachineManager(
                    (name, config) -> VirtualMachine.over(null));
            ReflectiveAvfVirtualMachineApi api = api(manager);
            AvfVirtualMachineEngine.AvfVmSession running =
                    new AvfVirtualMachineEngine(api).launch(spec("rebuild-running", root, 512));
            try {
                new AvfVirtualMachineEngine(api).launch(spec("rebuild-running", root, 768));
                fail("a running VM must refuse the rebuild");
            } catch (AvfEngineException expected) {
                assertEquals(AvfEngineException.VM_STOP_UNCONFIRMED, expected.reason(),
                        "the refusal is typed as an unconfirmed stop");
            }
            assertEquals(0, manager.deleteCallsForTest(),
                    "no delete may run while the VM is not confirmed stopped");
            assertTrue(Files.exists(root), "the root raw volume survives the refusal");
            running.stopRuntime(500L);
        } finally {
            Files.deleteIfExists(root);
        }
    }

    private static ReflectiveAvfVirtualMachineApi api(VirtualMachineManager manager) {
        return new ReflectiveAvfVirtualMachineApi(
                new FakeVirtualizationContext(manager), AvfPersistentConfigTest.class.getClassLoader());
    }

    private static AvfLaunchSpec spec(String instanceId, Path root, long memoryMb) {
        return AvfLaunchSpec.builder(instanceId, "/data/kernel", "/data/initramfs.cpio.gz", root.toString())
                .memoryMb(memoryMb)
                .build();
    }

    private static VirtualMachineConfig protectedConfig() {
        VirtualMachineCustomImageConfig image = new VirtualMachineCustomImageConfig.Builder()
                .setName("stored")
                .setKernelPath("/data/kernel")
                .setInitrdPath("/data/initramfs.cpio.gz")
                .build();
        return new VirtualMachineConfig.Builder(new FakeVirtualizationContext(null))
                .setCustomImageConfig(image)
                .setProtectedVm(true)
                .setMemoryBytes(512L << 20)
                .build();
    }

    /** The manager records the config it was asked to create. */
    private static VirtualMachineConfig lastConfig(VirtualMachineManager manager) {
        return manager.lastConfigForTest();
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) fail(description);
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            fail(description + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void assertEquals(int expected, int actual, String description) {
        if (expected != actual) fail(description + " (expected " + expected + ", got " + actual + ")");
    }

    private static void assertEquals(long expected, long actual, String description) {
        if (expected != actual) fail(description + " (expected " + expected + ", got " + actual + ")");
    }

    private static void fail(String reason) {
        System.out.println("AVF_PERSISTENT_CONFIG_TEST_FAIL reason=" + reason);
        System.exit(1);
    }
}
