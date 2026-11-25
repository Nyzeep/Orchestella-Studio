package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies that {@link TerminalActivity} socket recovery naming stays locked to
 * {@link QemuInstanceLauncher} and honors {@link FullLinuxContract.ExecutionStrategy}
 * boundaries (ADR-0002).
 */
public final class TerminalSocketRecoveryTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void socketNamesMatchQemuInstanceLauncherProductionFormat() throws Exception {
        assertEquals("droidlayer-control-inst-42", TerminalActivity.controlSocketName("inst-42"));
        assertEquals("droidlayer-terminal-inst-42", TerminalActivity.terminalSocketName("inst-42"));

        String launcherSource = new String(
                Files.readAllBytes(repoRoot().resolve(
                        "app/src/main/java/io/github/nyzeep/orchestella/QemuInstanceLauncher.java")),
                StandardCharsets.UTF_8);
        assertTrue(launcherSource.contains("\"droidlayer-control-\" + parameters.instanceId"));
        assertTrue(launcherSource.contains("\"droidlayer-terminal-\" + parameters.instanceId"));

        String terminalSource = new String(
                Files.readAllBytes(repoRoot().resolve(
                        "app/src/main/java/io/github/nyzeep/orchestella/TerminalActivity.java")),
                StandardCharsets.UTF_8);
        assertFalse(terminalSource.contains("orchestella-\" + socketSuffix + \"-ctrl"));
        assertFalse(terminalSource.contains("orchestella-\" + socketSuffix + \"-term"));
    }

    @Test
    public void socketRecoveryOnlyAppliesToLegacyOrSoftwareVirtualizationInstances() throws Exception {
        Path root = tmp.newFolder("instances").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(root);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        Path vol = tmp.newFile("root.raw").toPath();

        InstanceLifecycle.Instance legacy = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"),
                vol,
                VmHardwareConfig.defaultConfig(),
                InstanceUserConfig.defaultRoot(),
                ExecutionPolicyContract.InstanceExecutionIdentity.legacyAbsent());

        InstanceLifecycle.Instance qemu = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"),
                vol,
                VmHardwareConfig.defaultConfig(),
                InstanceUserConfig.defaultRoot(),
                ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                        StorageContract.RootVolumeFormat.RAW_SPARSE));

        InstanceLifecycle.Instance uml = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.22.2"),
                vol,
                VmHardwareConfig.defaultConfig(),
                InstanceUserConfig.defaultRoot(),
                ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                        FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                        StorageContract.RootVolumeFormat.RAW_SPARSE));

        InstanceLifecycle.Instance avf = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"),
                vol,
                VmHardwareConfig.defaultConfig(),
                InstanceUserConfig.defaultRoot(),
                ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                        FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION,
                        StorageContract.RootVolumeFormat.RAW_SPARSE));

        assertFalse(TerminalActivity.supportsSocketRecovery(null));
        assertTrue(TerminalActivity.supportsSocketRecovery(legacy));
        assertTrue(TerminalActivity.supportsSocketRecovery(qemu));
        assertFalse(TerminalActivity.supportsSocketRecovery(uml));
        assertFalse(TerminalActivity.supportsSocketRecovery(avf));
    }

    private static Path repoRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }
}
