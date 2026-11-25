package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Ticket 10 executable guards. They are intentionally source-level where the
 * invariant is "this dependency/class must not exist"; Gradle's module graph
 * cannot express a negative dependency, and a deleted class must not come back
 * through a convenience import.
 */
public final class IntegrationGuardsTest {

    @Test
    public void avfEngineHasNoQemuEngineDependency() throws IOException {
        Path engine = repoRoot().resolve("runtime/engine-avf/src/main/java");
        try (Stream<Path> files = Files.walk(engine)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                assertFalse("AVF engine must not import QEMU engine types: " + file,
                        source.contains("import io.github.nyzeep.orchestella.runtime.engineqemu."));
            }
        }
    }

    @Test
    public void deletedDisplayAndForwardingChannelsStayOutOfProduction() {
        for (String missing : List.of(
                "AvfWaylandChannel.java",
                "AvfGraphicsVsockProtocol.java",
                "AvfAudioChannel.java",
                "AvfAudioVsockProtocol.java",
                "AvfVsockPortForwarder.java",
                "QemuWaylandChannel.java",
                // UI-12（2026-11-18）：旧 XML 向导已重写为 Compose 壳内二级页，
                // 旧 Activity 必须保持删除状态，不得经便利 import 复活。
                "SetupWizardActivity.java",
                "SetupWizardActivity.kt")) {
            assertFalse(missing + " is a dead path and must not return",
                    Files.exists(repoRoot().resolve("app/src/main/java/io/github/nyzeep/orchestella")
                            .resolve(missing)));
        }
    }

    @Test
    public void avfSingleVcpuConstraintIsVisibleBeforeLaunch() throws IOException {
        // UI-12 迁移：守卫从旧 SetupWizardActivity.kt 改指 Compose 向导的投影与
        // 步骤内容，三条不变量原样保留（问 admission 层 / 分支单 vCPU 拒绝 /
        // 渲染专用提示）。
        String projection = read(
                "app/src/main/java/io/github/nyzeep/orchestella/ui/screens/wizard/WizardUiState.kt");
        assertTrue("the wizard must ask the admission layer",
                projection.contains("AvfLaunchAdmission.cpuSupport"));
        assertTrue("the wizard must branch on the single-vCPU refusal",
                projection.contains("NEEDS_SINGLE_VCPU"));

        String stepContents = read(
                "app/src/main/java/io/github/nyzeep/orchestella/ui/screens/wizard/WizardStepContents.kt");
        assertTrue("the wizard must render the dedicated notice",
                stepContents.contains("wizard_config_vcpu_avf_notice"));

        String strings = read("app/src/main/res/values/strings.xml");
        assertTrue("the notice must state the 1 vCPU constraint in user terms",
                strings.contains("仅支持 1 vCPU"));
        assertTrue("the notice must explain the QEMU fallback",
                strings.contains("回退到软件模拟"));
    }

    @Test
    public void ciRunsTheZeroDeviceDeviceValidationDryRun() throws IOException {
        String ci = read(".github/workflows/ci.yml");
        assertTrue("CI must run the AVF device-validation tool tests",
                ci.contains("tools/device-validation"));
        assertTrue("CI must run the explicit dry-run",
                ci.contains("run_avf_device_validation.py") && ci.contains("--dry-run"));
    }

    @Test
    public void executionSessionUsesDisplayChannelAndNoLegacyWaylandChannelMethodRemains()
            throws IOException {
        String sessionSource = read(
                "app/src/main/java/io/github/nyzeep/orchestella/ExecutionSession.java");
        assertTrue("ExecutionSession must expose displayChannel()",
                sessionSource.contains("DisplayChannel displayChannel()"));
        assertFalse("ExecutionSession must not retain waylandChannel()",
                sessionSource.contains("waylandChannel("));

        Path mainJava = repoRoot().resolve("app/src/main/java");
        try (Stream<Path> files = Files.walk(mainJava)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")
                    || path.toString().endsWith(".kt")).toList()) {
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                assertFalse("Production code must not declare or call legacy waylandChannel(): " + file,
                        source.contains("waylandChannel("));
            }
        }

        String hudController = read(
                "app/src/main/java/io/github/nyzeep/orchestella/WaylandSurfaceController.java");
        assertFalse("HudBadgeInfo.toBadgeText() must use RFB rather than Wayland",
                hudController.contains("\"Wayland ["));
    }

    private static String read(String relative) throws IOException {
        return new String(Files.readAllBytes(repoRoot().resolve(relative)), StandardCharsets.UTF_8);
    }

    private static Path repoRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found from " + Paths.get("").toAbsolutePath());
    }
}
