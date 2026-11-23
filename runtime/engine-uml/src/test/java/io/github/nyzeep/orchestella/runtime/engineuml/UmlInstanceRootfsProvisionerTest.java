package io.github.nyzeep.orchestella.runtime.engineuml;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class UmlInstanceRootfsProvisionerTest {
    private static final String ALPINE_PIN =
            "6bf491907b705caa5fc65773fbbc1d0954530b6c455d4446f670a4c1fdcea489";
    private static final long ALPINE_SIZE = 3_981_043L;
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.ofEpochMilli(1_790_000_000_000L), ZoneOffset.UTC);

    @Rule public final TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void provisionsExt4RootVolumeAtomicallyAndSweepsTempPartFile() throws Exception {
        Path workDir = tempFolder.newFolder("happy").toPath();
        Path volumesDir = Files.createDirectories(workDir.resolve("root-volumes"));
        Path runtimeDir = Files.createDirectories(workDir.resolve("runtime"));
        Path targetVolume = volumesDir.resolve("inst-1.raw");
        Path seedTar = Files.createFile(workDir.resolve("alpine.tar.gz"));
        byte[] tarBytes = "SYNTHETIC_ALPINE_TAR_PAYLOAD".getBytes(StandardCharsets.UTF_8);
        Files.write(seedTar, tarBytes);
        String sha = sha256(tarBytes);

        UmlRootReservationRegistry registry = new UmlRootReservationRegistry();
        AtomicReference<UmlLaunchSpec> seenSpec = new AtomicReference<>();
        AtomicBoolean callbackRan = new AtomicBoolean(false);
        Object writeLock = new Object();

        UmlProcessFactory fakeFactory = (spec, cols, rows) -> {
            seenSpec.set(spec);
            Path stageDir = spec.ephemeralRootDir();
            assertTrue(Files.isRegularFile(stageDir.resolve("sbin/mkfs.ext4")));
            assertTrue(Files.isRegularFile(stageDir.resolve("etc/mke2fs.conf")));
            assertTrue(Files.isRegularFile(stageDir.resolve("bin/busybox")));
            assertTrue(Files.isRegularFile(stageDir.resolve("um-command.sh")));
            assertTrue(Files.isRegularFile(stageDir.resolve("guest-assets/um-command.sh")));
            assertTrue(Files.isRegularFile(stageDir.resolve("guest-assets/etc/orchestella/bridge-agent.sh")));

            Path tempPart = spec.ubd0Path();
            assertTrue(tempPart.getFileName().toString().contains(".provision-"));
            assertTrue(tempPart.getFileName().toString().endsWith(".part"));
            assertEquals(volumesDir.toAbsolutePath().normalize(), tempPart.getParent().toAbsolutePath().normalize());
            assertTrue(!Files.exists(targetVolume));

            writeValidExt4Magic(tempPart, StorageContract.MIN_ROOT_VOLUME_BYTES);
            String okLine = extractSuccessTokenFromScript(
                    Files.readString(stageDir.resolve("um-command.sh")));
            return new FakeProvisionProcess(0, okLine + "\n", false, true);
        };

        UmlInstanceRootfsProvisioner provisioner = new UmlInstanceRootfsProvisioner(
                fakeFactory,
                registry,
                noopToolchainStager(),
                path -> 4L * 1024L * 1024L * 1024L,
                FIXED_CLOCK);

        UmlInstanceRootfsProvisioner.ProvisionRequest request =
                new UmlInstanceRootfsProvisioner.ProvisionRequest(
                        "inst-1",
                        "gen-1",
                        targetVolume,
                        StorageContract.MIN_ROOT_VOLUME_BYTES,
                        "qa-uml-alpine",
                        new UmlInstanceRootfsProvisioner.SeedInput(
                                "alpine",
                                "3.22.2",
                                seedTar,
                                null,
                                null,
                                sha,
                                tarBytes.length),
                        UmlInstanceRootfsProvisioner.ProvisionMode.CREATE_NEW,
                        null,
                        "/system/lib/libuml_kernel.so",
                        "/system/lib/libuml_stub.so",
                        runtimeDir,
                        writeLock,
                        (instId, gen) -> true,
                        receipt -> {
                            assertTrue(Thread.holdsLock(writeLock));
                            callbackRan.set(true);
                        },
                        5_000L,
                        500L,
                        500L,
                        500L);

        UmlInstanceRootfsProvisioner.ProvisionReceipt receipt = provisioner.provision(request);
        assertEquals(targetVolume.toAbsolutePath().normalize(), receipt.rootVolume());
        assertEquals("alpine", receipt.distroId());
        assertEquals(sha, receipt.rootfsSha256());
        assertFalse(receipt.replacedExisting());
        assertTrue(callbackRan.get());
        assertFalse(registry.isReserved(targetVolume));
        assertEquals(
                UmlVolumeInspector.VolumeState.EXT4_READY,
                UmlVolumeInspector.inspect(targetVolume).state());

        // Only targetVolume remains in volumesDir (no lingering .part files); stage dir swept.
        try (var list = Files.list(volumesDir)) {
            assertEquals(List.of(targetVolume.getFileName().toString()),
                    list.map(p -> p.getFileName().toString()).toList());
        }
        assertFalse(Files.exists(seenSpec.get().ephemeralRootDir()));
    }

    @Test
    public void rejectsPartialDownloadChecksumMismatchInvalidPathsAndUnverifiedSeedWithoutTouchingTarget()
            throws Exception {
        Path workDir = tempFolder.newFolder("rejections").toPath();
        Path volumesDir = Files.createDirectories(workDir.resolve("root-volumes"));
        Path runtimeDir = Files.createDirectories(workDir.resolve("runtime"));
        Path targetVolume = volumesDir.resolve("inst-2.raw");
        byte[] oldBytes = "EXISTING_DO_NOT_TOUCH".getBytes(StandardCharsets.UTF_8);
        Files.write(targetVolume, oldBytes);

        byte[] tarBytes = "GOOD_TAR".getBytes(StandardCharsets.UTF_8);
        String goodSha = sha256(tarBytes);
        Path goodTar = workDir.resolve("alpine.tar.gz");
        Files.write(goodTar, tarBytes);

        UmlRootReservationRegistry registry = new UmlRootReservationRegistry();
        AtomicBoolean spawnCalled = new AtomicBoolean(false);
        UmlInstanceRootfsProvisioner provisioner = new UmlInstanceRootfsProvisioner(
                (spec, cols, rows) -> {
                    spawnCalled.set(true);
                    return new FakeProvisionProcess(0, "", false, true);
                },
                registry,
                noopToolchainStager(),
                path -> 4L * 1024L * 1024L * 1024L,
                FIXED_CLOCK);

        // 1. Partial download (.part input name or wrong byte size)
        Path partTar = workDir.resolve("alpine.tar.gz.part");
        Files.write(partTar, tarBytes);
        assertReason(
                UmlInstanceRootfsProvisioner.REASON_PARTIAL_INPUT,
                () -> provisioner.provision(baseRequest(
                        "inst-2", "g1", targetVolume, partTar, goodSha, tarBytes.length,
                        null, null, runtimeDir, UmlInstanceRootfsProvisioner.ProvisionMode.REPLACE_EXPLICIT, "mig")));

        Path truncatedTar = workDir.resolve("truncated.tar.gz");
        Files.write(truncatedTar, "GOOD".getBytes(StandardCharsets.UTF_8));
        assertReason(
                UmlInstanceRootfsProvisioner.REASON_PARTIAL_INPUT,
                () -> provisioner.provision(baseRequest(
                        "inst-2", "g1", targetVolume, truncatedTar, goodSha, tarBytes.length,
                        null, null, runtimeDir, UmlInstanceRootfsProvisioner.ProvisionMode.REPLACE_EXPLICIT, "mig")));

        // 2. Checksum mismatch
        Path wrongTar = workDir.resolve("corrupt.tar.gz");
        Files.write(wrongTar, "BAD_TAR!".getBytes(StandardCharsets.UTF_8));
        assertReason(
                UmlInstanceRootfsProvisioner.REASON_CHECKSUM_MISMATCH,
                () -> provisioner.provision(baseRequest(
                        "inst-2", "g1", targetVolume, wrongTar, goodSha, tarBytes.length,
                        null, null, runtimeDir, UmlInstanceRootfsProvisioner.ProvisionMode.REPLACE_EXPLICIT, "mig")));

        // 3. Invalid target path (.part suffix)
        assertReason(
                UmlInstanceRootfsProvisioner.REASON_INVALID_PATH,
                () -> provisioner.provision(baseRequest(
                        "inst-2", "g1", volumesDir.resolve("bad.part"), goodTar, goodSha, tarBytes.length,
                        null, null, runtimeDir, UmlInstanceRootfsProvisioner.ProvisionMode.CREATE_NEW, null)));

        // 4. Unverified hostfs seed sidecar
        Path seedDir = Files.createDirectories(workDir.resolve("seed-tree"));
        Path sidecar = workDir.resolve("seed-tree.verified");
        Files.writeString(sidecar, "pin=" + "0".repeat(64) + "\n");
        assertReason(
                UmlInstanceRootfsProvisioner.REASON_SEED_UNVERIFIED,
                () -> provisioner.provision(baseRequest(
                        "inst-2", "g1", targetVolume, goodTar, goodSha, tarBytes.length,
                        seedDir, sidecar, runtimeDir, UmlInstanceRootfsProvisioner.ProvisionMode.REPLACE_EXPLICIT, "mig")));

        // 5. Refused distros (Debian 13 & Arch)
        assertReason(
                UmlInstanceRootfsProvisioner.REASON_DISTRO_REFUSED,
                () -> provisioner.provision(new UmlInstanceRootfsProvisioner.ProvisionRequest(
                        "inst-2",
                        "g1",
                        volumesDir.resolve("deb.raw"),
                        StorageContract.MIN_ROOT_VOLUME_BYTES,
                        "deb",
                        new UmlInstanceRootfsProvisioner.SeedInput(
                                "debian", "13", goodTar, null, null, goodSha, tarBytes.length),
                        UmlInstanceRootfsProvisioner.ProvisionMode.CREATE_NEW,
                        null,
                        "/k",
                        "/s",
                        runtimeDir,
                        new Object(),
                        (i, g) -> true,
                        null,
                        5_000L,
                        500L,
                        500L,
                        500L)));

        assertFalse(spawnCalled.get());
        assertArrayEquals(oldBytes, Files.readAllBytes(targetVolume));
    }

    @Test
    public void enospcAndKilledGuestPreserveExistingDiskAndCleanUpTempFiles() throws Exception {
        Path workDir = tempFolder.newFolder("failures").toPath();
        Path volumesDir = Files.createDirectories(workDir.resolve("root-volumes"));
        Path runtimeDir = Files.createDirectories(workDir.resolve("runtime"));
        Path targetVolume = volumesDir.resolve("inst-3.raw");
        byte[] oldVolumeBytes = new byte[4096];
        oldVolumeBytes[0x438] = 0x53;
        oldVolumeBytes[0x439] = (byte) 0xEF;
        oldVolumeBytes[2048] = 42;
        Files.write(targetVolume, oldVolumeBytes);
        String oldSha = sha256(oldVolumeBytes);

        byte[] tarBytes = "SEED_TAR".getBytes(StandardCharsets.UTF_8);
        Path tar = workDir.resolve("alpine.tar.gz");
        Files.write(tar, tarBytes);
        String tarSha = sha256(tarBytes);

        UmlRootReservationRegistry registry = new UmlRootReservationRegistry();

        // ENOSPC via StorageSpaceChecker
        UmlInstanceRootfsProvisioner lowSpaceProvisioner = new UmlInstanceRootfsProvisioner(
                (spec, cols, rows) -> new FakeProvisionProcess(0, "", false, true),
                registry,
                noopToolchainStager(),
                path -> 1024L,
                FIXED_CLOCK);
        assertThrows(
                StorageContract.InsufficientStorageException.class,
                () -> lowSpaceProvisioner.provision(baseRequest(
                        "inst-3", "g1", targetVolume, tar, tarSha, tarBytes.length,
                        null, null, runtimeDir, UmlInstanceRootfsProvisioner.ProvisionMode.REPLACE_EXPLICIT, "upgrade")));
        assertEquals(oldSha, sha256(Files.readAllBytes(targetVolume)));
        assertFalse(registry.isReserved(targetVolume));

        // Killed / failed guest during explicit migration
        UmlInstanceRootfsProvisioner killedProvisioner = new UmlInstanceRootfsProvisioner(
                (spec, cols, rows) -> {
                    Files.write(spec.ubd0Path(), new byte[] {1, 2, 3, 4});
                    return new FakeProvisionProcess(137, "Killed\nORCHESTELLA_UML_PROVISION_FAIL step=mkfs rc=137\n", false, true);
                },
                registry,
                noopToolchainStager(),
                path -> 4L * 1024L * 1024L * 1024L,
                FIXED_CLOCK);
        assertReason(
                UmlInstanceRootfsProvisioner.REASON_GUEST_FAILED,
                () -> killedProvisioner.provision(baseRequest(
                        "inst-3", "g2", targetVolume, tar, tarSha, tarBytes.length,
                        null, null, runtimeDir, UmlInstanceRootfsProvisioner.ProvisionMode.REPLACE_EXPLICIT, "upgrade")));
        assertEquals(oldSha, sha256(Files.readAllBytes(targetVolume)));
        assertFalse(registry.isReserved(targetVolume));
        try (var list = Files.list(volumesDir)) {
            assertEquals(List.of("inst-3.raw"), list.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    public void unconfirmedGuestStopRetainsReservationAndBlocksRelaunch() throws Exception {
        Path workDir = tempFolder.newFolder("unconfirmed").toPath();
        Path volumesDir = Files.createDirectories(workDir.resolve("root-volumes"));
        Path runtimeDir = Files.createDirectories(workDir.resolve("runtime"));
        Path targetVolume = volumesDir.resolve("inst-4.raw");
        byte[] tarBytes = "SEED_TAR".getBytes(StandardCharsets.UTF_8);
        Path tar = workDir.resolve("alpine.tar.gz");
        Files.write(tar, tarBytes);
        String tarSha = sha256(tarBytes);

        UmlRootReservationRegistry registry = new UmlRootReservationRegistry();
        UmlInstanceRootfsProvisioner hungProvisioner = new UmlInstanceRootfsProvisioner(
                (spec, cols, rows) -> new FakeProvisionProcess(0, "booting...\n", true, false),
                registry,
                noopToolchainStager(),
                path -> 4L * 1024L * 1024L * 1024L,
                FIXED_CLOCK);

        assertReason(
                UmlInstanceRootfsProvisioner.REASON_GUEST_STOP_UNCONFIRMED,
                () -> hungProvisioner.provision(new UmlInstanceRootfsProvisioner.ProvisionRequest(
                        "inst-4",
                        "g1",
                        targetVolume,
                        StorageContract.MIN_ROOT_VOLUME_BYTES,
                        "qa",
                        new UmlInstanceRootfsProvisioner.SeedInput(
                                "alpine", "3.22.2", tar, null, null, tarSha, tarBytes.length),
                        UmlInstanceRootfsProvisioner.ProvisionMode.CREATE_NEW,
                        null,
                        "/k",
                        "/s",
                        runtimeDir,
                        new Object(),
                        (i, g) -> true,
                        null,
                        50L,
                        10L,
                        10L,
                        200L)));

        assertTrue(registry.isReserved(targetVolume));
        assertFalse(Files.exists(targetVolume));
        assertThrows(
                UmlRootReservationRegistry.RootVolumeBusyException.class,
                () -> hungProvisioner.provision(baseRequest(
                        "inst-4", "g2", targetVolume, tar, tarSha, tarBytes.length,
                        null, null, runtimeDir, UmlInstanceRootfsProvisioner.ProvisionMode.CREATE_NEW, null)));
    }

    @Test
    public void lateGenerationAndDuplicateCreateFailClosedWithoutOverwritingPublishedDisk()
            throws Exception {
        Path workDir = tempFolder.newFolder("late-dup").toPath();
        Path volumesDir = Files.createDirectories(workDir.resolve("root-volumes"));
        Path runtimeDir = Files.createDirectories(workDir.resolve("runtime"));
        Path targetVolume = volumesDir.resolve("inst-5.raw");
        byte[] tarBytes = "SEED_TAR".getBytes(StandardCharsets.UTF_8);
        Path tar = workDir.resolve("alpine.tar.gz");
        Files.write(tar, tarBytes);
        String tarSha = sha256(tarBytes);

        UmlRootReservationRegistry registry = new UmlRootReservationRegistry();
        AtomicBoolean stillCurrent = new AtomicBoolean(true);
        UmlInstanceRootfsProvisioner lateProvisioner = new UmlInstanceRootfsProvisioner(
                (spec, cols, rows) -> {
                    writeValidExt4Magic(spec.ubd0Path(), StorageContract.MIN_ROOT_VOLUME_BYTES);
                    stillCurrent.set(false);
                    String okLine = extractSuccessTokenFromScript(
                            Files.readString(spec.ephemeralRootDir().resolve("um-command.sh")));
                    return new FakeProvisionProcess(0, okLine + "\n", false, true);
                },
                registry,
                noopToolchainStager(),
                path -> 4L * 1024L * 1024L * 1024L,
                FIXED_CLOCK);

        assertReason(
                UmlInstanceRootfsProvisioner.REASON_STALE_GENERATION,
                () -> lateProvisioner.provision(new UmlInstanceRootfsProvisioner.ProvisionRequest(
                        "inst-5",
                        "stale-gen",
                        targetVolume,
                        StorageContract.MIN_ROOT_VOLUME_BYTES,
                        "qa",
                        new UmlInstanceRootfsProvisioner.SeedInput(
                                "alpine", "3.22.2", tar, null, null, tarSha, tarBytes.length),
                        UmlInstanceRootfsProvisioner.ProvisionMode.CREATE_NEW,
                        null,
                        "/k",
                        "/s",
                        runtimeDir,
                        new Object(),
                        (i, g) -> stillCurrent.get(),
                        null,
                        5_000L,
                        500L,
                        500L,
                        500L)));
        assertFalse(Files.exists(targetVolume));

        // Duplicate create on existing EXT4_READY volume fails closed under CREATE_NEW.
        writeValidExt4Magic(targetVolume, 4096L);
        String existingSha = sha256(Files.readAllBytes(targetVolume));
        assertReason(
                UmlInstanceRootfsProvisioner.REASON_EXISTING_VOLUME_REQUIRES_EXPLICIT_MIGRATION,
                () -> lateProvisioner.provision(baseRequest(
                        "inst-5", "g2", targetVolume, tar, tarSha, tarBytes.length,
                        null, null, runtimeDir, UmlInstanceRootfsProvisioner.ProvisionMode.CREATE_NEW, null)));
        assertEquals(existingSha, sha256(Files.readAllBytes(targetVolume)));
    }

    @Test
    public void posixPermissionsAndSymlinksOnStagingArePreservedForLinuxHosts() throws Exception {
        Path probe = tempFolder.newFolder("posix-probe").toPath();
        assumeTrue(
                "POSIX permissions require Linux/CI host",
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

        Path tar = probe.resolve("alpine.tar.gz");
        byte[] tarBytes = "POSIX_TAR".getBytes(StandardCharsets.UTF_8);
        Files.write(tar, tarBytes);
        String tarSha = sha256(tarBytes);

        // Symlinked target path must be rejected fail-closed.
        Path realFile = Files.createFile(probe.resolve("real.raw"));
        Path symlinkTarget = probe.resolve("link.raw");
        Files.createSymbolicLink(symlinkTarget, realFile);

        UmlInstanceRootfsProvisioner provisioner = new UmlInstanceRootfsProvisioner(
                (spec, cols, rows) -> new FakeProvisionProcess(0, "", false, true),
                new UmlRootReservationRegistry(),
                noopToolchainStager(),
                path -> 4L * 1024L * 1024L * 1024L,
                FIXED_CLOCK);

        assertReason(
                UmlInstanceRootfsProvisioner.REASON_INVALID_PATH,
                () -> provisioner.provision(baseRequest(
                        "inst-posix", "g1", symlinkTarget, tar, tarSha, tarBytes.length,
                        null, null, probe.resolve("rt"), UmlInstanceRootfsProvisioner.ProvisionMode.CREATE_NEW, null)));

        // Staging um-command.sh carries 0755 on POSIX host.
        AtomicReference<Set<PosixFilePermission>> stagedPerms = new AtomicReference<>();
        UmlInstanceRootfsProvisioner okProvisioner = new UmlInstanceRootfsProvisioner(
                (spec, cols, rows) -> {
                    try {
                        stagedPerms.set(Files.getPosixFilePermissions(
                                spec.ephemeralRootDir().resolve("um-command.sh")));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    writeValidExt4Magic(spec.ubd0Path(), StorageContract.MIN_ROOT_VOLUME_BYTES);
                    String okLine;
                    try {
                        okLine = extractSuccessTokenFromScript(
                                Files.readString(spec.ephemeralRootDir().resolve("um-command.sh")));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    return new FakeProvisionProcess(0, okLine + "\n", false, true);
                },
                new UmlRootReservationRegistry(),
                noopToolchainStager(),
                path -> 4L * 1024L * 1024L * 1024L,
                FIXED_CLOCK);
        okProvisioner.provision(baseRequest(
                "inst-posix", "g2", probe.resolve("out.raw"), tar, tarSha, tarBytes.length,
                null, null, probe.resolve("rt"), UmlInstanceRootfsProvisioner.ProvisionMode.CREATE_NEW, null));
        assertNotNull(stagedPerms.get());
        assertTrue(stagedPerms.get().contains(PosixFilePermission.OWNER_EXECUTE));
        assertTrue(stagedPerms.get().contains(PosixFilePermission.OTHERS_EXECUTE));
    }

    @Test
    public void explicitMigrationRestoresOriginalExt4VolumeIfPublishedCallbackFails()
            throws Exception {
        Path workDir = tempFolder.newFolder("rollback").toPath();
        Path volumesDir = Files.createDirectories(workDir.resolve("root-volumes"));
        Path runtimeDir = Files.createDirectories(workDir.resolve("runtime"));
        Path targetVolume = volumesDir.resolve("inst-rollback.raw");
        writeValidExt4Magic(targetVolume, StorageContract.MIN_ROOT_VOLUME_BYTES);
        try (var ch = Files.newByteChannel(
                targetVolume, java.nio.file.StandardOpenOption.WRITE)) {
            ch.position(0x500);
            ch.write(java.nio.ByteBuffer.wrap("ORIGINAL_USER_MARKER".getBytes(StandardCharsets.UTF_8)));
        }
        String originalSha = sha256File(targetVolume);

        byte[] tarBytes = "REPLACEMENT_TAR_BYTES".getBytes(StandardCharsets.UTF_8);
        Path tar = workDir.resolve("alpine.tar.gz");
        Files.write(tar, tarBytes);
        String tarSha = sha256(tarBytes);

        UmlRootReservationRegistry registry = new UmlRootReservationRegistry();
        UmlInstanceRootfsProvisioner provisioner = new UmlInstanceRootfsProvisioner(
                (spec, cols, rows) -> {
                    writeValidExt4Magic(spec.ubd0Path(), StorageContract.MIN_ROOT_VOLUME_BYTES);
                    String okLine;
                    try {
                        okLine = extractSuccessTokenFromScript(
                                Files.readString(spec.ephemeralRootDir().resolve("um-command.sh")));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    return new FakeProvisionProcess(0, okLine + "\n", false, true);
                },
                registry,
                noopToolchainStager(),
                path -> 4L * 1024L * 1024L * 1024L,
                FIXED_CLOCK);

        UmlInstanceRootfsProvisioner.ProvisionRequest failingPublishRequest =
                new UmlInstanceRootfsProvisioner.ProvisionRequest(
                        "inst-rollback",
                        "gen-rb-1",
                        targetVolume,
                        StorageContract.MIN_ROOT_VOLUME_BYTES,
                        "qa-uml-alpine",
                        new UmlInstanceRootfsProvisioner.SeedInput(
                                "alpine", "3.22.2", tar, null, null, tarSha, tarBytes.length),
                        UmlInstanceRootfsProvisioner.ProvisionMode.REPLACE_EXPLICIT,
                        "explicit-rollback-test",
                        "/k",
                        "/s",
                        runtimeDir,
                        new Object(),
                        (id, gen) -> true,
                        receipt -> {
                            throw new IOException("simulated metadata write failure");
                        },
                        5_000L,
                        500L,
                        500L,
                        500L);

        assertThrows(IOException.class, () -> provisioner.provision(failingPublishRequest));
        assertEquals(originalSha, sha256File(targetVolume));
        byte[] restoredMarker = new byte["ORIGINAL_USER_MARKER".length()];
        try (var ch = Files.newByteChannel(targetVolume, java.nio.file.StandardOpenOption.READ)) {
            ch.position(0x500);
            ch.read(java.nio.ByteBuffer.wrap(restoredMarker));
        }
        assertArrayEquals("ORIGINAL_USER_MARKER".getBytes(StandardCharsets.UTF_8), restoredMarker);
        assertFalse(registry.isReserved(targetVolume));
        try (var list = Files.list(volumesDir)) {
            assertEquals(List.of("inst-rollback.raw"),
                    list.map(p -> p.getFileName().toString()).toList());
        }
    }

    private UmlInstanceRootfsProvisioner.ProvisionRequest baseRequest(
            String instanceId,
            String generation,
            Path targetVolume,
            Path tarPath,
            String tarSha,
            long tarSize,
            Path seedDir,
            Path sidecar,
            Path runtimeDir,
            UmlInstanceRootfsProvisioner.ProvisionMode mode,
            String migrationReason) {
        return new UmlInstanceRootfsProvisioner.ProvisionRequest(
                instanceId,
                generation,
                targetVolume,
                StorageContract.MIN_ROOT_VOLUME_BYTES,
                "qa-uml-alpine",
                new UmlInstanceRootfsProvisioner.SeedInput(
                        "alpine", "3.22.2", tarPath, seedDir, sidecar, tarSha, tarSize),
                mode,
                migrationReason,
                "/k",
                "/s",
                runtimeDir,
                new Object(),
                (i, g) -> true,
                null,
                5_000L,
                500L,
                500L,
                500L);
    }

    private static UmlInstanceRootfsProvisioner.ToolchainStager noopToolchainStager() {
        return stageDir -> {
            Files.createDirectories(stageDir.resolve("sbin"));
            Files.createDirectories(stageDir.resolve("etc"));
            Files.createDirectories(stageDir.resolve("bin"));
            Files.writeString(stageDir.resolve("sbin/mkfs.ext4"), "stub-mkfs");
            Files.writeString(stageDir.resolve("etc/mke2fs.conf"), "[defaults]\n");
            Files.writeString(stageDir.resolve("bin/busybox"), "stub-busybox");
        };
    }

    private static void writeValidExt4Magic(Path target, long sizeBytes) {
        try {
            try (var ch = Files.newByteChannel(
                    target,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE)) {
                ch.position(0x438);
                ch.write(java.nio.ByteBuffer.wrap(new byte[] {0x53, (byte) 0xEF}));
                ch.position(sizeBytes - 1L);
                ch.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String extractSuccessTokenFromScript(String script) {
        for (String line : script.split("\n")) {
            int idx = line.indexOf("ORCHESTELLA_UML_PROVISION_OK ");
            if (idx >= 0) {
                return line.substring(idx).replace("\\n'", "").trim();
            }
        }
        throw new AssertionError("missing success marker in provision script");
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static void assertReason(String expectedReason, ThrowingAction action) {
        UmlInstanceRootfsProvisioner.ProvisionException failure =
                assertThrows(UmlInstanceRootfsProvisioner.ProvisionException.class, action::run);
        assertEquals(expectedReason, failure.reason());
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static String sha256File(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(path)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static final class FakeProvisionProcess implements UmlProcessHandle {
        private final int exitCode;
        private final byte[] consoleBytes;
        private final boolean refuseDestroy;
        private volatile boolean alive;

        FakeProvisionProcess(int exitCode, String consoleOutput, boolean initiallyAlive, boolean allowDestroy) {
            this.exitCode = exitCode;
            this.consoleBytes = consoleOutput.getBytes(StandardCharsets.UTF_8);
            this.alive = initiallyAlive;
            this.refuseDestroy = !allowDestroy;
        }

        @Override public long pid() { return 4242L; }
        @Override public boolean isAlive() { return alive; }
        @Override public int waitFor() { return exitCode; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return !alive; }
        @Override public void destroy() { if (!refuseDestroy) alive = false; }
        @Override public void destroyForcibly() { if (!refuseDestroy) alive = false; }
        @Override public OutputStream terminalInput() { return OutputStream.nullOutputStream(); }
        @Override public InputStream terminalOutput() { return new ByteArrayInputStream(consoleBytes); }
        @Override public OutputStream controlInput() { return OutputStream.nullOutputStream(); }
        @Override public InputStream controlOutput() { return InputStream.nullInputStream(); }
        @Override public boolean resizeTerminal(int columns, int rows) { return true; }
        @Override public void closeChannels() {}
    }
}
