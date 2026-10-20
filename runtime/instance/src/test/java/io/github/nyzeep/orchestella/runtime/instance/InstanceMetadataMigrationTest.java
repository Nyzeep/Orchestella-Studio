package io.github.nyzeep.orchestella.runtime.instance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.DistroReference;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.FileStore;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.Instance;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Ticket 03 metadata contract: missing hardware keys are migrated with an
 * explicit provenance instead of silently becoming 2 vCPU / 2 GB, a partial
 * hardware block fails closed, and unknown keys never change effective
 * hardware.
 */
public final class InstanceMetadataMigrationTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.ofEpochMilli(1_789_000_000_000L), ZoneOffset.UTC);

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void legacyMetadataIsMarkedAndKeepsItsEffectiveHardware() throws Exception {
        Path dir = temporaryFolder.newFolder("legacy").toPath();
        FileStore store = new FileStore(dir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        Instance created = controller.create(
                new DistroReference("alpine", "3.24.1"), dir.resolve("root.raw"));

        Properties properties = read(propertiesFile(dir, created.id()));
        properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith("hardware.") || key.equals("metadata.schemaVersion"))
                .toList()
                .forEach(properties::remove);
        write(propertiesFile(dir, created.id()), properties);

        Instance legacy = store.load(created.id());
        assertEquals("the historical default stays 2 vCPU", 2, legacy.hardwareConfig().cpuCores());
        assertEquals("the historical default stays 2048 MB", 2048, legacy.hardwareConfig().ramMegabytes());
        assertTrue("legacy defaults must be explicit provenance",
                legacy.hardwareConfig().isLegacyDefaults());

        store.save(legacy);
        Instance reloaded = store.load(created.id());
        assertTrue("the migration marker survives a rewrite",
                reloaded.hardwareConfig().isLegacyDefaults());
        Properties rewritten = read(propertiesFile(dir, created.id()));
        assertEquals("metadata schema version is written", "1",
                rewritten.getProperty("metadata.schemaVersion"));
        assertEquals("the migration marker is persisted", "true",
                rewritten.getProperty("hardware.legacyDefaults"));
    }

    @Test
    public void partialHardwareBlockFailsClosedInsteadOfDefaulting() throws Exception {
        Path dir = temporaryFolder.newFolder("partial").toPath();
        FileStore store = new FileStore(dir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        Instance created = controller.create(
                new DistroReference("debian", "13"), dir.resolve("root.raw"));

        Properties properties = read(propertiesFile(dir, created.id()));
        properties.remove("hardware.ramMegabytes");
        properties.remove("hardware.diskCapacityBytes");
        write(propertiesFile(dir, created.id()), properties);

        try {
            store.load(created.id());
            fail("a partial hardware block must not be defaulted");
        } catch (IOException expected) {
            assertTrue("the failure names the incomplete hardware block",
                    expected.getCause() != null
                            && expected.getCause().getMessage().contains("hardware"));
        }
    }

    @Test
    public void unknownKeysDoNotChangeEffectiveHardware() throws Exception {
        Path dir = temporaryFolder.newFolder("unknown").toPath();
        FileStore store = new FileStore(dir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        VmHardwareConfig custom = VmHardwareConfig.builder()
                .cpuCores(4)
                .ramMegabytes(4096)
                .build();
        Instance created = controller.create(
                new DistroReference("ubuntu", "24.04"), dir.resolve("root.raw"), custom);

        Properties properties = read(propertiesFile(dir, created.id()));
        properties.setProperty("future.unknownKey", "ignored-value");
        write(propertiesFile(dir, created.id()), properties);

        Instance loaded = store.load(created.id());
        assertEquals("known hardware is preserved", 4, loaded.hardwareConfig().cpuCores());
        assertEquals("known memory is preserved", 4096, loaded.hardwareConfig().ramMegabytes());
        assertFalse("a fully specified block is not legacy", loaded.hardwareConfig().isLegacyDefaults());
    }

    private static Path propertiesFile(Path dir, String id) {
        return dir.resolve(id + ".properties");
    }

    private static Properties read(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static void write(Path path, Properties properties) throws IOException {
        try (OutputStream output = Files.newOutputStream(path)) {
            properties.store(output, "test fixture");
        }
    }
}
