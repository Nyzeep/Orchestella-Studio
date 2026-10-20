package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import java.util.List;

/**
 * Ticket 03 zero-device acceptance for the pre-bootstrap admission: the
 * default 2-vCPU configuration is rejected before any assembly, a 1-vCPU
 * configuration yields a complete plan, and every hardware field is either
 * expressed or explicitly typed as not expressible.
 */
public final class AvfLaunchAdmissionTest {
    public static void main(String[] arguments) {
        rejectsDefaultTwoVcpuBeforeAnyPlan();
        acceptsOneVcpuAndMapsEveryField();
        rejectsControlCharactersInExtraArguments();
        rejectsKnownUnsupportedHostFactsButAdmitsUnknownFacts();
        acceptsNullConfigurationWithConservativeDefaults();
        specAppendsPlanArgumentsAfterTheBaseCmdline();
        System.out.println("AVF_LAUNCH_ADMISSION_TEST_OK cpu=1 default_reject=1"
                + " memory=1 disk=not_expressible gpu=not_expressible extras=1"
                + " facts=3 null_config=1 cmdline=1");
    }

    private static void rejectsDefaultTwoVcpuBeforeAnyPlan() {
        AvfLaunchAdmission.Result result = AvfLaunchAdmission.evaluate(
                VmHardwareConfig.defaultConfig(), AvfHostFacts.supported(), -1L);
        assertFalse(result.accepted(), "the default 2-vCPU config must be rejected");
        assertEquals(AvfEngineException.HARDWARE_UNSUPPORTED, result.rejection().reason(),
                "the rejection is a hardware limitation");
        assertEquals("cpuCores", result.rejection().field(), "the rejected field is named");
        assertTrue(result.plan() == null, "a rejected admission must not produce a launch plan");
    }

    private static void acceptsOneVcpuAndMapsEveryField() {
        VmHardwareConfig config = VmHardwareConfig.builder()
                .cpuCores(1)
                .ramMegabytes(1024)
                .diskCapacityBytes(4L * 1024L * 1024L * 1024L)
                .gpuAccelerationEnabled(true)
                .extraKernelArguments("console=ttyS0  quiet")
                .build();
        AvfLaunchAdmission.Result result =
                AvfLaunchAdmission.evaluate(config, AvfHostFacts.supported(), 4L * 1024L * 1024L * 1024L);
        assertTrue(result.accepted(), "1 vCPU is admissible");
        AvfLaunchAdmission.Plan plan = result.plan();
        assertEquals(1, plan.cpuCount(), "AVF v1 uses exactly one vCPU");
        assertEquals(1024L, plan.memoryMb(), "memory is expressed");
        assertEquals(List.of("console=ttyS0", "quiet"), plan.extraKernelArguments(),
                "extra kernel arguments are tokenized and preserved");
        assertEquals(AvfLaunchAdmission.Disposition.EXPRESSED, plan.field("memory").disposition(),
                "memory maps to setMemoryBytes");
        assertEquals(AvfLaunchAdmission.Disposition.NOT_EXPRESSIBLE, plan.field("diskCapacityBytes").disposition(),
                "AVF has no independent disk-size setter");
        assertTrue(plan.field("diskCapacityBytes").detail().contains("4294967296"),
                "the observed raw size is recorded instead of pretending it was set");
        assertEquals(AvfLaunchAdmission.Disposition.NOT_EXPRESSIBLE, plan.field("gpuAccelerationEnabled").disposition(),
                "AVF v1 cannot express GPU acceleration");
        assertTrue(plan.diagnostics().stream().anyMatch(note -> note.contains("GPU")),
                "GPU being unsupported is a recorded diagnostic, not a launch failure");
    }

    private static void rejectsControlCharactersInExtraArguments() {
        VmHardwareConfig config = VmHardwareConfig.builder()
                .cpuCores(1)
                .extraKernelArguments("console=ttyS0\nroot=/dev/vda")
                .build();
        AvfLaunchAdmission.Result result =
                AvfLaunchAdmission.evaluate(config, AvfHostFacts.supported(), -1L);
        assertFalse(result.accepted(), "a newline in extra arguments must be rejected");
        assertEquals("extraKernelArguments", result.rejection().field(),
                "the offending field is named");
    }

    private static void rejectsKnownUnsupportedHostFactsButAdmitsUnknownFacts() {
        AvfLaunchAdmission.Result unsupported = AvfLaunchAdmission.evaluate(
                oneCpuConfig(), AvfHostFacts.unsupported(), -1L);
        assertFalse(unsupported.accepted(), "known non-protected absence is a rejection");
        assertEquals("nonProtectedVm", unsupported.rejection().field(), "the host fact is named");

        assertTrue(AvfLaunchAdmission.evaluate(oneCpuConfig(), AvfHostFacts.unknown(), -1L).accepted(),
                "an unread host fact must reach the adapter instead of failing early");
    }

    private static void acceptsNullConfigurationWithConservativeDefaults() {
        AvfLaunchAdmission.Result result = AvfLaunchAdmission.evaluate(null, AvfHostFacts.supported(), -1L);
        assertTrue(result.accepted(), "a caller without hardware config keeps a viable path");
        assertEquals(1, result.plan().cpuCount(), "the fallback is one vCPU");
        assertEquals(512L, result.plan().memoryMb(), "the authenticated default memory is kept");
    }

    private static void specAppendsPlanArgumentsAfterTheBaseCmdline() {
        AvfLaunchAdmission.Plan plan = AvfLaunchAdmission.evaluate(
                oneCpuConfig().withExtraKernelArguments("console=ttyS0 quiet"),
                AvfHostFacts.supported(), -1L).plan();
        AvfLaunchSpec spec = AvfLaunchSpec.builder("admission-cmdline", "/k", "/i", "/r")
                .extraKernelArguments(plan.extraKernelArguments())
                .networkSpec(io.github.nyzeep.orchestella.runtime.contract.NetworkContract
                        .OutboundNetworkSpec.withDns("1.1.1.1"))
                .build();
        String cmdline = spec.guestCmdline();
        assertTrue(cmdline.contains("console=ttyS0 quiet"), "plan arguments reach the cmdline: " + cmdline);
        assertTrue(cmdline.indexOf("console=ttyS0") < cmdline.indexOf("droidlayer.dns"),
                "DNS stays the final contract key");
    }

    private static VmHardwareConfig oneCpuConfig() {
        return VmHardwareConfig.builder().cpuCores(1).ramMegabytes(1024).build();
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) fail(description);
    }

    private static void assertFalse(boolean condition, String description) {
        assertTrue(!condition, description);
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            fail(description + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void assertEquals(int expected, int actual, String description) {
        if (expected != actual) fail(description + " (expected " + expected + ", got " + actual + ")");
    }

    private static void assertEquals(long expected, long actual, String description) {
        if (expected != actual) fail(description + " (expected " + expected + ", got " + actual + ")");
    }

    private static void fail(String reason) {
        System.out.println("AVF_LAUNCH_ADMISSION_TEST_FAIL reason=" + reason);
        System.exit(1);
    }
}
