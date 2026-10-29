package io.github.nyzeep.orchestella.runtime.fastlane;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.FakeContext;
import android.os.ParcelFileDescriptor;
import android.system.virtualmachine.FakeVsockNetwork;
import android.system.virtualmachine.VirtualMachine;
import android.system.virtualmachine.VirtualMachineCallback;
import android.system.virtualmachine.VirtualMachineConfig;
import android.system.virtualmachine.VirtualMachineException;
import android.system.virtualmachine.VirtualMachineManager;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Test;

public final class ReflectiveFastLaneApiTest {
    private static final String SHA = "c".repeat(64);
    private static final String INSTANCE = "fastlane-test";

    @Test
    public void passesEveryBuilderFieldExactlyOnce() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        VirtualMachineConfig config = harness.manager.lastConfigForTest();
        assertNotNull(config);
        assertEquals("libfastlane_payload.so", config.getPayloadBinaryName());
        assertTrue(config.isProtectedVm());
        assertEquals(1024L * 1024L * 1024L, config.getMemoryBytes());
        assertEquals(VirtualMachineConfig.CPU_TOPOLOGY_ONE_CPU, config.getCpuTopology());
        assertEquals(268435456L, config.getEncryptedStorageBytes());
        assertEquals(VirtualMachineConfig.DEBUG_LEVEL_NONE, config.getDebugLevel());
        assertEquals(1, config.payloadSetCallsForTest());
        assertEquals(1, config.protectedSetCallsForTest());
        assertEquals(1, config.memorySetCallsForTest());
        assertEquals(1, config.cpuSetCallsForTest());
        assertEquals(1, config.storageSetCallsForTest());
        assertEquals(1, config.debugSetCallsForTest());
        assertEquals(1, harness.vm().runCountForTest());
        assertEquals(1, harness.vm().callbackSetCountForTest());
        assertEquals(1, handle.status());
        assertTrue(handle.notes().contains("vm: ready"));
        assertTrue(handle.notes().contains("callback: installed"));
    }

    @Test
    public void payloadApkPathRedirectFailsHonestlyWhenPlatformLacksSetApkPath() {
        // Session-18 W-A: the two-package redirect resolves setApkPath lazily.
        // The fake Builder predates that method, so a redirect against this
        // surface must fail with a fixed, attributable code — never a silent
        // own-APK fallback (which would load no payload at all).
        Harness harness = Harness.create();
        FastLaneException failure = assertThrows(
                FastLaneException.class,
                () -> harness.api.start(spec().withPayloadApkPath("/data/app/~~x==/ext==/base.apk")));
        // A missing setApkPath method is a truncated platform surface — the
        // API maps it to HIDDEN_API_BLOCKED, the fixed code for "required
        // platform capability absent".
        assertEquals(FastLaneException.HIDDEN_API_BLOCKED, failure.reason());
    }

    @Test
    public void payloadApkPathIsAbsentForOwnApkLaunches() throws Exception {
        // The non-redirect path never touches setApkPath: the fake Builder
        // (which lacks the method) must keep working unchanged.
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        assertEquals(1, handle.status());
    }

    @Test
    public void mapsMatchHostAndDebugFull() throws Exception {
        Harness harness = Harness.create();
        FastLaneLaunchSpec spec = new FastLaneLaunchSpec(
                INSTANCE,
                "payload.so",
                2048,
                FastLaneLaunchSpec.CpuTopology.MATCH_HOST,
                1024L,
                FastLaneLaunchSpec.DebugLevel.FULL,
                5000,
                5001,
                5002,
                5003,
                SHA);
        harness.api.start(spec);
        VirtualMachineConfig config = harness.manager.lastConfigForTest();
        assertEquals(VirtualMachineConfig.CPU_TOPOLOGY_MATCH_HOST, config.getCpuTopology());
        assertEquals(VirtualMachineConfig.DEBUG_LEVEL_FULL, config.getDebugLevel());
    }

    @Test
    public void reusesMatchingStoppedVmWithoutCreatingAgain() throws Exception {
        Harness harness = Harness.create();
        harness.api.start(spec()).stopRuntime(100L);
        FastLaneHandle second = harness.api.start(spec());
        assertEquals(1, harness.manager.createCallsForTest());
        assertEquals(2, harness.manager.getCallsForTest());
        assertEquals(2, harness.vm().runCountForTest());
        assertEquals(1, second.status());
    }

    @Test
    public void refusesDifferentConfigAndNeverDeletesEncryptedStorage() throws Exception {
        Harness harness = Harness.create();
        harness.api.start(spec()).stopRuntime(100L);
        FastLaneLaunchSpec other = new FastLaneLaunchSpec(
                INSTANCE,
                "libfastlane_payload.so",
                2048,
                FastLaneLaunchSpec.CpuTopology.ONE_CPU,
                268435456L,
                FastLaneLaunchSpec.DebugLevel.NONE,
                5000,
                5001,
                5002,
                5003,
                SHA);
        FastLaneException failure = assertThrows(
                FastLaneException.class, () -> harness.api.start(other));
        assertEquals(FastLaneException.VM_CREATE_FAILED, failure.reason());
        assertEquals(0, harness.manager.deleteCallsForTest());
        assertEquals(1, harness.manager.createCallsForTest());
        assertEquals(1, harness.vm().stopCountForTest());
    }

    @Test
    public void apkPathDriftIsRecoveredByRecreatingTheStaleVm() throws Exception {
        Harness harness = Harness.create();
        harness.api.start(spec()).stopRuntime(100L);
        // Platform-side drift (session 19 T-A.4): the extension APK was reinstalled
        // and the stored config now points at a path that no longer exists. The
        // request itself is unchanged, so this is recovered, not refused.
        harness.vm().recordConfigForTest(
                harness.manager.lastConfigForTest().withApkPathForTest("/data/app/~~old==/ext==/base.apk"));

        FastLaneHandle second = harness.api.start(spec());
        assertEquals(1, second.status());
        assertEquals(2, harness.manager.createCallsForTest());
        assertEquals(0, harness.manager.deleteCallsForTest());
        assertTrue(second.notes().contains("vm: recreated after config drift"));
    }

    @Test
    public void refusesExistingRunningVm() throws Exception {
        Harness harness = Harness.create();
        harness.api.start(spec());
        FastLaneException failure = assertThrows(
                FastLaneException.class, () -> harness.api.start(spec()));
        assertEquals(FastLaneException.VM_CREATE_FAILED, failure.reason());
        assertEquals(1, harness.manager.createCallsForTest());
    }

    @Test
    public void connectReturnsAReadWriteChannelAndClosesTheDescriptor() throws Exception {
        Harness harness = Harness.create();
        harness.network.reply(5001, "hello".getBytes(StandardCharsets.UTF_8));
        FastLaneHandle handle = harness.api.start(spec());
        FastLaneChannel channel = harness.api.connect(handle, 5001);

        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8),
                channel.inputStream().readNBytes(5));
        channel.outputStream().write("ping".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("ping".getBytes(StandardCharsets.UTF_8),
                harness.network.sent(5001));
        assertEquals(5001, harness.vm().lastConnectPortForTest());

        channel.close();
        assertTrue(harness.network.closed(5001));
    }

    @Test
    public void connectChannelCloseIsIdempotent() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        FastLaneChannel channel = harness.api.connect(handle, 5001);
        channel.close();
        channel.close();
        assertTrue(harness.network.closed(5001));
    }

    @Test
    public void connectRejectsPortsOutsideTheContract() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        assertEquals(FastLaneException.VSOCK_FAILED,
                assertThrows(FastLaneException.class,
                        () -> harness.api.connect(handle, 80)).reason());
        assertEquals(FastLaneException.VSOCK_FAILED,
                assertThrows(FastLaneException.class,
                        () -> harness.api.connect(handle, 70000)).reason());
    }

    @Test
    public void connectMapsPlatformFailureToVsockFailed() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        harness.vm().setConnectThrowsForTest(true);
        assertEquals(FastLaneException.VSOCK_FAILED,
                assertThrows(FastLaneException.class,
                        () -> harness.api.connect(handle, 5001)).reason());
    }

    @Test
    public void connectMapsDescriptorAdoptionFailure() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        harness.vm().pfdConfigurerForTest = pfd -> pfd.failDup = true;
        assertEquals(FastLaneException.VSOCK_FAILED,
                assertThrows(FastLaneException.class,
                        () -> harness.api.connect(handle, 5001)).reason());
    }

    @Test
    public void connectMapsDescriptorPermissionDenial() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        harness.vm().pfdConfigurerForTest = pfd -> pfd.denyStreams = true;
        assertEquals(FastLaneException.PERMISSION_DENIED,
                assertThrows(FastLaneException.class,
                        () -> harness.api.connect(handle, 5001)).reason());
    }

    @Test
    public void connectAfterStopFailsClosed() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        handle.stopRuntime(100L);
        assertEquals(FastLaneException.VSOCK_FAILED,
                assertThrows(FastLaneException.class,
                        () -> harness.api.connect(handle, 5001)).reason());
    }

    @Test
    public void foreignHandleIsRejected() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle foreign = new FastLaneHandle() {
            @Override
            public String instanceId() {
                return "other";
            }

            @Override
            public int status() {
                return 1;
            }

            @Override
            public FastLaneExitInfo waitForExit() {
                return FastLaneExitInfo.normal(0L);
            }

            @Override
            public FastLaneStopResult stopRuntime(long graceMs) {
                return FastLaneStopResult.CONFIRMED;
            }

            @Override
            public void close() {
            }

            @Override
            public List<String> notes() {
                return new ArrayList<>();
            }
        };
        assertEquals(FastLaneException.VSOCK_FAILED,
                assertThrows(FastLaneException.class,
                        () -> harness.api.connect(foreign, 5001)).reason());
    }

    @Test
    public void permissionDenialFailsBeforeCreatingAnything() {
        Harness harness = Harness.create();
        harness.context.setPermission(-1);
        assertEquals(FastLaneException.PERMISSION_DENIED,
                assertThrows(FastLaneException.class,
                        () -> harness.api.start(spec())).reason());
        assertEquals(0, harness.manager.createCallsForTest());
    }

    @Test
    public void missingProtectedCapabilityFailsClosed() {
        FakeVsockNetwork network = new FakeVsockNetwork();
        VirtualMachineManager manager = new VirtualMachineManager(
                (name, config) -> new VirtualMachine(network),
                VirtualMachineManager.CAPABILITY_NON_PROTECTED_VM);
        FakeContext context = new FakeContext(manager);
        ReflectiveFastLaneApi api = new ReflectiveFastLaneApi(
                context, ReflectiveFastLaneApiTest.class.getClassLoader());
        assertEquals(FastLaneException.PROTECTED_UNAVAILABLE,
                assertThrows(FastLaneException.class, () -> api.start(spec())).reason());
        assertEquals(0, manager.createCallsForTest());
    }

    @Test
    public void capabilityReadFailureIsHiddenApiBlocked() {
        Harness harness = Harness.create();
        harness.manager.setCapabilityReadThrowsForTest(true);
        assertEquals(FastLaneException.HIDDEN_API_BLOCKED,
                assertThrows(FastLaneException.class,
                        () -> harness.api.start(spec())).reason());
    }

    @Test
    public void missingManagerIsProtectedUnavailable() {
        FakeContext context = new FakeContext(null);
        ReflectiveFastLaneApi api = new ReflectiveFastLaneApi(
                context, ReflectiveFastLaneApiTest.class.getClassLoader());
        assertEquals(FastLaneException.PROTECTED_UNAVAILABLE,
                assertThrows(FastLaneException.class,
                        () -> api.start(spec())).reason());
    }

    @Test
    public void noAvfWithDeniedPermissionReportsProtectedUnavailable() {
        ReflectiveFastLaneApi api = new ReflectiveFastLaneApi(
                new FakeContext(null, -1),
                ReflectiveFastLaneApiTest.class.getClassLoader());
        assertEquals(FastLaneException.PROTECTED_UNAVAILABLE,
                assertThrows(FastLaneException.class, () -> api.start(spec())).reason());
    }

    @Test
    public void createFailureIsTyped() {
        Harness harness = Harness.create();
        harness.manager.setCreateFailureForTest(new VirtualMachineException("create failed"));
        assertEquals(FastLaneException.VM_CREATE_FAILED,
                assertThrows(FastLaneException.class,
                        () -> harness.api.start(spec())).reason());
    }

    @Test
    public void runFailureIsTypedAndClosesTheVm() {
        Harness harness = Harness.create(vm ->
                vm.setRunFailureForTest(new VirtualMachineException("run failed")));
        assertEquals(FastLaneException.VM_CREATE_FAILED,
                assertThrows(FastLaneException.class,
                        () -> harness.api.start(spec())).reason());
        assertEquals(1, harness.vm().closeCountForTest());
    }

    @Test
    public void synchronousPayloadVerificationFailureIsTyped() {
        Harness harness = Harness.create(vm -> vm.setRunFailureForTest(
                new VirtualMachineException("payload verification failed")));
        assertEquals(FastLaneException.PAYLOAD_VERIFY_FAILED,
                assertThrows(FastLaneException.class,
                        () -> harness.api.start(spec())).reason());
    }

    @Test
    public void payloadReadyIsObservable() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        assertFalse(handle.payloadReady());
        harness.vm().deliverPayloadReady();
        assertTrue(handle.payloadReady());
    }

    @Test(timeout = 2000)
    public void payloadFinishedWhileStatusRemainsRunningDoesNotHang() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        harness.vm().deliverPayloadFinished(0);
        harness.vm().setStatusForTest(VirtualMachine.STATUS_RUNNING);
        assertEquals(FastLaneExitInfo.Kind.NORMAL, handle.waitForExit().kind());
    }

    @Test(timeout = 2000)
    public void errorWhileStatusRemainsRunningDoesNotHang() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        harness.vm().deliverError(VirtualMachineCallback.ERROR_UNKNOWN, "boom");
        harness.vm().setStatusForTest(VirtualMachine.STATUS_RUNNING);
        assertEquals(FastLaneExitInfo.Kind.CRASHED, handle.waitForExit().kind());
    }

    @Test
    public void deletedStopIsUnconfirmedAndExitIsDeleted() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        harness.vm().setStatusForTest(VirtualMachine.STATUS_DELETED);

        assertEquals(FastLaneStopResult.UNCONFIRMED, handle.stopRuntime(10L));
        assertEquals(0, harness.vm().stopCountForTest());
        assertEquals(1, harness.vm().closeCountForTest());
        assertEquals(FastLaneExitInfo.Kind.DELETED, handle.waitForExit().kind());
    }

    @Test
    public void payloadInvalidConfigCallbackIsTyped() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        harness.vm().deliverError(
                VirtualMachineCallback.ERROR_PAYLOAD_INVALID_CONFIG, "invalid config");
        assertEquals(FastLaneException.VM_CREATE_FAILED,
                assertThrows(FastLaneException.class, handle::waitForExit).reason());
    }

    @Test
    public void payloadInvalidConfigStopReasonIsTyped() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        harness.vm().deliverStopped(
                VirtualMachineCallback.STOP_REASON_MICRODROID_INVALID_PAYLOAD_CONFIG);
        assertEquals(FastLaneException.VM_CREATE_FAILED,
                assertThrows(FastLaneException.class, handle::waitForExit).reason());
    }

    @Test
    public void outputConstructionFailureIsVsockFailedAndClosesTheDescriptor() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        List<ParcelFileDescriptor> descriptors = new ArrayList<>();
        harness.vm().pfdConfigurerForTest = pfd -> {
            pfd.failOutput = true;
            descriptors.add(pfd);
        };

        assertEquals(FastLaneException.VSOCK_FAILED,
                assertThrows(FastLaneException.class,
                        () -> harness.api.connect(handle, 5001)).reason());
        assertEquals(1, descriptors.size());
        assertTrue(descriptors.get(0).isClosedForTest());
    }

    @Test
    public void payloadChangedCallbackIsTyped() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        harness.vm().deliverError(
                VirtualMachineCallback.ERROR_PAYLOAD_CHANGED, "payload changed");
        FastLaneException failure = assertThrows(
                FastLaneException.class, handle::waitForExit);
        assertEquals(FastLaneException.PAYLOAD_VERIFY_FAILED, failure.reason());
    }

    @Test
    public void payloadChangedStopReasonIsTyped() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        harness.vm().deliverStopped(
                VirtualMachineCallback.STOP_REASON_MICRODROID_PAYLOAD_HAS_CHANGED);
        assertEquals(FastLaneException.PAYLOAD_VERIFY_FAILED,
                assertThrows(FastLaneException.class, handle::waitForExit).reason());
    }

    @Test
    public void synchronousPayloadChangedIsTyped() {
        Harness harness = Harness.create(vm -> vm.setRunFailureForTest(
                new VirtualMachineException("payload changed")));
        assertEquals(FastLaneException.PAYLOAD_VERIFY_FAILED,
                assertThrows(FastLaneException.class,
                        () -> harness.api.start(spec())).reason());
    }

    @Test
    public void payloadVerificationStopReasonIsTyped() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        harness.vm().deliverStopped(
                VirtualMachineCallback.STOP_REASON_MICRODROID_PAYLOAD_VERIFICATION_FAILED);
        FastLaneException failure = assertThrows(
                FastLaneException.class, handle::waitForExit);
        assertEquals(FastLaneException.PAYLOAD_VERIFY_FAILED, failure.reason());
    }

    @Test
    public void managerGetFailureIsTyped() {
        Harness harness = Harness.create();
        harness.manager.setGetThrowsForTest(true);
        assertEquals(FastLaneException.VM_CREATE_FAILED,
                assertThrows(FastLaneException.class,
                        () -> harness.api.start(spec())).reason());
    }

    @Test
    public void missingSystemApiIsHiddenApiBlocked() {
        ClassLoader blocked = new ClassLoader(ReflectiveFastLaneApiTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                    throws ClassNotFoundException {
                if ("android.system.virtualmachine.VirtualMachineConfig".equals(name)) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        ReflectiveFastLaneApi api = new ReflectiveFastLaneApi(new FakeContext(null), blocked);
        assertEquals(FastLaneException.HIDDEN_API_BLOCKED,
                assertThrows(FastLaneException.class, () -> api.start(spec())).reason());
    }

    @Test
    public void unsupportedProtectedVmOperationIsProtectedUnavailable() {
        VirtualMachineManager manager = new VirtualMachineManager((name, config) -> {
            throw new UnsupportedOperationException("protected VMs are not supported");
        });
        ReflectiveFastLaneApi api = new ReflectiveFastLaneApi(
                new FakeContext(manager), ReflectiveFastLaneApiTest.class.getClassLoader());
        assertEquals(FastLaneException.PROTECTED_UNAVAILABLE,
                assertThrows(FastLaneException.class, () -> api.start(spec())).reason());
    }

    @Test
    public void deletedExistingVmRequiresExplicitDelete() throws Exception {
        Harness harness = Harness.create();
        harness.api.start(spec()).stopRuntime(100L);
        harness.vm().setStatusForTest(VirtualMachine.STATUS_DELETED);
        assertEquals(FastLaneException.VM_CREATE_FAILED,
                assertThrows(FastLaneException.class,
                        () -> harness.api.start(spec())).reason());
        assertEquals(0, harness.manager.deleteCallsForTest());
    }

    @Test
    public void unobservableStatusStopIsUnconfirmedAndRetrySettles() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        harness.vm().setStatusThrowsForTest(true);

        assertEquals(FastLaneStopResult.UNCONFIRMED, handle.stopRuntime(10L));
        assertEquals(1, harness.vm().stopCountForTest());
        assertEquals(1, harness.vm().closeCountForTest());

        harness.vm().setStatusThrowsForTest(false);
        assertEquals(FastLaneStopResult.ALREADY_STOPPED, handle.stopRuntime(10L));
        assertEquals(1, harness.vm().stopCountForTest());
    }

    @Test
    public void platformStopExceptionStillProbesClose() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        harness.vm().setStopThrowsForTest(true);

        assertEquals(FastLaneStopResult.CONFIRMED, handle.stopRuntime(10L));
        assertEquals(1, harness.vm().stopCountForTest());
        assertEquals(1, harness.vm().closeCountForTest());
    }

    @Test
    public void existingConfigReadFailureIsTyped() throws Exception {
        Harness harness = Harness.create();
        harness.api.start(spec()).stopRuntime(100L);
        harness.vm().setConfigReadFailureForTest(new RuntimeException("read failed"));
        assertEquals(FastLaneException.VM_CREATE_FAILED,
                assertThrows(FastLaneException.class,
                        () -> harness.api.start(spec())).reason());
    }

    @Test
    public void callbackInstallFailureFallsBackToPolling() throws Exception {
        Harness harness = Harness.create(vm -> vm.setCallbackInstallThrowsForTest(true));
        FastLaneHandle handle = harness.api.start(spec());
        assertEquals(0, harness.vm().callbackSetCountForTest());
        assertTrue(handle.notes().contains("callback: install failed"));
        assertEquals(1, handle.status());
        assertEquals(FastLaneStopResult.CONFIRMED, handle.stopRuntime(100L));
        assertEquals(FastLaneExitInfo.Kind.STOP_REQUESTED, handle.waitForExit().kind());
    }

    @Test
    public void stopConfirmedClosesChannelsAndIsIdempotent() throws Exception {
        Harness harness = Harness.create();
        harness.network.reply(5001, new byte[0]);
        FastLaneHandle handle = harness.api.start(spec());
        harness.api.connect(handle, 5001);

        assertEquals(FastLaneStopResult.CONFIRMED, handle.stopRuntime(100L));
        assertEquals(1, harness.vm().stopCountForTest());
        assertTrue(harness.network.closed(5001));
        assertEquals(0, handle.status());
        assertEquals(FastLaneStopResult.CONFIRMED, handle.stopRuntime(100L));
        assertEquals(1, harness.vm().stopCountForTest());
        assertEquals(FastLaneExitInfo.Kind.STOP_REQUESTED, handle.waitForExit().kind());
    }

    @Test
    public void alreadyStoppedReleasesHandleWithoutPlatformStop() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        harness.vm().setStatusForTest(VirtualMachine.STATUS_STOPPED);
        assertEquals(FastLaneStopResult.ALREADY_STOPPED, handle.stopRuntime(100L));
        assertEquals(0, harness.vm().stopCountForTest());
        assertEquals(1, harness.vm().closeCountForTest());
    }

    @Test
    public void unconfirmedStopStaysVisibleAndNeverBecomesStopped() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        harness.vm().setStopConvergesForTest(false);
        harness.vm().setCloseConvergesForTest(false);

        assertEquals(FastLaneStopResult.UNCONFIRMED, handle.stopRuntime(20L));
        assertEquals(1, harness.vm().stopCountForTest());
        assertEquals(1, harness.vm().closeCountForTest());
        assertEquals(1, handle.status());
        assertEquals(FastLaneStopResult.UNCONFIRMED, handle.stopRuntime(10L));
        assertEquals(2, harness.vm().stopCountForTest());
        FastLaneException failure = assertThrows(
                FastLaneException.class, handle::waitForExit);
        assertEquals(FastLaneException.STOP_UNCONFIRMED, failure.reason());
    }

    @Test
    public void payloadFinishedZeroIsNormal() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        harness.vm().deliverPayloadFinished(0);
        assertEquals(FastLaneExitInfo.Kind.NORMAL, handle.waitForExit().kind());
    }

    @Test
    public void payloadFinishedNonZeroIsCrashed() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        harness.vm().deliverPayloadFinished(7);
        FastLaneExitInfo info = handle.waitForExit();
        assertEquals(FastLaneExitInfo.Kind.CRASHED, info.kind());
        assertEquals(7, info.exitCode());
    }

    @Test
    public void shutdownReasonIsNormalAndCrashReasonIsCrashed() throws Exception {
        Harness normal = Harness.create();
        FastLaneHandle normalHandle = normal.api.start(spec());
        normal.vm().deliverStopped(VirtualMachineCallback.STOP_REASON_SHUTDOWN);
        assertEquals(FastLaneExitInfo.Kind.NORMAL, normalHandle.waitForExit().kind());

        Harness crashed = Harness.create();
        FastLaneHandle crashedHandle = crashed.api.start(spec());
        crashed.vm().deliverStopped(VirtualMachineCallback.STOP_REASON_CRASH);
        assertEquals(FastLaneExitInfo.Kind.CRASHED, crashedHandle.waitForExit().kind());
    }

    @Test
    public void deletedAndUnobservableStatusAreDistinct() throws Exception {
        Harness deleted = Harness.create();
        FastLaneHandle deletedHandle = deleted.api.start(spec());
        deleted.vm().setStatusForTest(VirtualMachine.STATUS_DELETED);
        assertEquals(FastLaneExitInfo.Kind.DELETED, deletedHandle.waitForExit().kind());

        Harness unobservable = Harness.create();
        FastLaneHandle unobservableHandle = unobservable.api.start(spec());
        unobservable.vm().setStatusThrowsForTest(true);
        assertEquals(FastLaneExitInfo.Kind.OBSERVE_FAILED,
                unobservableHandle.waitForExit().kind());
    }

    @Test
    public void payloadVerificationCallbackIsTyped() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        harness.vm().deliverError(
                VirtualMachineCallback.ERROR_PAYLOAD_VERIFICATION_FAILED,
                "payload verification failed");
        FastLaneException failure = assertThrows(
                FastLaneException.class, handle::waitForExit);
        assertEquals(FastLaneException.PAYLOAD_VERIFY_FAILED, failure.reason());
    }

    @Test
    public void unknownCallbackErrorIsCrashed() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        harness.vm().deliverError(VirtualMachineCallback.ERROR_UNKNOWN, "boom");
        assertEquals(FastLaneExitInfo.Kind.CRASHED, handle.waitForExit().kind());
    }

    @Test
    public void notesNeverContainPayloadNameOrPaths() throws Exception {
        Harness harness = Harness.create();
        FastLaneHandle handle = harness.api.start(spec());
        for (String note : handle.notes()) {
            assertFalse(note, note.contains("libfastlane_payload.so"));
            assertFalse(note, note.contains("/"));
        }
    }

    private static FastLaneLaunchSpec spec() {
        return new FastLaneLaunchSpec(
                INSTANCE,
                "libfastlane_payload.so",
                1024,
                FastLaneLaunchSpec.CpuTopology.ONE_CPU,
                268435456L,
                FastLaneLaunchSpec.DebugLevel.NONE,
                5000,
                5001,
                5002,
                5003,
                SHA);
    }

    private static final class Harness {
        private final FakeVsockNetwork network;
        private final FakeContext context;
        private VirtualMachineManager manager;
        private ReflectiveFastLaneApi api;

        private Harness(FakeVsockNetwork network, FakeContext context,
                VirtualMachineManager manager, ReflectiveFastLaneApi api) {
            this.network = network;
            this.context = context;
            this.manager = manager;
            this.api = api;
        }

        private static Harness create() {
            return create(vm -> { });
        }

        private static Harness create(Consumer<VirtualMachine> configurer) {
            FakeVsockNetwork network = new FakeVsockNetwork();
            VirtualMachineManager manager = new VirtualMachineManager((name, config) -> {
                VirtualMachine vm = new VirtualMachine(network);
                configurer.accept(vm);
                return vm;
            });
            FakeContext context = new FakeContext(manager);
            ReflectiveFastLaneApi api = new ReflectiveFastLaneApi(
                    context, ReflectiveFastLaneApiTest.class.getClassLoader());
            return new Harness(network, context, manager, api);
        }

        private VirtualMachine vm() {
            VirtualMachine vm = manager.lastVmForTest();
            assertNotNull("VM was not created", vm);
            return vm;
        }
    }
}
