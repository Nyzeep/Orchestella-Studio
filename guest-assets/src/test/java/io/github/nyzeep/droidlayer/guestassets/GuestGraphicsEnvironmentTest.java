package io.github.nyzeep.droidlayer.guestassets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests verifying Linux Guest graphics environment scripts,
 * Mesa virgl driver configurations, llvmpipe fallback guards,
 * and desktop packaging setups (Ticket 58).
 */
final class GuestGraphicsEnvironmentTest {

    private static Path getShellDir() {
        String projectDir = System.getProperty("guestAssetsProjectDir");
        if (projectDir != null && !projectDir.isBlank()) {
            return Path.of(projectDir).resolve("shell");
        }
        return Path.of("shell");
    }

    private static Path getScriptsDir() {
        String projectDir = System.getProperty("guestAssetsProjectDir");
        if (projectDir != null && !projectDir.isBlank()) {
            return Path.of(projectDir).resolve("scripts");
        }
        return Path.of("scripts");
    }

    private static Path getInitramfsDir() {
        String projectDir = System.getProperty("guestAssetsProjectDir");
        if (projectDir != null && !projectDir.isBlank()) {
            return Path.of(projectDir).resolve("initramfs");
        }
        return Path.of("initramfs");
    }

    private static Path getToolsGpuDir() {
        String projectDir = System.getProperty("guestAssetsProjectDir");
        if (projectDir != null && !projectDir.isBlank()) {
            return Path.of(projectDir).getParent().resolve("tools").resolve("gpu");
        }
        return Path.of("..").resolve("tools").resolve("gpu");
    }

    @Test
    void graphicsProfileScriptExistsAndHasLfLineEndingsOnly() throws IOException {
        Path graphicsScript = getShellDir().resolve("10-droidlayer-graphics.sh");
        assertTrue(Files.exists(graphicsScript), "10-droidlayer-graphics.sh must exist");
        assertTrue(Files.size(graphicsScript) > 0, "10-droidlayer-graphics.sh must not be empty");

        byte[] bytes = Files.readAllBytes(graphicsScript);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertFalse(content.contains("\r"), "10-droidlayer-graphics.sh must use LF line endings only");
        assertTrue(content.startsWith("#!/bin/sh"), "10-droidlayer-graphics.sh must start with POSIX shebang");
    }

    @Test
    void graphicsProfileExportsVirglAndVersionOverrides() throws IOException {
        Path graphicsScript = getShellDir().resolve("10-droidlayer-graphics.sh");
        String content = Files.readString(graphicsScript, StandardCharsets.UTF_8);

        assertTrue(content.contains("export GALLIUM_DRIVER=virgl"),
                "Must export GALLIUM_DRIVER=virgl for hardware 3D acceleration");
        assertTrue(content.contains("export MESA_GL_VERSION_OVERRIDE=3.2"),
                "Must export MESA_GL_VERSION_OVERRIDE=3.2");
        assertTrue(content.contains("export MESA_GLES_VERSION_OVERRIDE=3.2"),
                "Must export MESA_GLES_VERSION_OVERRIDE=3.2");
        assertTrue(content.contains("export DROIDLAYER_GRAPHICS_BACKEND=virgl"),
                "Must declare virgl graphics backend");
    }

    @Test
    void graphicsProfileProvidesSmoothLlvmpipeFallback() throws IOException {
        Path graphicsScript = getShellDir().resolve("10-droidlayer-graphics.sh");
        String content = Files.readString(graphicsScript, StandardCharsets.UTF_8);

        // Fallback to llvmpipe
        assertTrue(content.contains("export GALLIUM_DRIVER=llvmpipe"),
                "Must support fallback to llvmpipe");
        assertTrue(content.contains("export DROIDLAYER_GRAPHICS_BACKEND=llvmpipe"),
                "Must declare llvmpipe graphics backend on fallback");
        assertTrue(content.contains("unset MESA_GL_VERSION_OVERRIDE"),
                "Must unset MESA_GL_VERSION_OVERRIDE on fallback");

        // Guards
        assertTrue(content.contains("DROIDLAYER_GPU_DISABLED") || content.contains("VIRGL_FORCE_LLVMPIPE"),
                "Must support explicit disablement via environment variable");
        assertTrue(content.contains("/proc/cmdline"),
                "Must inspect kernel command line for gpu disable flags");
        assertTrue(content.contains("/dev/dri/renderD128") || content.contains("/sys/class/drm/renderD128"),
                "Must probe DRM render node for VirtIO-GPU 3D capability");
    }

