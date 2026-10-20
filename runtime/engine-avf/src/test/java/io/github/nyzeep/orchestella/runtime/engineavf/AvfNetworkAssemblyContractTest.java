package io.github.nyzeep.orchestella.runtime.engineavf;

import android.system.virtualmachine.VirtualMachine;
import android.system.virtualmachine.VirtualMachineCustomImageConfig;
import android.system.virtualmachine.VirtualMachineManager;
import io.github.nyzeep.orchestella.runtime.contract.NetworkContract;

/**
 * Ticket 08 zero-device acceptance for the Android 17 network contract:
 * network is a plan (NONE / NATIVE_TAP + DHCP or static), the headless 2D GPU
 * workaround has a typed outcome, a GPU failure degrades or refuses according
 * to the plan, and a status-35 early stop is never a normal exit.
 */
public final class AvfNetworkAssemblyContractTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    public static void main(String[] arguments) throws Exception {
        tapDhcpEnablesNetworkAndAppliesHeadlessGpu();
        noneNeverCallsUseNetworkTrue();
        staticPlanCarriesGuestAddressingKeys();
        gpuFailureDegradesToNoNetworkWhenAllowed();
        gpuFailureRefusesWhenDegradationIsDisabled();
        gpuUnsupportedApiIsBenign();
        status35IsNeverANormalExit();
        System.out.println("AVF_NETWORK_ASSEMBLY_CONTRACT_TEST_OK plan=3 use_network=1"
                + " gpu_applied=1 gpu_failed=2 gpu_unsupported=1 status35=1"
                + " dns=1 static_keys=1 dhcp_no_static=1");
    }

    private static void tapDhcpEnablesNetworkAndAppliesHeadlessGpu() throws Exception {
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101)) {
            AvfVirtualMachineEngine.AvfVmSession session = launch(guest, spec("tap-dhcp")
                    .networkPlan(AvfNetworkPlan.nativeTapDhcp("1.1.1.1", true))
                    .build());
            VirtualMachineCustomImageConfig config = guest.observedConfigs().get(0);
            assertTrue(config.isNetworkSupported(), "NATIVE_TAP declares the crosvm network backend");
            assertTrue(config.gpuConfig() != null, "a headless GPU config must be declared");
            assertEquals("2d", config.gpuConfig().getBackend(), "the workaround uses the 2d backend");
            assertTrue(notes(session).contains("useNetwork: applied"), "useNetwork is recorded: " + notes(session));
            assertTrue(notes(session).contains("gpu-config: applied"), "GPU outcome is recorded: " + notes(session));
            String cmdline = config.kernelCmdline();
            assertTrue(cmdline.contains("droidlayer.dns=1.1.1.1"), "DNS is always on the cmdline: " + cmdline);
            assertFalse(cmdline.contains("orchestella.guest_ip"), "DHCP must not fake static keys: " + cmdline);
            session.closeChannels();
        }
    }

    private static void noneNeverCallsUseNetworkTrue() throws Exception {
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101)) {
            AvfVirtualMachineEngine.AvfVmSession session = launch(guest, spec("plan-none")
                    .networkPlan(AvfNetworkPlan.none("1.1.1.1"))
                    .build());
            VirtualMachineCustomImageConfig config = guest.observedConfigs().get(0);
            assertFalse(config.isNetworkSupported(), "NONE must not declare network support");
            assertFalse(notes(session).contains("useNetwork: applied"),
                    "NONE must never call useNetwork(true): " + notes(session));
            assertTrue(notes(session).contains("useNetwork: skipped"), "the skip is recorded");
            assertTrue(config.kernelCmdline().contains("droidlayer.dns=1.1.1.1"),
                    "DNS stays on the cmdline for a later retry");
            session.closeChannels();
        }
    }

    private static void staticPlanCarriesGuestAddressingKeys() throws Exception {
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101)) {
            AvfVirtualMachineEngine.AvfVmSession session = launch(guest, spec("plan-static")
                    .networkPlan(AvfNetworkPlan.nativeTapStatic(
                            "10.0.2.15", "10.0.2.2", "255.255.255.0", "1.1.1.1", true))
                    .build());
            String cmdline = guest.observedConfigs().get(0).kernelCmdline();
            assertTrue(cmdline.contains("orchestella.guest_ip=10.0.2.15"), "guest IP key: " + cmdline);
            assertTrue(cmdline.contains("orchestella.guest_gw=10.0.2.2"), "gateway key: " + cmdline);
            assertTrue(cmdline.contains("orchestella.guest_mask=255.255.255.0"), "netmask key: " + cmdline);
            session.closeChannels();
        }
    }

    private static void gpuFailureDegradesToNoNetworkWhenAllowed() throws Exception {
        VirtualMachineCustomImageConfig.GpuConfig.Builder.failForTest = true;
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101)) {
            AvfVirtualMachineEngine.AvfVmSession session = launch(guest, spec("gpu-degrade")
                    .networkPlan(AvfNetworkPlan.nativeTapDhcp("1.1.1.1", true))
                    .build());
            VirtualMachineCustomImageConfig config = guest.observedConfigs().get(0);
            assertFalse(config.isNetworkSupported(), "a failed GPU workaround degrades to no network");
            assertTrue(notes(session).contains("gpu-config: failed"), "the failure is recorded: " + notes(session));
            assertTrue(notes(session).contains("network: degraded"), "the degradation is visible");
            assertFalse(notes(session).contains("useNetwork: applied"), "degraded launch must not enable network");
        } finally {
            VirtualMachineCustomImageConfig.GpuConfig.Builder.failForTest = false;
        }
    }

    private static void gpuFailureRefusesWhenDegradationIsDisabled() throws Exception {
        VirtualMachineCustomImageConfig.GpuConfig.Builder.failForTest = true;
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101)) {
            // A refused launch must not reach create.
            VirtualMachineManager observing = guest.newManager();
            ReflectiveAvfVirtualMachineApi api = new ReflectiveAvfVirtualMachineApi(
                    guest.newContext(observing), AvfNetworkAssemblyContractTest.class.getClassLoader());
            try {
                new AvfVirtualMachineEngine(api).launch(spec("gpu-refuse")
                        .networkPlan(AvfNetworkPlan.nativeTapDhcp("1.1.1.1", false))
                        .build());
                fail("a failed GPU workaround without degradation must refuse the launch");
            } catch (AvfEngineException expected) {
                assertEquals(AvfEngineException.NETWORK_BACKEND_UNAVAILABLE, expected.reason(),
                        "the refusal is a typed network backend failure");
            }
            assertEquals(0, observing.createCallsForTest(), "a refused launch never creates a VM");
        } finally {
            VirtualMachineCustomImageConfig.GpuConfig.Builder.failForTest = false;
        }
    }

    private static void gpuUnsupportedApiIsBenign() throws Exception {
        VirtualMachineCustomImageConfig.GpuConfig.Builder.unsupportedForTest = true;
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101)) {
            AvfVirtualMachineEngine.AvfVmSession session = launch(guest, spec("gpu-unsupported")
                    .networkPlan(AvfNetworkPlan.nativeTapDhcp("1.1.1.1", true))
                    .build());
            VirtualMachineCustomImageConfig config = guest.observedConfigs().get(0);
            assertTrue(config.isNetworkSupported(),
                    "a missing GPU API is benign and keeps the legacy network attempt");
            assertTrue(notes(session).contains("gpu-config: unsupported"),
                    "the unsupported outcome is recorded: " + notes(session));
            session.closeChannels();
        } finally {
            VirtualMachineCustomImageConfig.GpuConfig.Builder.unsupportedForTest = false;
        }
    }

    private static void status35IsNeverANormalExit() throws Exception {
        VirtualMachine vm = VirtualMachine.withNumericStatus(() -> VirtualMachine.STATUS_STOPPED);
        vm.exitStatusForTest = 35;
        VirtualMachineManager manager = new VirtualMachineManager((name, config) -> vm);
        ReflectiveAvfVirtualMachineApi api = new ReflectiveAvfVirtualMachineApi(
                new android.system.virtualmachine.FakeVirtualizationContext(manager),
                AvfNetworkAssemblyContractTest.class.getClassLoader());
        AvfVirtualMachineEngine.AvfVmSession session = new AvfVirtualMachineEngine(api).launch(
                spec("status-35").networkPlan(AvfNetworkPlan.nativeTapDhcp("1.1.1.1", true)).build());
        AvfExitInfo exit = session.waitForExit();
        assertEquals(AvfExitInfo.Kind.NETWORK_BACKEND_UNAVAILABLE, exit.kind(),
                "status 35 with a network plan is the missing-net-feature signature");
        assertEquals(35, exit.exitCode(), "status 35 is preserved as a non-zero exit");
        assertTrue(session.waitFor() != 0, "waitFor must not collapse status 35 to a clean exit");
    }

    private static AvfLaunchSpec.Builder spec(String instanceId) {
        return AvfLaunchSpec.builder(instanceId, "/data/kernel", "/data/initramfs.cpio.gz",
                        "/data/root.raw")
                .networkSpec(NetworkContract.OutboundNetworkSpec.withDns("1.1.1.1"));
    }

    private static AvfVirtualMachineEngine.AvfVmSession launch(
            SimulatedAvfGuest guest, AvfLaunchSpec spec) throws Exception {
        return new AvfVirtualMachineEngine(AvfVirtualMachineApiFixture.recordingApiOver(guest))
                .launch(spec);
    }

    private static java.util.List<String> notes(AvfVirtualMachineEngine.AvfVmSession session) {
        return session.reflectionNotes();
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) fail(description);
    }

    private static void assertFalse(boolean condition, String description) {
        assertTrue(!condition, description);
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            fail(description + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void assertEquals(int expected, int actual, String description) {
        if (expected != actual) fail(description + " (expected " + expected + ", got " + actual + ")");
    }

    private static void fail(String reason) {
        System.out.println("AVF_NETWORK_ASSEMBLY_CONTRACT_TEST_FAIL reason=" + reason);
        System.exit(1);
    }
}
