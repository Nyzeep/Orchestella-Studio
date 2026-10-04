package io.github.nyzeep.droidlayer.runtime.engineavf;

import io.github.nyzeep.droidlayer.runtime.contract.BridgeClient;
import io.github.nyzeep.droidlayer.runtime.instance.InstanceLifecycle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

/**
 * The normal Full Linux instance lifecycle driven end-to-end over the AVF
 * adapter's simulated implementation: CREATED → PROVISIONING → STOPPED →
 * RUNNING (with a live bridge handshake over vsock) → observed guest exit →
 * STOPPED. The same state machine rules apply as for the QEMU strategy.
 */
public final class AvfInstanceLifecycleContractTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(arguments[0]);
        Files.createDirectories(output);

        Path storeDir = output.resolve("instances");
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller =
                new InstanceLifecycle.Controller(store, Clock.systemUTC());

        Path rootVolume = output.resolve("lifecycle-root.raw");
        Files.write(rootVolume, new byte[4096]);

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        assertEquals(InstanceLifecycle.State.CREATED, instance.state(), "fresh instance state");

        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101)) {
            AvfVirtualMachineEngine engine =
                    new AvfVirtualMachineEngine(AvfVirtualMachineApiFixture.apiOver(guest));

            // beforeLaunch: first boot records CREATED -> PROVISIONING -> STOPPED.
            controller.beginProvisioning(instance.id());
            controller.completeProvisioning(instance.id());
            assertEquals(
                    InstanceLifecycle.State.STOPPED,
                    store.load(instance.id()).state(),
                    "provisioning recorded before any boot");

            // Every boot takes the single RUNNING slot via STOPPED -> RUNNING.
            controller.start(instance.id());
            assertEquals(
                    InstanceLifecycle.State.RUNNING,
                    store.load(instance.id()).state(),
                    "running slot taken for the AVF boot");

            AvfLaunchSpec spec = AvfLaunchSpec.builder(
                            "lifecycle-1",
                            output.resolve("Image").toString(),
                            output.resolve("initramfs-embedded.cpio.gz").toString(),
                            rootVolume.toString())
                    .build();
            AvfVirtualMachineEngine.AvfVmSession session = engine.launch(spec);

            BridgeClient client = new BridgeClient(
                    session.controlChannel().inputStream(),
                    session.controlChannel().outputStream());
            BridgeClient.Result handshake = client.handshake(TOKEN);
            assertTrue(handshake.accepted(), "bridge handshake must succeed over vsock while RUNNING");
            assertTrue(client.ping().accepted(), "ping/pong must succeed over vsock while RUNNING");
            session.closeChannels();

            // The simulated guest exits; the host observes it and records the
            // only allowed RUNNING -> STOPPED transition.
            session.stop();
            session.waitFor();
            controller.recordNormalGuestStop(instance.id());
            assertEquals(
                    InstanceLifecycle.State.STOPPED,
                    store.load(instance.id()).state(),
                    "guest exit returns the instance to STOPPED");
        }

        System.out.println("AVF_INSTANCE_LIFECYCLE_CONTRACT_TEST_OK"
                + " created=1 provisioning=1 running=1 stopped=1 bridge=1");
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            fail(description);
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            fail(description + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void fail(String reason) {
        System.out.println("AVF_INSTANCE_LIFECYCLE_CONTRACT_TEST_FAIL reason=" + reason
                + " encoding=" + StandardCharsets.UTF_8.name());
        System.exit(1);
    }
}
