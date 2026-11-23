package io.github.nyzeep.orchestella.runtime.instance;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies W3-00 synthetic compatibility fixtures derived from historical schemas (v0/v1)
 * and historical SAF archive headers across load, start, clone, stop, host-death reconciliation,
 * cold restore, and SAF export/import round-trips.
 */
public final class LegacyCompatibilityFixtureTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.ofEpochMilli(1_789_000_000_000L), ZoneOffset.UTC);

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void legacySchema0AndSchema1FixturesLoadWithoutWriteMigrationAndPreserveIdentityAndVolumeDigest()
            throws Exception {
        for (String fixtureInfo : new String[] {
                "/instance-metadata-v0-legacy.properties|00000000-0000-4000-8000-000000000002",
                "/instance-metadata-v1-golden.properties|00000000-0000-4000-8000-000000000001"
        }) {
            String[] parts = fixtureInfo.split("\\|");
            String resourcePath = parts[0];
            String id = parts[1];

            Path caseDir = temporaryFolder.newFolder("legacy-" + id).toPath();
            Path storeDir = caseDir.resolve("instances");
            Files.createDirectories(storeDir);

            Path propsPath = storeDir.resolve(id + ".properties");
            try (InputStream fixture = getClass().getResourceAsStream(resourcePath)) {
                assertNotNull("missing fixture " + resourcePath, fixture);
                Files.copy(fixture, propsPath);
            }

            Properties loadedProps = readProperties(propsPath);
            Path volumePath = caseDir.resolve("root.raw");
            byte[] volumeBytes = ("SYNTHETIC_LEGACY_VOLUME_" + id).getBytes(StandardCharsets.UTF_8);
            Files.write(volumePath, volumeBytes);
            String expectedVolumeSha = StorageContract.sha256Hex(volumeBytes);
            loadedProps.setProperty("rootVolume", volumePath.toString());
            try (var out = Files.newOutputStream(propsPath)) {
                loadedProps.store(out, "synthetic fixture with local temp volume");
            }
            byte[] fileBytesBeforeLoad = Files.readAllBytes(propsPath);

            InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
            InstanceLifecycle.Instance loaded = store.load(id);

            // Loading must never trigger write migration.
            assertArrayEquals("load must not modify legacy metadata file",
                    fileBytesBeforeLoad, Files.readAllBytes(propsPath));
            assertTrue("legacy instance keeps absent execution identity",
                    loaded.executionIdentity().isLegacyAbsent());

            InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
            controller.start(id);
            controller.recordNormalGuestStop(id);

            // Clone preserves absent identity and volume digest.
            InstanceLifecycle.Instance cloned = controller.cloneInstance(
                    id, caseDir.resolve("cloned.raw"));
            assertTrue(cloned.executionIdentity().isLegacyAbsent());
            assertEquals(expectedVolumeSha, StorageContract.sha256Hex(Files.readAllBytes(cloned.rootVolume())));

            // Host-death reconciliation + cold restore preserve identity and volume digest.
            controller.start(id);
            controller.reconcileAfterHostDeath(id);
            assertEquals(InstanceLifecycle.State.INTERRUPTED, store.load(id).state());
            controller.coldRestore(id);
            assertEquals(InstanceLifecycle.State.RUNNING, store.load(id).state());
            controller.recordNormalGuestStop(id);
            assertTrue(store.load(id).executionIdentity().isLegacyAbsent());

            // SAF export + import preserves legacy absent identity and root-volume SHA-256.
            ByteArrayOutputStream archiveOut = new ByteArrayOutputStream();
            StorageContract.InstanceExportMetadata exportMetadata = controller.exportToArchive(id, archiveOut);
            assertTrue(exportMetadata.executionIdentity().isLegacyAbsent());
            assertEquals(expectedVolumeSha, exportMetadata.sha256RootVolume());

            Path importedVolume = caseDir.resolve("imported.raw");
            InstanceLifecycle.Instance imported = controller.importFromArchive(
                    new ByteArrayInputStream(archiveOut.toByteArray()),
                    importedVolume,
                    UUID.randomUUID().toString());
            assertTrue(imported.executionIdentity().isLegacyAbsent());
            assertEquals(expectedVolumeSha, StorageContract.sha256Hex(Files.readAllBytes(imported.rootVolume())));
        }
    }

    @Test
    public void historicalSafArchivesWithoutStrategyFieldsImportAndPreserveVolumeDigest() throws Exception {
        for (String resourcePath : new String[] {
                "/legacy-export-v0-metadata.json",
                "/legacy-export-v1-metadata.json"
        }) {
            Path caseDir = temporaryFolder.newFolder("saf-" + resourcePath.hashCode()).toPath();
            byte[] rawVolume = ("SYNTHETIC_ARCHIVE_ROOT_" + resourcePath).getBytes(StandardCharsets.UTF_8);
            String volumeSha = StorageContract.sha256Hex(rawVolume);

            String templateJson;
            try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
                assertNotNull("missing fixture " + resourcePath, in);
                templateJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String actualJson = templateJson
                    .replace("1048576", Integer.toString(rawVolume.length))
                    .replace("0000000000000000000000000000000000000000000000000000000000000000", volumeSha);
            byte[] metadataBytes = actualJson.getBytes(StandardCharsets.UTF_8);
            String metadataSha = StorageContract.sha256Hex(metadataBytes);

            Map<String, String> entries = new LinkedHashMap<>();
            entries.put(StorageContract.ROOT_VOLUME_FILE_NAME, volumeSha);
            entries.put(StorageContract.METADATA_FILE_NAME, metadataSha);
            byte[] checksumBytes = new StorageContract.ChecksumManifest(entries)
                    .format().getBytes(StandardCharsets.UTF_8);

            ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
                zip.putNextEntry(new ZipEntry(StorageContract.ROOT_VOLUME_FILE_NAME));
                zip.write(rawVolume);
                zip.closeEntry();

                zip.putNextEntry(new ZipEntry(StorageContract.METADATA_FILE_NAME));
                zip.write(metadataBytes);
                zip.closeEntry();

                zip.putNextEntry(new ZipEntry(StorageContract.CHECKSUMS_FILE_NAME));
                zip.write(checksumBytes);
                zip.closeEntry();
            }

            InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(caseDir.resolve("store"));
            InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
            Path targetVolume = caseDir.resolve("restored.raw");
            InstanceLifecycle.Instance restored = controller.importFromArchive(
                    new ByteArrayInputStream(zipBytes.toByteArray()),
                    targetVolume,
                    UUID.randomUUID().toString());

            assertTrue(restored.executionIdentity().isLegacyAbsent());
            assertEquals(volumeSha, StorageContract.sha256Hex(Files.readAllBytes(restored.rootVolume())));
            assertFalse(readProperties(caseDir.resolve("store").resolve(restored.id() + ".properties"))
                    .containsKey("execution.strategy"));
        }
    }

    @Test
    public void explicitUserModeKernelInstanceSurvivesReloadStateTransitionsCloneColdRestoreAndSafRoundTrip()
            throws Exception {
        Path caseDir = temporaryFolder.newFolder("qa-uml-roundtrip").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(caseDir.resolve("store"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path volumePath = caseDir.resolve("uml-qa.raw");
        byte[] volumeBytes = "SYNTHETIC_UML_RAW_SPARSE_DISK".getBytes(StandardCharsets.UTF_8);
        Files.write(volumePath, volumeBytes);
        String expectedVolumeSha = StorageContract.sha256Hex(volumeBytes);

        ExecutionPolicyContract.InstanceExecutionIdentity umlIdentity =
                ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                        FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                        StorageContract.RootVolumeFormat.RAW_SPARSE);

        InstanceLifecycle.Instance created = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                volumePath,
                VmHardwareConfig.defaultConfig(),
                InstanceUserConfig.defaultRoot(),
                umlIdentity);
        assertEquals(umlIdentity, created.executionIdentity());

        // Reload from a fresh store instance.
        InstanceLifecycle.FileStore reloadedStore = new InstanceLifecycle.FileStore(caseDir.resolve("store"));
        assertEquals(umlIdentity, reloadedStore.load(created.id()).executionIdentity());

        // State changes, reconciliation, and cold restore preserve explicit identity.
        controller.beginProvisioning(created.id());
        controller.completeProvisioning(created.id());
        controller.start(created.id());
        controller.reconcileAfterHostDeath(created.id());
        controller.coldRestore(created.id());
        controller.recordNormalGuestStop(created.id());
        assertEquals(umlIdentity, reloadedStore.load(created.id()).executionIdentity());

        // Clone preserves explicit identity and root-volume digest.
        InstanceLifecycle.Instance cloned = controller.cloneInstance(created.id(), caseDir.resolve("cloned-uml.raw"));
        assertEquals(umlIdentity, cloned.executionIdentity());
        assertEquals(expectedVolumeSha, StorageContract.sha256Hex(Files.readAllBytes(cloned.rootVolume())));

        // SAF export + import preserves explicit identity and root-volume digest.
        ByteArrayOutputStream archiveOut = new ByteArrayOutputStream();
        StorageContract.InstanceExportMetadata exported = controller.exportToArchive(created.id(), archiveOut);
        assertEquals(umlIdentity, exported.executionIdentity());

        InstanceLifecycle.Instance imported = controller.importFromArchive(
                new ByteArrayInputStream(archiveOut.toByteArray()),
                caseDir.resolve("imported-uml.raw"),
                UUID.randomUUID().toString());
        assertEquals(umlIdentity, imported.executionIdentity());
        assertEquals(expectedVolumeSha, StorageContract.sha256Hex(Files.readAllBytes(imported.rootVolume())));
    }

    private static Properties readProperties(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        }
        return properties;
    }
}
