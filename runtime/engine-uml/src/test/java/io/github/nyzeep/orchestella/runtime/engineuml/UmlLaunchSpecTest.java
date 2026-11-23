package io.github.nyzeep.orchestella.runtime.engineuml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class UmlLaunchSpecTest {

    @Test
    public void buildsExpectedArgvAndEnvironmentWithoutSecrets() {
        Path runtimeDir = Paths.get("/data/user/0/app/files/runtime/qa-1");
        Path rootDir = runtimeDir.resolve("ephemeral-rootfs-gen1");
        UmlLaunchSpec spec = UmlLaunchSpec.builder()
                .kernelPath("/data/app/lib/arm64/libuml_kernel.so")
                .stubExePath("/data/app/lib/arm64/libuml_stub.so")
                .memMb(512)
                .ephemeralRootDir(rootDir)
                .runtimeDir(runtimeDir)
                .extraArgs(List.of("hostname=uml-qa"))
                .build();

        assertEquals(
                List.of(
                        "/data/app/lib/arm64/libuml_kernel.so",
                        "mem=512M",
                        "rw",
                        "loglevel=3",
                        "con=null",
                        "ssl=null",
                        "con0=fd:0,fd:1",
                        "ssl0=fd:3,fd:4",
                        "seccomp=on",
                        "panic=1",
                        "stub_exe=/data/app/lib/arm64/libuml_stub.so",
                        "umroot=" + rootDir.toAbsolutePath(),
                        "umrootfstype=hostfs",
                        "um.standalone=1",
                        "hostname=uml-qa"),
                spec.argv());

        Map<String, String> env = spec.environment();
        assertEquals(runtimeDir.resolve("tmp").toAbsolutePath().toString(), env.get("TMPDIR"));
        assertEquals(runtimeDir.toAbsolutePath().toString(), env.get("HOME"));
        assertEquals("/system/bin:/system/xbin:/vendor/bin", env.get("PATH"));
        assertFalse(env.containsKey("BRIDGE_TOKEN"));
        assertTrue(spec.argv().stream().noneMatch(arg -> arg.contains("token")));
        assertEquals(UmlLaunchSpec.RootMode.HOSTFS, spec.rootMode());
    }

    @Test
    public void buildsExt4UbdArgvWithCredsHostfsDirAndWithoutEphemeralRoot() {
        Path runtimeDir = Paths.get("/data/user/0/app/files/runtime/qa-2");
        Path rootVolume = Paths.get("/data/user/0/app/files/root-volumes/inst-2.raw");
        Path credsDir = runtimeDir.resolve("boot-creds-gen2");
        UmlLaunchSpec spec = UmlLaunchSpec.builder()
                .kernelPath("/data/app/lib/arm64/libuml_kernel.so")
                .stubExePath("/data/app/lib/arm64/libuml_stub.so")
                .memMb(512)
                .rootMode(UmlLaunchSpec.RootMode.EXT4_UBD)
                .ubd0Path(rootVolume)
                .credsHostfsDir(credsDir)
                .runtimeDir(runtimeDir)
                .extraArgs(List.of("console=tty0"))
                .build();

        assertEquals(UmlLaunchSpec.RootMode.EXT4_UBD, spec.rootMode());
        assertEquals(rootVolume, spec.ubd0Path());
        assertEquals(credsDir, spec.credsHostfsDir());
        assertEquals(
                List.of(
                        "/data/app/lib/arm64/libuml_kernel.so",
                        "mem=512M",
                        "rw",
                        "loglevel=3",
                        "con=null",
                        "ssl=null",
                        "con0=fd:0,fd:1",
                        "ssl0=fd:3,fd:4",
                        "seccomp=on",
                        "panic=1",
                        "stub_exe=/data/app/lib/arm64/libuml_stub.so",
                        "ubd0=" + rootVolume.toAbsolutePath(),
                        "umroot=/dev/ubda",
                        "umrootfstype=ext4",
                        "um.creds=" + credsDir.toAbsolutePath(),
                        "um.standalone=1",
                        "console=tty0"),
                spec.argv());
    }

    @Test
    public void buildsHostfsProvisionSpecWithAttachedUbd0() {
        Path runtimeDir = Paths.get("/data/user/0/app/files/runtime/qa-3");
        Path stageDir = runtimeDir.resolve("uml-provision-stage-g1");
        Path tempVolume = Paths.get("/data/user/0/app/files/root-volumes/inst-3.raw.provision-g1.part");
        UmlLaunchSpec spec = UmlLaunchSpec.builder()
                .kernelPath("/k")
                .stubExePath("/s")
                .rootMode(UmlLaunchSpec.RootMode.HOSTFS)
                .ephemeralRootDir(stageDir)
                .ubd0Path(tempVolume)
                .runtimeDir(runtimeDir)
                .build();

        assertTrue(spec.argv().contains("ubd0=" + tempVolume.toAbsolutePath()));
        assertTrue(spec.argv().contains("umroot=" + stageDir.toAbsolutePath()));
        assertTrue(spec.argv().contains("umrootfstype=hostfs"));
    }

    @Test
    public void rejectsInvalidRootModeCombinations() {
        Path dir = Paths.get("/tmp/uml-spec");
        Path vol = Paths.get("/tmp/uml-spec/root.raw");

        try {
            UmlLaunchSpec.builder()
                    .kernelPath("/k")
                    .stubExePath("/s")
                    .rootMode(UmlLaunchSpec.RootMode.EXT4_UBD)
                    .runtimeDir(dir)
                    .build();
            fail("expected EXT4_UBD without ubd0Path rejection");
        } catch (IllegalArgumentException | NullPointerException expected) {
            assertTrue(expected.getMessage().contains("ubd0"));
        }

        try {
            UmlLaunchSpec.builder()
                    .kernelPath("/k")
                    .stubExePath("/s")
                    .rootMode(UmlLaunchSpec.RootMode.EXT4_UBD)
                    .ephemeralRootDir(dir)
                    .ubd0Path(vol)
                    .runtimeDir(dir)
                    .build();
            fail("expected EXT4_UBD with ephemeralRootDir rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("ephemeralRootDir"));
        }
    }

    @Test
    public void rejectsInvalidMemoryAndControlFdRanges() {
        Path dir = Paths.get("/tmp/uml-spec");
        try {
            UmlLaunchSpec.builder()
                    .kernelPath("/k")
                    .stubExePath("/s")
                    .memMb(128)
                    .ephemeralRootDir(dir)
                    .runtimeDir(dir)
                    .build();
            fail("expected low memory rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("256"));
        }

        try {
            UmlLaunchSpec.builder()
                    .kernelPath("/k")
                    .stubExePath("/s")
                    .ephemeralRootDir(dir)
                    .runtimeDir(dir)
                    .controlInFd(1)
                    .controlOutFd(4)
                    .build();
            fail("expected stdio collision rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("control"));
        }

        try {
            UmlLaunchSpec.builder()
                    .kernelPath("/k")
                    .stubExePath("/s")
                    .ephemeralRootDir(dir)
                    .runtimeDir(dir)
                    .controlInFd(3)
                    .controlOutFd(3)
                    .build();
            fail("expected identical control fd rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("distinct"));
        }
    }
}
