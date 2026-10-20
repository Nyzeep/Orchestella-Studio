package io.github.nyzeep.orchestella.runtime.engineavf;

import android.system.virtualmachine.FakeVirtualizationContext;
import android.system.virtualmachine.VirtualMachine;
import android.system.virtualmachine.VirtualMachineManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ticket 02 zero-device acceptance: the AVF session owns one bounded stop, a
 * repeated stop never creates a second owner, an unconfirmable platform stop is
 * reported as UNCONFIRMED, and exit observation distinguishes a normal stop, a
 * deleted VM and a stop requested by the host.
 */
public final class AvfRuntimeOwnershipTest {
    public static void main(String[] arguments) throws Exception {
        closeStopsOnceAndRepeatsAreIdempotent();
        stuckPlatformStopIsUnconfirmedAndNeverStopsTheOwner();
        throwingPlatformStopIsNotSuccess();
        alreadyStoppedVmReportsAlreadyStopped();
        exitClassificationDistinguishesNormalDeletedAndStopRequest();
        launchTraceStagesAreMonotonic();
        System.out.println("AVF_RUNTIME_OWNERSHIP_TEST_OK close_stop=1 idempotent=1"
                + " unconfirmed=1 throwing=1 already=1 exit_normal=1 exit_deleted=1"
                + " exit_stop=1 trace=1");
    }

    private static void closeStopsOnceAndRepeatsAreIdempotent() throws Exception {
        VirtualMachine vm = VirtualMachine.over(null);
        AvfVirtualMachineEngine.AvfVmSession session = launchOver(
                new VirtualMachineManager((name, config) -> vm), "owned-close");
        assertEquals(AvfStopResult.CONFIRMED, session.stopRuntime(500L), "first stop must confirm");
        assertEquals(1, vm.stopCountForTest(), "platform stop must be requested once");
        assertEquals(1, vm.closeCountForTest(), "platform handle released once");
        assertEquals(AvfStopResult.CONFIRMED, session.stopRuntime(500L),
                "a repeated stop returns the same owner's result");
        assertEquals(1, vm.stopCountForTest(),
                "a repeated stop must not call the platform again");
        assertFalse(session.isRuntimeAlive(), "confirmed owner is not alive");
    }

