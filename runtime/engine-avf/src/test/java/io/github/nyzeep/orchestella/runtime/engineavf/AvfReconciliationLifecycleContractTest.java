package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * The Ticket 28 stop/cold-restore/abnormal-termination semantics driven end to
 * end over the AVF strategy: a host death leaves the stale RUNNING slot for
 * reconciliation, INTERRUPTED instances cold-restore back into a live vsock
 * session, an abnormal VM exit is marked CRASHED with a real diagnostics
 * archive, and the exported report embeds the archived log. The state-machine
 * rules are the QEMU strategy's own — only the VM transport differs.
 */
public final class AvfReconciliationLifecycleContractTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-05T00:00:00Z"), ZoneId.of("UTC"));

    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(arguments[0]);
        Files.createDirectories(output);

        reconcilesAStaleRunningInstanceAndColdRestoresOverAvf(output);
        marksAnAbnormalAvfExitAndExportsDiagnostics(output);
        System.out.println(
                "AVF_RECONCILIATION_LIFECYCLE_CONTRACT_TEST_OK"
                        + " reconcile=1 cold_restore=1 abnormal=1 diagnostics_export=1");
    }

    private static void reconcilesAStaleRunningInstanceAndColdRestoresOverAvf(Path output)
            throws Exception {
        InstanceLifecycle.FileStore store =
                new InstanceLifecycle.FileStore(output.resolve("metadata-reconcile"));
        InstanceLifecycle.Controller controller =
                new InstanceLifecycle.Controller(store, CLOCK);
        Path rootVolume = output.resolve("reconcile-root.raw");
        Files.write(rootVolume, new byte[4096]);

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        controller.start(instance.id());

        // The live session dies with the host process: nothing records a
        // stop, so the store keeps the stale RUNNING slot.
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101)) {
            AvfVirtualMachineEngine engine =
                    new AvfVirtualMachineEngine(AvfVirtualMachineApiFixture.apiOver(guest));
            AvfVirtualMachineEngine.AvfVmSession session = engine.launch(launchSpec(
                    instance.id(), rootVolume));
            BridgeClient client = new BridgeClient(
                    session.controlChannel().inputStream(),
                    session.controlChannel().outputStream());
            assertTrue(client.handshake(TOKEN).accepted(),
                    "the instance is live before the simulated host death");
            session.closeChannels();
        }

        // Reopen: a fresh store reconstruction must still show RUNNING.
        InstanceLifecycle.FileStore reopened =
                new InstanceLifecycle.FileStore(output.resolve("metadata-reconcile"));
        assertEquals(
                InstanceLifecycle.State.RUNNING,
                reopened.load(instance.id()).state(),
                "the stale RUNNING slot survives host death");

        InstanceLifecycle.Controller freshController =
                new InstanceLifecycle.Controller(reopened, CLOCK);
        InstanceLifecycle.Instance reconciled = freshController.reconcileAfterHostDeath(instance.id());
        assertEquals(
                InstanceLifecycle.State.INTERRUPTED,
                reconciled.state(),
                "reconciliation moves the stale slot to INTERRUPTED");
        assertEquals(
                InstanceLifecycle.State.INTERRUPTED,
                reopened.load(instance.id()).state(),
                "INTERRUPTED persists");

        // A cold restore is the only way back to RUNNING, and it re-arms the
        // single-running invariant.
        InstanceLifecycle.Instance restored = freshController.coldRestore(instance.id());
        assertEquals(
                InstanceLifecycle.State.RUNNING,
                restored.state(),
                "cold restore restarts the interrupted instance");

        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101)) {
            AvfVirtualMachineEngine engine =
                    new AvfVirtualMachineEngine(AvfVirtualMachineApiFixture.apiOver(guest));
            AvfVirtualMachineEngine.AvfVmSession session = engine.launch(launchSpec(
                    instance.id(), rootVolume));
            BridgeClient client = new BridgeClient(
                    session.controlChannel().inputStream(),
                    session.controlChannel().outputStream());
            assertTrue(client.handshake(TOKEN).accepted(),
                    "the cold-restored instance serves the bridge over vsock again");
            session.closeChannels();
        }
        freshController.recordNormalGuestStop(instance.id());
        assertEquals(
                InstanceLifecycle.State.STOPPED,
                reopened.load(instance.id()).state(),
                "the restored session ends in a clean STOPPED");
    }

    private static void marksAnAbnormalAvfExitAndExportsDiagnostics(Path output)
            throws Exception {
        InstanceLifecycle.FileStore store =
                new InstanceLifecycle.FileStore(output.resolve("metadata-crash"));
        InstanceLifecycle.Controller controller =
                new InstanceLifecycle.Controller(store, CLOCK);
        Path rootVolume = output.resolve("crash-root.raw");
        Files.write(rootVolume, new byte[4096]);

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        controller.start(instance.id());

        int exitStatus;
        java.util.List<String> notes;
        // Manual close management: the mid-session guest.close() IS the
        // simulated VMM death, distinct from the try-with-resources cleanup.
        SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101);
        try {
            AvfVirtualMachineEngine engine =
                    new AvfVirtualMachineEngine(AvfVirtualMachineApiFixture.apiOver(guest));
            AvfVirtualMachineEngine.AvfVmSession session = engine.launch(launchSpec(
                    instance.id(), rootVolume));
            notes = session.reflectionNotes();
            // The VM dies without a normal guest stop. The channels drop
            // first (what a VMM death looks like), then the host tears the
            // dead VM down and observes the end through waitFor().
            guest.close();
            session.stop();
            exitStatus = session.waitFor();
            session.closeChannels();
        } finally {
            guest.close();
        }
        Path diagnosticsDirectory = output.resolve("diagnostics").resolve(instance.id());
        Path archived = AvfSessionDiagnostics.archive(
                diagnosticsDirectory, instance.id(), exitStatus, notes);
        assertTrue(Files.exists(archived), "the diagnostics archive was written");
        assertTrue(
                Files.readString(archived, StandardCharsets.UTF_8)
                        .contains("exit_status=" + exitStatus),
                "the archive records the observed exit status");

        controller.recordAbnormalTermination(instance.id(), new InstanceLifecycle.CrashDiagnostic(
                CLOCK.millis(), exitStatus, archived,
                "AVF session ended abnormally (simulated VMM death)"));
        InstanceLifecycle.Instance crashed = store.load(instance.id());
        assertEquals(InstanceLifecycle.State.CRASHED, crashed.state(), "abnormal exit marks CRASHED");
        assertTrue(crashed.crashDiagnostic() != null, "the crash diagnostic is bound");

        // The diagnostic survives a store reconstruction.
        InstanceLifecycle.FileStore reopened =
                new InstanceLifecycle.FileStore(output.resolve("metadata-crash"));
        assertTrue(reopened.load(instance.id()).crashDiagnostic() != null,
                "the crash diagnostic persists across store reconstruction");

        // Export produces a retrievable report containing the archived log.
        Path exportTarget = output.resolve("export")
                .resolve(instance.id() + "-diagnostics.txt");
        controller.exportDiagnostic(instance.id(), exportTarget);
        String exported = Files.readString(exportTarget, StandardCharsets.UTF_8);
        assertTrue(exported.contains("exit_status=" + exitStatus),
                "the export embeds the diagnostics archive");

        // Cold restore is the recovery path and clears the crash marker.
        InstanceLifecycle.Instance restored = controller.coldRestore(instance.id());
        assertEquals(InstanceLifecycle.State.RUNNING, restored.state(), "cold restore from CRASHED");
        assertTrue(store.load(instance.id()).crashDiagnostic() == null,
                "the crash marker clears on cold restore");
        controller.recordNormalGuestStop(instance.id());
    }

    private static AvfLaunchSpec launchSpec(String instanceId, Path rootVolume) {
        return AvfLaunchSpec.builder(
                        instanceId,
                        rootVolume.resolveSibling("Image").toString(),
                        rootVolume.resolveSibling("initramfs-embedded.cpio.gz").toString(),
                        rootVolume.toString())
                .build();
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
        System.out.println("AVF_RECONCILIATION_LIFECYCLE_CONTRACT_TEST_FAIL reason=" + reason);
        System.exit(1);
    }
}
