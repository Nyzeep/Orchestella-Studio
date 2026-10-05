package io.github.nyzeep.droidlayer.runtime.engineavf;

import android.system.virtualmachine.VirtualMachineCustomImageConfig;
import io.github.nyzeep.droidlayer.runtime.contract.NetworkContract;

/**
 * Ticket 36 AC-1: the outbound network assembly contract for the AVF profile.
 * The launched custom VM must declare network support (the crosvm backend
 * switch that pairs the guest virtio-net with the host-side bridge/NAT) and
 * must receive the exact guest network contract keys — vsock service ports and
 * {@code droidlayer.dns=} — through the kernel cmdline setter the platform
 * builder exposes. The setter surface is optional by platform version, so its
 * absence is recorded in the reflection notes, never silently dropped.
 */
public final class AvfNetworkAssemblyContractTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    public static void main(String[] arguments) throws Exception {
        assemblesNetworkSupportAndTheGuestCmdlineKeys();
        System.out.println("AVF_NETWORK_ASSEMBLY_CONTRACT_TEST_OK network_supported=1"
                + " cmdline_keys=1 dns_propagation=1");
    }

    private static void assemblesNetworkSupportAndTheGuestCmdlineKeys() throws Exception {
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101)) {
            AvfLaunchSpec spec = AvfLaunchSpec.builder(
                            "net-assembly-1", "/data/kernel", "/data/initramfs.cpio.gz",
                            "/data/root.raw")
                    .networkSpec(NetworkContract.OutboundNetworkSpec.withDns("1.1.1.1"))
                    .build();
            AvfVirtualMachineEngine engine =
                    new AvfVirtualMachineEngine(AvfVirtualMachineApiFixture.recordingApiOver(guest));
            AvfVirtualMachineEngine.AvfVmSession session = engine.launch(spec);

            assertTrue(!guest.observedConfigs().isEmpty(), "one config must reach the manager");
            VirtualMachineCustomImageConfig config = guest.observedConfigs().get(0);
            assertTrue(config.isNetworkSupported(), "outbound network must be declared (AC-1)");
            assertTrue(
                    session.reflectionNotes().stream()
                            .anyMatch(note -> note.contains("setNetworkSupported: applied")),
                    "the network switch must be a recorded assembly step: "
                            + session.reflectionNotes());

            String cmdline = config.kernelCmdline();
            assertTrue(
                    cmdline.contains("droidlayer.vsock_control_port=9100")
                            && cmdline.contains("droidlayer.vsock_terminal_port=9101"),
                    "the guest learns both vsock service ports from the cmdline: " + cmdline);
            assertTrue(
                    cmdline.contains("droidlayer.dns=1.1.1.1"),
                    "the custom resolver reaches the guest cmdline (AC-1, Ticket 27 parity): "
                            + cmdline);
            assertTrue(
                    session.reflectionNotes().stream()
                            .anyMatch(note -> note.contains("setKernelCmdline: applied")),
                    "the cmdline delivery step must be recorded: " + session.reflectionNotes());
            session.closeChannels();
        }
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            fail(description);
        }
    }

    private static void fail(String reason) {
        System.out.println("AVF_NETWORK_ASSEMBLY_CONTRACT_TEST_FAIL reason=" + reason);
        System.exit(1);
    }
}
