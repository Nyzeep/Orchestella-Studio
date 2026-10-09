package io.github.nyzeep.orchestella.runtime.capability;

import java.util.List;

/** Observable support-floor, capability-badge, and emulator-notice behavior. */
public final class DeviceCapabilityEvaluatorTest {
    private static final long THREE_GIB = 3L * 1024L * 1024L * 1024L;

    public static void main(String[] arguments) {
        exposesTheHonestSoftwareVirtualizationBadgeForASupportedArm64Device();
        refusesADeviceBelowTheSdkFloor();
        refusesANonArm64DeviceWithoutClaimingAnAlternativeRuntime();
        surfacesAnEmulatorNoticeWithoutPretendingItIsADeviceGatePass();
    }

    private static void exposesTheHonestSoftwareVirtualizationBadgeForASupportedArm64Device() {
        DeviceCapabilityEvaluator.Assessment assessment =
                DeviceCapabilityEvaluator.evaluate(
                        new DeviceCapabilityEvaluator.DeviceProfile(
                                35, List.of("arm64-v8a"), THREE_GIB, false));

        assertTrue(assessment.canCreateFullLinuxInstance(), "supported device may create an Instance");
        assertEquals(
                "完整 Linux · 软件模拟（较慢）",
                assessment.performanceBadge(),
                "honest software-virtualization badge");
        assertEquals(null, assessment.emulatorNotice(), "physical device has no emulator notice");
    }

    private static void refusesADeviceBelowTheSdkFloor() {
        DeviceCapabilityEvaluator.Assessment assessment =
                DeviceCapabilityEvaluator.evaluate(
                        new DeviceCapabilityEvaluator.DeviceProfile(
                                25, List.of("arm64-v8a"), THREE_GIB, false));

        assertFalse(assessment.canCreateFullLinuxInstance(), "API 25 must be refused");
        assertEquals(
                "Android 8（API 26）或更高版本",
                assessment.refusalReason(),
                "support-floor reason");
    }

    private static void refusesANonArm64DeviceWithoutClaimingAnAlternativeRuntime() {
        DeviceCapabilityEvaluator.Assessment assessment =
                DeviceCapabilityEvaluator.evaluate(
                        new DeviceCapabilityEvaluator.DeviceProfile(
                                35, List.of("x86_64"), THREE_GIB, false));

        assertFalse(assessment.canCreateFullLinuxInstance(), "non-arm64 host must be refused");
        assertEquals("仅支持 arm64-v8a", assessment.refusalReason(), "architecture refusal reason");
    }

    private static void surfacesAnEmulatorNoticeWithoutPretendingItIsADeviceGatePass() {
        DeviceCapabilityEvaluator.Assessment assessment =
                DeviceCapabilityEvaluator.evaluate(
                        new DeviceCapabilityEvaluator.DeviceProfile(
                                35, List.of("arm64-v8a"), THREE_GIB, true));

        assertTrue(assessment.canCreateFullLinuxInstance(), "an arm64 emulator still reports its floor facts");
        assertEquals(
                "检测到 Android 模拟器：Ticket 26 的安装到 shell 验收仍需 arm64 真机。",
                assessment.emulatorNotice(),
                "emulator disclosure");
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertFalse(boolean condition, String description) {
        assertTrue(!condition, description);
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
