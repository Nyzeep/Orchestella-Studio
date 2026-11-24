package io.github.nyzeep.orchestella.runtime.instance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class InstanceLifecycleMultiUmlPolicyTest {
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.ofEpochMilli(1_790_500_000_000L), ZoneOffset.UTC);

    @Rule public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void defaultPolicyStillEnforcesSingleRunningInstanceWhileIsolatedQaPolicyAllowsMultipleUmlOnly()
            throws Exception {
        Path root = tmp.newFolder("multi-uml").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(root.resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, FIXED_CLOCK);

        InstanceLifecycle.Instance umlA = createProvisioned(
                controller, root.resolve("uml-a.raw"), FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL);
        InstanceLifecycle.Instance umlB = createProvisioned(
                controller, root.resolve("uml-b.raw"), FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL);
        InstanceLifecycle.Instance qemu = createProvisioned(
                controller, root.resolve("qemu.raw"), null);

        // Default single-running policy still blocks a second UML instance.
        controller.start(umlA.id());
        assertThrows(
                InstanceLifecycle.SingleRunningInstanceException.class,
                () -> controller.start(umlB.id()));

        // Isolated multi-UML policy allows a second USER_MODE_KERNEL instance alongside umlA.
        controller.start(umlB.id(), InstanceLifecycle.ConcurrencyPolicy.ISOLATED_MULTI_USER_MODE_KERNEL);
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(umlA.id()).state());
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(umlB.id()).state());

        // Non-UML (QEMU/legacy) cannot co-run under either policy.
        assertThrows(
                InstanceLifecycle.SingleRunningInstanceException.class,
                () -> controller.start(
                        qemu.id(), InstanceLifecycle.ConcurrencyPolicy.ISOLATED_MULTI_USER_MODE_KERNEL));

        // Simulate crash on umlB and coldRestore under multi-UML policy while umlA stays RUNNING.
        controller.recordCrash(umlB.id(), 137, null, "forced");
        controller.coldRestore(
                umlB.id(), InstanceLifecycle.ConcurrencyPolicy.ISOLATED_MULTI_USER_MODE_KERNEL);
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(umlB.id()).state());

        controller.recordNormalGuestStop(umlA.id());
        controller.recordNormalGuestStop(umlB.id());

        // If a QEMU instance is RUNNING, isolated multi-UML policy still blocks starting UML alongside QEMU.
        controller.start(qemu.id());
        assertThrows(
                InstanceLifecycle.SingleRunningInstanceException.class,
                () -> controller.start(
                        umlA.id(), InstanceLifecycle.ConcurrencyPolicy.ISOLATED_MULTI_USER_MODE_KERNEL));
    }

    private static InstanceLifecycle.Instance createProvisioned(
            InstanceLifecycle.Controller controller,
            Path rootVolume,
            FullLinuxContract.ExecutionStrategy strategy)
            throws Exception {
        Files.createDirectories(rootVolume.getParent());
        Files.createFile(rootVolume);
        ExecutionPolicyContract.InstanceExecutionIdentity identity = strategy == null
                ? ExecutionPolicyContract.InstanceExecutionIdentity.legacyAbsent()
                : ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                        strategy, StorageContract.RootVolumeFormat.RAW_SPARSE);
        InstanceLifecycle.Instance created = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.22.2"),
                rootVolume,
                VmHardwareConfig.defaultConfig(),
                InstanceUserConfig.defaultRoot(),
                identity);
        controller.beginProvisioning(created.id());
        controller.completeProvisioning(created.id());
        return created;
    }
}
