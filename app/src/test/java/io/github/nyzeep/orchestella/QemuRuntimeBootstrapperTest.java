package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.runtime.engineqemu.RuntimeArtifactManifest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class QemuRuntimeBootstrapperTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void bootstrapMustReturnTheInstanceRootVolumeThatExistsBeforeQemuExec() throws Exception {
        Path work = temporaryFolder.newFolder("bootstrap").toPath();
        Path runtimeDir = Files.createDirectories(work.resolve("runtime"));
        Path distroInputs = Files.createDirectories(work.resolve("distro-inputs"));
        byte[] kernel = synthetic(4096, 0x5A);
        byte[] initramfs = synthetic(2048, 0xA5);
        byte[] tarball = synthetic(1024, 0x11);
        Files.write(distroInputs.resolve("rootfs.tar.gz"), tarball);
        Path rootVolume = work.resolve("root-volumes").resolve("instance-1.raw");
        Files.createDirectories(rootVolume.getParent());
        Files.write(rootVolume, new byte[64]);

        QemuRuntimeBootstrapper bootstrapper = new QemuRuntimeBootstrapper(
                manifest(kernel, initramfs),
                name -> new ByteArrayInputStream("Image".equals(name) ? kernel : initramfs),
                name -> new ByteArrayInputStream(initramfs),
                runtimeDir,
                distroInputs);

        QemuRuntimeBootstrapper.RuntimePaths paths =
                bootstrapper.bootstrap("rootfs.tar.gz", TOKEN, rootVolume);

        assertEquals(
                "launch must use the instance root volume", rootVolume, paths.rootVolume);
        assertTrue(
                "root volume path must exist before QEMU exec",
                Files.isRegularFile(paths.rootVolume));
    }

    @Test
    public void bootstrapRejectsAMissingInstanceRootVolumeBeforeQemuExec() throws Exception {
        Path work = temporaryFolder.newFolder("bootstrap-missing").toPath();
        Path runtimeDir = Files.createDirectories(work.resolve("runtime"));
        Path distroInputs = Files.createDirectories(work.resolve("distro-inputs"));
        byte[] kernel = synthetic(4096, 0x5A);
        byte[] initramfs = synthetic(2048, 0xA5);
        Files.write(distroInputs.resolve("rootfs.tar.gz"), synthetic(1024, 0x11));
        Path missingRootVolume = work.resolve("root-volumes").resolve("missing.raw");

        QemuRuntimeBootstrapper bootstrapper = new QemuRuntimeBootstrapper(
                manifest(kernel, initramfs),
                name -> new ByteArrayInputStream("Image".equals(name) ? kernel : initramfs),
                name -> new ByteArrayInputStream(initramfs),
                runtimeDir,
                distroInputs);

        try {
            bootstrapper.bootstrap("rootfs.tar.gz", TOKEN, missingRootVolume);
            fail("missing instance root volume must be rejected before QEMU exec");
        } catch (IOException expected) {
            assertEquals("root-volume-missing", expected.getMessage());
        }
    }

    @Test
    public void bootstrapSupportsGuestPassword() throws Exception {
        Path work = temporaryFolder.newFolder("bootstrap-pass").toPath();
        Path runtimeDir = Files.createDirectories(work.resolve("runtime"));
        Path distroInputs = Files.createDirectories(work.resolve("distro-inputs"));
        byte[] kernel = synthetic(4096, 0x5A);
        byte[] initramfs = synthetic(2048, 0xA5);
        byte[] tarball = synthetic(1024, 0x11);
        Files.write(distroInputs.resolve("rootfs.tar.gz"), tarball);
        Path rootVolume = work.resolve("root-volumes").resolve("instance-pass.raw");
        Files.createDirectories(rootVolume.getParent());
        Files.write(rootVolume, new byte[64]);

        QemuRuntimeBootstrapper bootstrapper = new QemuRuntimeBootstrapper(
                manifest(kernel, initramfs),
                name -> new ByteArrayInputStream("Image".equals(name) ? kernel : initramfs),
                name -> new ByteArrayInputStream(initramfs),
                runtimeDir,
                distroInputs);

        QemuRuntimeBootstrapper.RuntimePaths paths =
                bootstrapper.bootstrap("rootfs.tar.gz", TOKEN, "SecretPass123", rootVolume);

        assertEquals(
                "launch must use the instance root volume", rootVolume, paths.rootVolume);
        assertTrue(
                "embedded initramfs exists",
                Files.isRegularFile(paths.initramfs));
    }

    private static RuntimeArtifactManifest manifest(byte[] kernel, byte[] initramfs) throws Exception {
        return RuntimeArtifactManifest.parse(new ByteArrayInputStream(
                (entry("Image", kernel)
                                + entry("initramfs.cpio.gz", initramfs)
                                + entry("lib/arm64-v8a/libdroidlayer_qemu.so", synthetic(128, 0x00))
                                + entry("QEMU-BUILD-INPUTS", synthetic(32, 0x01))
                                + entry("QEMU-GPL-SOURCES.md", synthetic(32, 0x02))
                                + entry("LICENSE.GPL-2.0", synthetic(32, 0x03))
                                + entry("NOTICE.QEMU", synthetic(32, 0x04)))
                        .getBytes(StandardCharsets.US_ASCII)));
    }

    private static String entry(String path, byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        return hex + "  " + path + "\n";
    }

    private static byte[] synthetic(int size, int seed) {
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            out[i] = (byte) (seed + i * 31);
        }
        return out;
    }
}