    @Test
    void unifiedEnvScriptChainsGraphicsProfile() throws IOException {
        Path envScript = getShellDir().resolve("00-droidlayer-env.sh");
        String content = Files.readString(envScript, StandardCharsets.UTF_8);

        assertTrue(content.contains("10-droidlayer-graphics.sh"),
                "00-droidlayer-env.sh must wire 10-droidlayer-graphics.sh for universal shell sessions");
    }

    @Test
    void desktopSetupInstallsMesaGalliumPackagesForAlpineAndDebian() throws IOException {
        Path setupScript = getScriptsDir().resolve("droidlayer-desktop-setup.sh");
        String content = Files.readString(setupScript, StandardCharsets.UTF_8);

        // Alpine Mesa packages
        assertTrue(content.contains("mesa-dri-gallium"),
                "Alpine packages must include mesa-dri-gallium");
        assertTrue(content.contains("mesa-va-gallium"),
                "Alpine packages must include mesa-va-gallium");

        // Debian Mesa & benchmark packages
        assertTrue(content.contains("libgl1-mesa-dri"),
                "Debian packages must include libgl1-mesa-dri");
        assertTrue(content.contains("mesa-utils"),
                "Debian packages must include mesa-utils");
        assertTrue(content.contains("glmark2-es2"),
                "Debian packages must include glmark2-es2");

        // Setup deploys 10-droidlayer-graphics.sh
        assertTrue(content.contains("10-droidlayer-graphics.sh"),
                "droidlayer-desktop-setup.sh must deploy 10-droidlayer-graphics.sh");
    }

    @Test
    void desktopLaunchScriptSourcesGraphicsProfile() throws IOException {
        Path launchScript = getScriptsDir().resolve("droidlayer-desktop-launch.sh");
        String content = Files.readString(launchScript, StandardCharsets.UTF_8);

        assertTrue(content.contains("10-droidlayer-graphics.sh"),
                "droidlayer-desktop-launch.sh must source 10-droidlayer-graphics.sh");
    }

    @Test
    void initramfsInitProvisionsGraphicsProfile() throws IOException {
        Path initScript = getInitramfsDir().resolve("init");
        String content = Files.readString(initScript, StandardCharsets.UTF_8);

        assertTrue(content.contains("/etc/profile.d/10-droidlayer-graphics.sh"),
                "initramfs init must provision /etc/profile.d/10-droidlayer-graphics.sh");
        assertTrue(content.contains("GALLIUM_DRIVER=virgl"),
                "initramfs graphics profile must configure GALLIUM_DRIVER=virgl");
        assertTrue(content.contains("GALLIUM_DRIVER=llvmpipe"),
                "initramfs graphics profile must configure fallback to llvmpipe");
    }

    @Test
    void verifyVirglSmokeToolScriptExistsAndHasCorrectStructure() throws IOException {
        Path smokeScript = getToolsGpuDir().resolve("verify-virgl-smoke.sh");
        assertTrue(Files.exists(smokeScript), "tools/gpu/verify-virgl-smoke.sh must exist");
        assertTrue(Files.size(smokeScript) > 0, "verify-virgl-smoke.sh must not be empty");

        byte[] bytes = Files.readAllBytes(smokeScript);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertFalse(content.contains("\r"), "verify-virgl-smoke.sh must use LF line endings only");
        assertTrue(content.startsWith("#!/"), "verify-virgl-smoke.sh must start with shebang");

        // Probing checks
        assertTrue(content.contains("/dev/dri/card0"), "Must probe /dev/dri/card0");
        assertTrue(content.contains("/dev/dri/renderD128"), "Must probe /dev/dri/renderD128");
        assertTrue(content.contains("GALLIUM_DRIVER"), "Must inspect GALLIUM_DRIVER");
        assertTrue(content.contains("GL_RENDERER"), "Must inspect GL_RENDERER");
        assertTrue(content.contains("es2_info") || content.contains("glxinfo"),
                "Must query es2_info or glxinfo");
        assertTrue(content.contains("glmark2-es2"), "Must support glmark2-es2 benchmark execution");
        assertTrue(content.contains("--check"), "Must support --check mode");
    }
}
