package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Verifies that Guest graphics environment scripts and automated verification tools
 * (Ticket 58) are packaged into Android application assets and toolchains with Unix LF
 * line endings and matching source content verbatim.
 */
public final class GuestGraphicsConfigPackagingTest {

    private static Path getAssetsShellDir() {
        Path direct = Paths.get("src", "main", "assets", "shell");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path fromRoot = Paths.get("app", "src", "main", "assets", "shell");
        if (Files.isDirectory(fromRoot)) {
            return fromRoot;
        }
        return direct;
    }

    private static Path getGuestAssetsShellDir() {
        Path direct = Paths.get("guest-assets", "shell");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path parent = Paths.get("..", "guest-assets", "shell");
        if (Files.isDirectory(parent)) {
            return parent;
        }
        return direct;
    }

    private static Path getToolsGpuDir() {
        Path direct = Paths.get("tools", "gpu");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path parent = Paths.get("..", "tools", "gpu");
        if (Files.isDirectory(parent)) {
            return parent;
        }
        return direct;
    }

    @Test
    public void packagedGraphicsScriptAssetExistsAndUsesLfLineEndingsOnly() throws IOException {
        Path graphicsAsset = getAssetsShellDir().resolve("10-orchestella-graphics.sh");
        assertTrue("10-orchestella-graphics.sh must exist in app assets", Files.exists(graphicsAsset));
        assertTrue("10-orchestella-graphics.sh must not be empty", Files.size(graphicsAsset) > 0);

        byte[] bytes = Files.readAllBytes(graphicsAsset);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertFalse("Graphics asset must not contain CR line endings", content.contains("\r"));
        assertTrue("Graphics asset must start with POSIX shebang", content.startsWith("#!/bin/sh"));
    }

    @Test
    public void packagedGraphicsScriptConfiguresVirgl3DAcceleration() throws IOException {
        Path graphicsAsset = getAssetsShellDir().resolve("10-orchestella-graphics.sh");
        byte[] bytes = Files.readAllBytes(graphicsAsset);
        String content = new String(bytes, StandardCharsets.UTF_8);

        assertTrue("Must export GALLIUM_DRIVER=virgl", content.contains("export GALLIUM_DRIVER=virgl"));
        assertTrue("Must export MESA_GL_VERSION_OVERRIDE=3.2", content.contains("export MESA_GL_VERSION_OVERRIDE=3.2"));
        assertTrue("Must export MESA_GLES_VERSION_OVERRIDE=3.2", content.contains("export MESA_GLES_VERSION_OVERRIDE=3.2"));
        assertTrue("Must export ORCHESTELLA_GRAPHICS_BACKEND=virgl", content.contains("export ORCHESTELLA_GRAPHICS_BACKEND=virgl"));
    }

    @Test
    public void packagedGraphicsScriptImplementsLlvmpipeFallbackGuard() throws IOException {
        Path graphicsAsset = getAssetsShellDir().resolve("10-orchestella-graphics.sh");
        byte[] bytes = Files.readAllBytes(graphicsAsset);
        String content = new String(bytes, StandardCharsets.UTF_8);

        assertTrue("Must export GALLIUM_DRIVER=llvmpipe on fallback", content.contains("export GALLIUM_DRIVER=llvmpipe"));
        assertTrue("Must export ORCHESTELLA_GRAPHICS_BACKEND=llvmpipe on fallback", content.contains("export ORCHESTELLA_GRAPHICS_BACKEND=llvmpipe"));
        assertTrue("Must unset MESA_GL_VERSION_OVERRIDE on fallback", content.contains("unset MESA_GL_VERSION_OVERRIDE"));

        assertTrue("Must support ORCHESTELLA_GPU_DISABLED override", content.contains("ORCHESTELLA_GPU_DISABLED"));
        assertTrue("Must probe DRM render node /dev/dri/renderD128", content.contains("/dev/dri/renderD128"));
    }

    @Test
    public void packagedGraphicsAssetMatchesGuestAssetsSourceVerbatim() throws IOException {
        Path sourceDir = getGuestAssetsShellDir();
        Path targetDir = getAssetsShellDir();

        if (Files.isDirectory(sourceDir)) {
            Path src = sourceDir.resolve("10-orchestella-graphics.sh");
            Path tgt = targetDir.resolve("10-orchestella-graphics.sh");
            assertTrue("Source graphics script must exist: " + src, Files.exists(src));
            assertTrue("Packaged graphics asset must exist: " + tgt, Files.exists(tgt));
            assertArrayEquals(
                    "Packaged graphics asset byte content must match source verbatim",
                    Files.readAllBytes(src),
                    Files.readAllBytes(tgt));
        }
    }

    @Test
    public void verifyVirglSmokeScriptExistsInToolsGpu() throws IOException {
        Path smokeScript = getToolsGpuDir().resolve("verify-virgl-smoke.sh");
        assertTrue("tools/gpu/verify-virgl-smoke.sh must exist", Files.exists(smokeScript));
        assertTrue("verify-virgl-smoke.sh must not be empty", Files.size(smokeScript) > 0);

        byte[] bytes = Files.readAllBytes(smokeScript);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertFalse("verify-virgl-smoke.sh must not contain CR line endings", content.contains("\r"));
        assertTrue("verify-virgl-smoke.sh must start with shebang", content.startsWith("#!/"));

        assertTrue("Smoke script must probe /dev/dri/card0", content.contains("/dev/dri/card0"));
        assertTrue("Smoke script must probe /dev/dri/renderD128", content.contains("/dev/dri/renderD128"));
        assertTrue("Smoke script must query GL_RENDERER", content.contains("GL_RENDERER"));
        assertTrue("Smoke script must support glmark2-es2 benchmark", content.contains("glmark2-es2"));
    }
}