    private static void stuckPlatformStopIsUnconfirmedAndNeverStopsTheOwner() throws Exception {
        AtomicInteger status = new AtomicInteger(VirtualMachine.STATUS_RUNNING);
        VirtualMachine vm = VirtualMachine.withNumericStatus(status::get);
        AvfVirtualMachineEngine.AvfVmSession session = launchOver(
                new VirtualMachineManager((name, config) -> vm), "owned-stuck");
        long started = System.nanoTime();
        AvfStopResult result = session.stopRuntime(80L);
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started);
        assertEquals(AvfStopResult.UNCONFIRMED, result,
                "a platform that stays RUNNING cannot confirm a stop");
        assertTrue(elapsedMs < 2000L,
                "bounded stop must return near its grace window: " + elapsedMs + " ms");
        assertEquals(1, vm.stopCountForTest(), "only one stop request");
        assertEquals(1, vm.closeCountForTest(), "one forced close attempt");
        assertTrue(session.isRuntimeAlive(), "UNCONFIRMED keeps the runtime alive");
        try {
            session.waitForExit();
            fail("an UNCONFIRMED stop must not be observed as a normal exit");
        } catch (AvfEngineException expected) {
            assertEquals(AvfEngineException.VM_STOP_UNCONFIRMED, expected.reason(),
                    "the exit observer reports the typed unconfirmed stop");
        }
    }

    private static void throwingPlatformStopIsNotSuccess() throws Exception {
        VirtualMachine vm = VirtualMachine.withNumericStatus(() -> VirtualMachine.STATUS_RUNNING);
        vm.stopFailureForTest = new IllegalStateException("synthetic platform stop failure");
        AvfVirtualMachineEngine.AvfVmSession session = launchOver(
                new VirtualMachineManager((name, config) -> vm), "owned-throwing");
        assertEquals(AvfStopResult.UNCONFIRMED, session.stopRuntime(50L),
                "a throwing platform stop is not evidence of success");
        assertTrue(session.isRuntimeAlive(), "the VM still reports RUNNING");
    }

    private static void alreadyStoppedVmReportsAlreadyStopped() throws Exception {
        VirtualMachine vm = VirtualMachine.withNumericStatus(() -> VirtualMachine.STATUS_STOPPED);
        AvfVirtualMachineEngine.AvfVmSession session = launchOver(
                new VirtualMachineManager((name, config) -> vm), "owned-already");
        assertEquals(AvfStopResult.ALREADY_STOPPED, session.stopRuntime(100L),
                "a VM already stopped before the request is not a second stop");
        assertEquals(0, vm.stopCountForTest(),
                "an already stopped VM must not receive stop()");
    }

    private static void exitClassificationDistinguishesNormalDeletedAndStopRequest()
            throws Exception {
        AtomicInteger status = new AtomicInteger(VirtualMachine.STATUS_RUNNING);
        VirtualMachine normalVm = VirtualMachine.withNumericStatus(status::get);
        AvfVirtualMachineEngine.AvfVmSession normal = launchOver(
                new VirtualMachineManager((name, config) -> normalVm), "exit-normal");
        status.set(VirtualMachine.STATUS_STOPPED);
        AvfExitInfo normalExit = normal.waitForExit();
        assertEquals(AvfExitInfo.Kind.NORMAL, normalExit.kind(), "guest stop is a normal exit");
        assertEquals(0, normalExit.exitCode(), "a normal exit keeps the zero code");

        VirtualMachine deletedVm =
                VirtualMachine.withNumericStatus(() -> VirtualMachine.STATUS_DELETED);
        AvfVirtualMachineEngine.AvfVmSession deleted = launchOver(
                new VirtualMachineManager((name, config) -> deletedVm), "exit-deleted");
        AvfExitInfo deletedExit = deleted.waitForExit();
        assertEquals(AvfExitInfo.Kind.DELETED, deletedExit.kind(),
                "a DELETED VM is not a normal exit");
        assertTrue(deletedExit.exitCode() != 0,
                "DELETED must never surface as exit code 0");
        assertEquals(deletedExit.exitCode(), deleted.waitFor(),
                "waitFor delegates the non-zero classification");

        VirtualMachine stopVm = VirtualMachine.over(null);
        AvfVirtualMachineEngine.AvfVmSession stopped = launchOver(
                new VirtualMachineManager((name, config) -> stopVm), "exit-stopped");
        assertEquals(AvfStopResult.CONFIRMED, stopped.stopRuntime(50L), "host stop confirms");
        assertEquals(AvfExitInfo.Kind.STOP_REQUESTED, stopped.waitForExit().kind(),
                "the host's own stop is a distinct exit class");
    }

    private static void launchTraceStagesAreMonotonic() throws Exception {
        VirtualMachine vm = VirtualMachine.over(null);
        List<AvfLaunchTrace.Stage> stages = new ArrayList<>();
        AvfVirtualMachineEngine.AvfVmSession session = launchOver(
                new VirtualMachineManager((name, config) -> vm), "trace-owner",
                stage -> stages.add(stage));
        session.stopRuntime(50L);
        session.waitForExit();
        assertEquals(List.of(
                        AvfLaunchTrace.Stage.LAUNCH_REQUESTED,
                        AvfLaunchTrace.Stage.VM_CREATED,
                        AvfLaunchTrace.Stage.RUN_RETURNED,
                        AvfLaunchTrace.Stage.STOP_REQUESTED,
                        AvfLaunchTrace.Stage.STOP_CONFIRMED,
                        AvfLaunchTrace.Stage.EXIT_OBSERVED),
                stages, "the AVF lifecycle stages must be ordered and complete");
    }

    private static AvfVirtualMachineEngine.AvfVmSession launchOver(
            VirtualMachineManager manager, String instanceId) throws Exception {
        return launchOver(manager, instanceId, AvfLaunchTrace.NONE);
    }

    private static AvfVirtualMachineEngine.AvfVmSession launchOver(
            VirtualMachineManager manager, String instanceId, AvfLaunchTrace trace)
            throws Exception {
        ReflectiveAvfVirtualMachineApi api = new ReflectiveAvfVirtualMachineApi(
                new FakeVirtualizationContext(manager),
                AvfRuntimeOwnershipTest.class.getClassLoader());
        AvfLaunchSpec spec = AvfLaunchSpec.builder(
                        instanceId, "/data/kernel", "/data/initramfs.cpio.gz", "/data/root.raw")
                .build();
        return new AvfVirtualMachineEngine(api).launch(spec, trace);
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
        if (expected != actual) {
            fail(description + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void fail(String reason) {
        System.out.println("AVF_RUNTIME_OWNERSHIP_TEST_FAIL reason=" + reason);
        System.exit(1);
    }
}
