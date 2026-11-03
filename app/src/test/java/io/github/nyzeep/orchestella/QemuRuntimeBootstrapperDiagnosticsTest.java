package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.runtime.distro.DistroManifest;
import io.github.nyzeep.orchestella.runtime.engineqemu.RuntimeArtifactManifest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Diagnostics contract for {@code QemuRuntimeBootstrapper}'s {@code proxy_relay} payload.
 *
 * <p>An absent payload stays tolerated (it is recorded as the {@code proxy-relay-absent}
 * degradation so a guest booting without its outbound proxy is traceable), while an unreadable
 * or oversized payload aborts the launch with {@code proxy-relay-unavailable} exactly like the
 * {@code guest-init-unavailable} path.
 */
public final class QemuRuntimeBootstrapperDiagnosticsTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String ROOTFS_NAME = "rootfs.tar.gz";
    private static final byte[] GUEST_INIT = "#!/bin/sh\ntrue\n".getBytes(StandardCharsets.US_ASCII);

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void unreadableProxyRelayFailsBootstrapWithProxyRelayUnavailable() throws Exception {
        Fixture fixture = new Fixture();
        fixture.bootstrapper = fixture.bootstrapper(List.of(fixture.distro), name -> {
            if ("proxy_relay".equals(name)) {
                // Mirrors production PackagedAssetStreams: an IOException is wrapped so the
                // function stays checked-exception free.
                throw new RuntimeException(new IOException("zip entry unreadable"));
            }
            return fixture.resource(name);
        }, note -> {});
        try {
            fixture.bootstrapper.bootstrap(ROOTFS_NAME, TOKEN, fixture.rootVolume);
            fail("an unreadable proxy_relay payload must not produce a launchable runtime");
        } catch (IOException expected) {
            assertTrue("message names the failure: " + expected.getMessage(),
                    expected.getMessage().contains("proxy-relay-unavailable"));
            assertTrue("cause chain keeps the packaging failure",
                    expected.getCause() instanceof RuntimeException);
        }
        assertFalse("a failed bootstrap must not publish an initramfs",
                Files.exists(fixture.runtimeDir.resolve("initramfs-embedded.cpio.gz")));
    }

    @Test
    public void absentProxyRelayIsRecordedAsDegradationAndBootstrapSucceeds() throws Exception {
        Fixture fixture = new Fixture();
        List<String> degradations = new ArrayList<>();
        Consumer<String> sink = degradations::add;
        fixture.bootstrapper = fixture.bootstrapper(List.of(fixture.distro),
                name -> "proxy_relay".equals(name) ? null : fixture.resource(name), sink);

        QemuRuntimeBootstrapper.RuntimePaths paths =
                fixture.bootstrapper.bootstrap(ROOTFS_NAME, TOKEN, fixture.rootVolume);

        assertTrue(Files.isRegularFile(paths.initramfs));
        assertTrue("absent payload must be reported: " + degradations,
                degradations.contains("proxy-relay-absent"));
        assertEquals("exactly one degradation for this fixture", 1, degradations.size());
    }

    private final class Fixture {
        final Path work = temporaryFolder.newFolder().toPath();
        final Path runtimeDir = Files.createDirectories(work.resolve("runtime"));
        final Path distroInputs = Files.createDirectories(work.resolve("distro-inputs"));
        final Path rootVolume = work.resolve("instance.raw");
        final byte[] kernel = synthetic(4096, 0x5a);
        final byte[] baseInitramfs = synthetic(2048, 0xa5);
        final byte[] tarball = synthetic(1024, 0x11);
        final DistroManifest distro = new DistroManifest("synthetic", "1", "aarch64",
                "https://example.invalid/" + ROOTFS_NAME, ROOTFS_NAME, sha256(tarball), tarball.length);
        QemuRuntimeBootstrapper bootstrapper;

        Fixture() throws Exception {
            Files.write(distroInputs.resolve(ROOTFS_NAME), tarball);
            Files.write(rootVolume, new byte[64]);
            bootstrapper = bootstrapper(List.of(distro), this::resource, note -> {});
        }

        ByteArrayInputStream resource(String name) {
            if ("guest_init".equals(name)) {
                return new ByteArrayInputStream(GUEST_INIT);
            }
            return "initramfs.cpio.gz".equals(name) ? new ByteArrayInputStream(baseInitramfs) : null;
        }

        QemuRuntimeBootstrapper bootstrapper(List<DistroManifest> distros,
                Function<String, InputStream> resources, Consumer<String> degradations)
                throws Exception {
            return new QemuRuntimeBootstrapper(manifest(kernel, baseInitramfs),
                    name -> new ByteArrayInputStream(kernel), resources, runtimeDir, distroInputs,
                    distros, Files::newInputStream, degradations);
        }
    }

    private static RuntimeArtifactManifest manifest(byte[] kernel, byte[] initramfs) throws Exception {
        return RuntimeArtifactManifest.parse(new ByteArrayInputStream(
                (entry("Image", kernel) + entry("initramfs.cpio.gz", initramfs)
                        + entry("lib/arm64-v8a/libdroidlayer_qemu.so", synthetic(128, 0x00))
                        + entry("QEMU-BUILD-INPUTS", synthetic(32, 0x01))
                        + entry("QEMU-GPL-SOURCES.md", synthetic(32, 0x02))
                        + entry("LICENSE.GPL-2.0", synthetic(32, 0x03))
                        + entry("NOTICE.QEMU", synthetic(32, 0x04))).getBytes(StandardCharsets.US_ASCII)));
    }

    private static String entry(String path, byte[] bytes) throws Exception {
        return sha256(bytes) + "  " + path + "\n";
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return hex.toString();
    }

    private static byte[] synthetic(int size, int seed) {
        byte[] output = new byte[size];
        for (int i = 0; i < size; i++) {
            output[i] = (byte) (seed + i * 31);
        }
        return output;
    }
}
