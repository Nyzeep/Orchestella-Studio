package io.github.nyzeep.orchestella.runtime.engineavf;

import android.system.virtualmachine.FakeVirtualizationContext;
import android.system.virtualmachine.VirtualMachine;
import android.system.virtualmachine.VirtualMachineConfig;
import android.system.virtualmachine.VirtualMachineManager;
import java.util.concurrent.atomic.AtomicReference;

/** Behavior at the public AVF adapter seam using independently pinned AOSP signatures. */
public final class ReflectiveAvfVirtualMachineApiTest {
    public static void main(String[] args) throws Exception {
        wrapsTheCustomImageInVmConfigAndRunsIt();
        connectsLongPortAndOwnsBothPfdDirections();
        closingEitherStreamReleasesTheWholeChannel();
        rejectsMissingPermissionsBeforeCreatingAnything();
        refusesUnsupportedPlatformAndUnavailableApi();
        mapsCreateAndRunExceptionsAndClosesFailedRun();
        rejectsBadFdAndReclaimsFailedStreamConstruction();
        injectedFdAdapterIsCheckedAndFailureClosesOriginal();
        closedAndForeignHandlesCannotConnect();
        streamPermissionRefusalIsNotReportedAsTransientVsock();
        rejectsAnInventedRunReturnType();
        platformStopIsReachedDuringBlockedOfficialConnect();
        stopDuringFdAdoptionRejectsLateStreamsWithoutDoubleClose();
        handleStopOwnsPublishedPfdExactlyOnce();
        coldRestoresMatchingPersistentVmWithoutCreatingAgain();
        failedRunRetriesTheSamePersistentVmWithoutDeletingIt();
        mismatchedStoredConfigsNeverRunOrDelete();
        runningPersistentVmIsNotStoppedByAnotherLaunch();
        unreadablePersistentConfigIsRejectedWithoutRunningOrDeleting();
        System.out.println("REFLECTIVE_AVF_API_TEST_OK official_config_run=1 long_pfd_streams=1 stream_close=1"
                + " permissions=2 platform_guards=3 create_run_failures=4 fd_cleanup=4 injection=2 handles=3"
                + " official_blocked_stop=1 adoption_race=1 published_fd_stop=1"
                + " persistent_restore=1 failed_run_retry=1 config_mismatch=12 running_vm_refusal=1 config_read_refusal=2");
    }

    private static AvfLaunchSpec spec() {
        return AvfLaunchSpec.builder("official-shape", "/kernel", "/initrd", "/root.raw")
                .memoryMb(384).build();
    }

    private static void wrapsTheCustomImageInVmConfigAndRunsIt() throws Exception {
        AtomicReference<VirtualMachineConfig> captured = new AtomicReference<>();
        VirtualMachine vm = VirtualMachine.withNumericStatus(() -> 1);
        VirtualMachineManager manager = new VirtualMachineManager((name, config) -> {
            check("official-shape".equals(name), "exact manager instance name");
            check(vm.runCountForTest() == 0, "create precedes run");
            captured.set(config);
            return vm;
        });
        ReflectiveAvfVirtualMachineApi api = new ReflectiveAvfVirtualMachineApi(
                new FakeVirtualizationContext(manager), getLoader());
        AvfVirtualMachineApi.AvfVirtualMachineHandle handle = api.startCustomVm(spec());
        VirtualMachineConfig config = captured.get();
        check(config != null, "manager received VM config");
        check(!config.isProtectedVm(), "non-protected is explicit");
        check(config.getMemoryBytes() == 402653184L, "MiB converted on VM builder");
        check(config.getCpuTopology() == VirtualMachineConfig.CPU_TOPOLOGY_ONE_CPU, "ONE_CPU");
        var image = config.getCustomImageConfig();
        check("/kernel".equals(image.getKernelPath()), "kernel on image builder");
        check("/initrd".equals(image.getInitrdPath()), "initrd on image builder");
        check(image.getDisks().length == 1 && image.getDisks()[0].isWritable(), "RWDisk");
        check("/root.raw".equals(image.getDisks()[0].getImagePath()), "exact root path");
        check(String.join(" ", image.getParams()).equals(spec().guestCmdline()), "addParam carries cmdline");
        check(vm.runCountForTest() == 1, "exactly one real run call");
        handle.stop();
        check(vm.stopCountForTest() == 1, "stop reaches VM");
    }

    private static void connectsLongPortAndOwnsBothPfdDirections() throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        var pfd = new android.os.ParcelFileDescriptor(
                new java.io.ByteArrayInputStream(new byte[] {42}), output, () -> {});
        java.util.concurrent.atomic.AtomicLong observedPort = new java.util.concurrent.atomic.AtomicLong();
        VirtualMachine vm = VirtualMachine.withBehavior(() -> {}, port -> {
            observedPort.set(port);
            return pfd;
        });
        ReflectiveAvfVirtualMachineApi api = apiFor(vm);
        var handle = api.startCustomVm(spec());
        var channel = api.connectVsock(handle, 9103);
        check(observedPort.get() == 9103L, "long port signature invoked");
        check(channel.inputStream().read() == 42, "PFD input contains actual bytes");
        channel.outputStream().write(7);
        channel.outputStream().flush();
        check(java.util.Arrays.equals(output.toByteArray(), new byte[] {7}), "PFD output sent bytes");
        channel.close();
        channel.close();
        check(pfd.closeCallsForTest() == 1, "original FD closed once");
        check(pfd.lastDuplicate != null && pfd.lastDuplicate.closeCallsForTest() == 1,
                "output duplicate has one independent owner");
        check(pfd.releasedForTest() == 2, "all descriptors released");
        handle.stop();
    }

    private static void closingEitherStreamReleasesTheWholeChannel() throws Exception {
        for (boolean inputFirst : new boolean[] {true, false}) {
            var pfd = memoryDescriptor();
            var api = apiFor(VirtualMachine.withBehavior(() -> {}, port -> pfd));
            var handle = api.startCustomVm(spec());
            var channel = api.connectVsock(handle, 9103);
            if (inputFirst) channel.inputStream().close();
            else channel.outputStream().close();
            channel.close();
            check(pfd.closeCallsForTest() == 1 && pfd.lastDuplicate.closeCallsForTest() == 1,
                    "stream close must close both FD owners once");
            handle.stop();
        }
    }

    private static void rejectsMissingPermissionsBeforeCreatingAnything() {
        for (boolean manage : new boolean[] {true, false}) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var manager = new VirtualMachineManager((name, config) -> { calls.incrementAndGet(); return null; });
            var api = new ReflectiveAvfVirtualMachineApi(new FakeVirtualizationContext(manager, manage, false), getLoader());
            AvfEngineException error = expect(AvfEngineException.PERMISSION_DENIED, () -> api.startCustomVm(spec()));
            check(error.getMessage().contains(manage ? "USE_CUSTOM_VIRTUAL_MACHINE" : "MANAGE_VIRTUAL_MACHINE"),
                    "exact missing permission reported");
            check(calls.get() == 0, "permission refusal must not create VM");
        }
    }

    private static void refusesUnsupportedPlatformAndUnavailableApi() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var api = new ReflectiveAvfVirtualMachineApi(new FakeVirtualizationContext(new VirtualMachineManager(
                (name, config) -> { calls.incrementAndGet(); return null; }, VirtualMachineManager.CAPABILITY_PROTECTED_VM)), getLoader());
        expect(AvfEngineException.HARDWARE_UNSUPPORTED, () -> api.startCustomVm(spec()));
        check(calls.get() == 0, "protected-only platform must not create custom VM");
        var absentManager = new ReflectiveAvfVirtualMachineApi(new FakeVirtualizationContext(null), getLoader());
        expect(AvfEngineException.VM_MANAGER_UNAVAILABLE, () -> absentManager.startCustomVm(spec()));
        var loader = new ClassLoader(getLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.endsWith("VirtualMachineCustomImageConfig$Builder")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        var absentApi = new ReflectiveAvfVirtualMachineApi(new FakeVirtualizationContext(null), loader);
        check(!absentApi.isReflectionAvailable(), "missing required builder is unavailable");
        expect(AvfEngineException.REFLECTION_UNAVAILABLE, () -> absentApi.startCustomVm(spec()));
    }

    private static void mapsCreateAndRunExceptionsAndClosesFailedRun() {
        for (Exception failure : new Exception[] {new SecurityException("custom denied"),
                new UnsupportedOperationException("unsupported"), new java.io.IOException("create refused")}) {
            var api = new ReflectiveAvfVirtualMachineApi(new FakeVirtualizationContext(new VirtualMachineManager(
                    (name, config) -> { throw failure; })), getLoader());
            String reason = failure instanceof SecurityException ? AvfEngineException.PERMISSION_DENIED
                    : failure instanceof UnsupportedOperationException ? AvfEngineException.HARDWARE_UNSUPPORTED
                    : AvfEngineException.VM_CREATE_FAILED;
            check(expect(reason, () -> api.startCustomVm(spec())).getCause() == failure, "create cause preserved");
        }
        Exception failure = new java.io.IOException("run failed after acquiring VM");
        VirtualMachine vm = VirtualMachine.withBehavior(() -> { throw failure; }, port -> null);
        var api = apiFor(vm);
        check(expect(AvfEngineException.VM_CREATE_FAILED, () -> api.startCustomVm(spec())).getCause() == failure,
                "run cause preserved");
        check(vm.closeCountForTest() == 1 && vm.getStatus() == VirtualMachine.STATUS_STOPPED,
                "failed run closes acquired VM and stops it");
    }

    private static void rejectsBadFdAndReclaimsFailedStreamConstruction() throws Exception {
        for (int stage = 0; stage < 3; stage++) {
            var pfd = memoryDescriptor();
            pfd.failDup = stage == 0;
            pfd.failInput = stage == 1;
            pfd.failOutput = stage == 2;
            var api = apiFor(VirtualMachine.withBehavior(() -> {}, port -> pfd));
            var handle = api.startCustomVm(spec());
            expect(AvfEngineException.VSOCK_CONNECT_FAILED, () -> api.connectVsock(handle, 9103));
            check(pfd.closeCallsForTest() == 1, "failed FD adoption closes original once");
            if (stage != 0) check(pfd.lastDuplicate.closeCallsForTest() == 1, "partial adoption releases duplicate");
            handle.stop();
        }
        var noFdApi = apiFor(VirtualMachine.withBehavior(() -> {}, port -> null));
        var noFdHandle = noFdApi.startCustomVm(spec());
        expect(AvfEngineException.VSOCK_CONNECT_FAILED, () -> noFdApi.connectVsock(noFdHandle, 9103));
        noFdHandle.stop();
        var pfd = memoryDescriptor();
        pfd.failClose = true;
        var api = apiFor(VirtualMachine.withBehavior(() -> {}, port -> pfd));
        var handle = api.startCustomVm(spec());
        var channel = api.connectVsock(handle, 9103);
        try { channel.close(); throw new AssertionError("close failure expected"); }
        catch (java.io.IOException failure) { check(failure.getSuppressed().length == 1, "both close errors preserved"); }
        channel.close();
        check(pfd.closeCallsForTest() == 1 && pfd.lastDuplicate.closeCallsForTest() == 1,
                "close failure still releases both owners once");
        handle.stop();
    }

    private static void injectedFdAdapterIsCheckedAndFailureClosesOriginal() throws Exception {
        for (boolean throwError : new boolean[] {true, false}) {
            var pfd = memoryDescriptor();
            var vm = VirtualMachine.withBehavior(() -> {}, port -> pfd);
            var context = new FakeVirtualizationContext(new VirtualMachineManager((name, config) -> vm));
            var api = new ReflectiveAvfVirtualMachineApi(context, getLoader(), null, descriptor -> {
                check(descriptor == pfd, "verified original PFD goes to App adapter");
                if (throwError) throw new java.io.IOException("injected adoption failure");
                return null;
            });
            var handle = api.startCustomVm(spec());
            expect(AvfEngineException.VSOCK_CONNECT_FAILED, () -> api.connectVsock(handle, 9103));
            check(pfd.closeCallsForTest() == 1, "failed injected adoption reclaims original");
            handle.stop();
        }
    }

    private static void closedAndForeignHandlesCannotConnect() {
        var connections = new java.util.concurrent.atomic.AtomicInteger();
        var vm = VirtualMachine.withBehavior(() -> {}, port -> { connections.incrementAndGet(); return memoryDescriptor(); });
        var api = apiFor(vm);
        var handle = api.startCustomVm(spec());
        expect(AvfEngineException.VSOCK_CONNECT_FAILED, () -> api.connectVsock(handle, 0));
        expect(AvfEngineException.VSOCK_CONNECT_FAILED, () -> apiFor(vm).connectVsock(handle, 9103));
        handle.stop();
        expect(AvfEngineException.VSOCK_CONNECT_FAILED, () -> api.connectVsock(handle, 9103));
        check(connections.get() == 0, "invalid, foreign or closed handle never reaches guest");
    }

    private static void streamPermissionRefusalIsNotReportedAsTransientVsock() {
        var pfd = memoryDescriptor();
        pfd.denyStreams = true;
        var api = apiFor(VirtualMachine.withBehavior(() -> {}, port -> pfd));
        var handle = api.startCustomVm(spec());
        expect(AvfEngineException.PERMISSION_DENIED, () -> api.connectVsock(handle, 9103));
        check(pfd.closeCallsForTest() == 1 && pfd.lastDuplicate.closeCallsForTest() == 1,
                "permission refusal still closes original and duplicate");
        handle.stop();
    }

    private static void rejectsAnInventedRunReturnType() {
        ClassLoader wrongShape = new ClassLoader(getLoader()) {
            @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.equals("android.system.virtualmachine.VirtualMachine")) return InventedVm.class;
                return super.loadClass(name);
            }
        };
        var api = new ReflectiveAvfVirtualMachineApi(new FakeVirtualizationContext(null), wrongShape);
        check(!api.isReflectionAvailable(), "boolean run stub must not be accepted as the official void API");
        expect(AvfEngineException.REFLECTION_UNAVAILABLE, () -> api.startCustomVm(spec()));
    }
    public static final class InventedVm {
        public boolean run() { return false; }
        public int getStatus() { return 1; }
        public void stop() { }
        public void close() { }
        public android.os.ParcelFileDescriptor connectVsock(long port) { return null; }
    }

    private static void platformStopIsReachedDuringBlockedOfficialConnect() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pfd = memoryDescriptor();
        var connects = new java.util.concurrent.atomic.AtomicInteger();
        VirtualMachine vm = VirtualMachine.withBehavior(() -> {}, port -> {
            connects.incrementAndGet();
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("fixture connect interrupted", failure);
            }
            return pfd;
        });
        var api = apiFor(vm);
        var session = new AvfVirtualMachineEngine(api, 100, 200).launch(spec());
        var connection = concurrent(() -> session.graphicsChannel());
        check(entered.await(1, java.util.concurrent.TimeUnit.SECONDS), "official long connect must be blocked");
        long started = System.nanoTime();
        var stopping = concurrent(() -> { session.stop(); return null; });
        try {
            stopping.get(1, java.util.concurrent.TimeUnit.SECONDS);
            check(vm.stopCountForTest() == 1 && vm.closeCountForTest() == 1,
                    "STOP must reach official-shaped VM stop and close while connect is blocked");
            check(System.nanoTime() - started <= java.util.concurrent.TimeUnit.SECONDS.toNanos(1),
                    "STOP must finish within one second without releasing connect latch");
            check(!connection.isDone(), "connect must remain blocked during STOP");
            System.out.println("AVF_OFFICIAL_BLOCKED_CONNECT_STOP elapsed_ns=" + (System.nanoTime() - started));
        } finally {
            release.countDown();
            stopping.get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
        expectClosed(connection);
        check(pfd.closeCallsForTest() == 1 && pfd.lastDuplicate == null,
                "PFD arriving after STOP must close once before stream adoption");
        check(vm.stopCountForTest() == 1 && vm.closeCountForTest() == 1,
                "platform stop and close called once");
        expect(AvfEngineException.VSOCK_CONNECT_FAILED, () -> session.graphicsChannel());
        check(connects.get() == 1, "closed graphics cache must not reconnect");
    }

    private static void stopDuringFdAdoptionRejectsLateStreamsWithoutDoubleClose() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pfd = memoryDescriptor();
        VirtualMachine vm = VirtualMachine.withBehavior(() -> {}, port -> pfd);
        var api = new ReflectiveAvfVirtualMachineApi(new FakeVirtualizationContext(
                new VirtualMachineManager((name, config) -> vm)), getLoader(), null, descriptor -> {
            var original = (android.os.ParcelFileDescriptor) descriptor;
            var duplicate = original.dup();
            var input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(original);
            var output = new android.os.ParcelFileDescriptor.AutoCloseOutputStream(duplicate);
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                input.close();
                output.close();
                throw new java.io.IOException(failure);
            }
            return new AvfVirtualMachineApi.AvfVsockChannel() {
                public java.io.InputStream inputStream() { return input; }
                public java.io.OutputStream outputStream() { return output; }
                public void close() throws java.io.IOException { try { output.close(); } finally { input.close(); } }
            };
        });
        var handle = api.startCustomVm(spec());
        var connecting = concurrent(() -> api.connectVsock(handle, 9103));
        check(entered.await(1, java.util.concurrent.TimeUnit.SECONDS), "FD adoption must really be in flight");
        var stopping = concurrent(() -> { handle.stop(); return null; });
        try {
            stopping.get(1, java.util.concurrent.TimeUnit.SECONDS);
            check(vm.stopCountForTest() == 1 && vm.closeCountForTest() == 1,
                    "platform stop cannot wait for FD adoption");
            check(pfd.closeCallsForTest() == 0 && pfd.lastDuplicate.closeCallsForTest() == 0,
                    "STOP must not race-close descriptors while the adapter constructs streams");
        } finally { release.countDown(); }
        expectClosed(connecting);
        check(pfd.closeCallsForTest() == 1 && pfd.lastDuplicate.closeCallsForTest() == 1,
                "late adopted stream owner closes both descriptors once");
        handle.stop();
        check(vm.closeCountForTest() == 1, "repeat STOP cannot close platform twice");
    }

    private static void handleStopOwnsPublishedPfdExactlyOnce() throws Exception {
        var pfd = memoryDescriptor();
        var vm = VirtualMachine.withBehavior(() -> {}, port -> pfd);
        var api = apiFor(vm);
        var handle = api.startCustomVm(spec());
        var channel = api.connectVsock(handle, 9103);
        handle.stop();
        channel.close();
        channel.inputStream().close();
        channel.outputStream().close();
        check(pfd.closeCallsForTest() == 1 && pfd.lastDuplicate.closeCallsForTest() == 1,
                "published PFD owner released once by handle STOP and caller close");
        expect(AvfEngineException.VSOCK_CONNECT_FAILED, () -> api.connectVsock(handle, 9103));
    }

    private static <T> java.util.concurrent.FutureTask<T> concurrent(java.util.concurrent.Callable<T> action) {
        var future = new java.util.concurrent.FutureTask<T>(action);
        Thread thread = new Thread(future, "avf-official-api-concurrency-test");
        thread.setDaemon(true);
        thread.start();
        return future;
    }
    private static void expectClosed(java.util.concurrent.FutureTask<?> task) throws Exception {
        try { task.get(1, java.util.concurrent.TimeUnit.SECONDS); throw new AssertionError("closed connection expected"); }
        catch (java.util.concurrent.ExecutionException failure) {
            check(failure.getCause() instanceof AvfEngineException, "engine failure expected");
            check(failure.getCause().getMessage().contains("closed"), "closed failure must be explicit");
        }
    }

    private static void coldRestoresMatchingPersistentVmWithoutCreatingAgain() throws Exception {
        var descriptors = new java.util.ArrayList<android.os.ParcelFileDescriptor>();
        VirtualMachine vm = VirtualMachine.withBehavior(() -> {}, port -> {
            var pfd = memoryDescriptor();
            descriptors.add(pfd);
            return pfd;
        });
        var manager = new VirtualMachineManager((name, config) -> vm);
        var context = new FakeVirtualizationContext(manager);
        var firstApi = new ReflectiveAvfVirtualMachineApi(context, getLoader());
        var first = firstApi.startCustomVm(spec());
        firstApi.connectVsock(first, 9103);
        first.stop();
        check(descriptors.get(0).releasedForTest() == 2, "first run resources reclaimed before cold restore");
        var restoredApi = new ReflectiveAvfVirtualMachineApi(context, getLoader());
        var restored = restoredApi.startCustomVm(spec());
        check(vm.runCountForTest() == 2 && manager.createCallsForTest() == 1 && manager.getCallsForTest() == 2,
                "second launch retrieves and runs the same persistent VM, not create again");
        check(restored.reflectionNotes().contains("persistent-vm: reused"), "reuse must be explicit");
        restoredApi.connectVsock(restored, 9103);
        restored.stop();
        check(descriptors.get(1).releasedForTest() == 2, "second run resources reclaimed");
        check(manager.deleteCallsForTest() == 0 && manager.getOrCreateCallsForTest() == 0,
                "cold restore cannot delete state or ignore supplied config");
    }

    private static void failedRunRetriesTheSamePersistentVmWithoutDeletingIt() throws Exception {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var pfd = memoryDescriptor();
        VirtualMachine vm = VirtualMachine.withBehavior(() -> {
            if (attempts.incrementAndGet() == 1) throw new java.io.IOException("first boot failed");
        }, port -> pfd);
        var manager = new VirtualMachineManager((name, config) -> vm);
        var context = new FakeVirtualizationContext(manager);
        var api = new ReflectiveAvfVirtualMachineApi(context, getLoader());
        expect(AvfEngineException.VM_CREATE_FAILED, () -> api.startCustomVm(spec()));
        check(vm.closeCountForTest() == 1 && vm.getStatus() == VirtualMachine.STATUS_STOPPED,
                "failed run closes runtime resources and leaves persistent configuration");
        var retried = new ReflectiveAvfVirtualMachineApi(context, getLoader());
        var handle = retried.startCustomVm(spec());
        check(vm.runCountForTest() == 2 && manager.createCallsForTest() == 1,
                "retry must get and rerun the same persisted VM");
        retried.connectVsock(handle, 9103);
        handle.stop();
        check(pfd.releasedForTest() == 2, "retry FD ownership reclaimed");
        check(manager.deleteCallsForTest() == 0 && manager.getOrCreateCallsForTest() == 0,
                "retry must not delete or ignore config");
    }

    private static void mismatchedStoredConfigsNeverRunOrDelete() throws Exception {
        String[] fields = {"getKernelPath", "getInitrdPath", "getImagePath", "getMemoryBytes",
                "getCpuTopology", "VM identity", "useNetwork", "getParams", "getBootloaderPath",
                "root disk count/type", "getPartitions", "VM config type"};
        for (int variant = 0; variant < fields.length; variant++) {
            VirtualMachine vm = VirtualMachine.withBehavior(() -> {}, port -> {
                throw new AssertionError("mismatch cannot open a descriptor");
            });
            var manager = new VirtualMachineManager((name, config) -> vm);
            var context = new FakeVirtualizationContext(manager);
            var image = new android.system.virtualmachine.VirtualMachineCustomImageConfig.Builder()
                    .setName("official-shape")
                    .setKernelPath(variant == 0 ? "/wrong-kernel" : "/kernel")
                    .setInitrdPath(variant == 1 ? "/wrong-initrd" : "/initrd")
                    .useNetwork(variant != 6);
            for (String param : spec().guestCmdline().split("\\s+")) image.addParam(param);
            if (variant == 7) image.addParam("unapproved=1");
            if (variant == 8) image.setBootloaderPath("/unexpected-bootloader");
            var disk = android.system.virtualmachine.VirtualMachineCustomImageConfig.Disk.RWDisk(
                    variant == 2 ? "/other-root.raw" : "/root.raw");
            if (variant == 10) disk.addPartition(new android.system.virtualmachine.VirtualMachineCustomImageConfig.Partition(
                    "unexpected", "/other-partition", true, null));
            image.addDisk(disk);
            if (variant == 9) image.addDisk(android.system.virtualmachine.VirtualMachineCustomImageConfig.Disk.RWDisk("/extra-disk"));
            var stored = new VirtualMachineConfig.Builder(context).setCustomImageConfig(image.build())
                    .setProtectedVm(variant == 5)
                    .setMemoryBytes(variant == 3 ? 536870912L : 402653184L)
                    .setCpuTopology(variant == 4 ? VirtualMachineConfig.CPU_TOPOLOGY_MATCH_HOST
                            : VirtualMachineConfig.CPU_TOPOLOGY_ONE_CPU).build();
            manager.create(spec().instanceId(), stored);
            if (variant == 11) vm.recordConfigForTest(null);
            var api = new ReflectiveAvfVirtualMachineApi(context, getLoader());
            var failure = expect(AvfEngineException.CONFIG_MISMATCH, () -> api.startCustomVm(spec()));
            check(failure.getMessage().contains(fields[variant]), "mismatch field must be explicit: " + fields[variant]);
            check(!failure.getMessage().contains("/wrong") && !failure.getMessage().contains("/other"),
                    "mismatch diagnostics do not expose disk/kernel values");
            check(vm.runCountForTest() == 0 && vm.stopCountForTest() == 0 && vm.closeCountForTest() == 0,
                    "mismatch cannot run, stop or close another persistent VM");
            check(manager.createCallsForTest() == 1 && manager.deleteCallsForTest() == 0
                    && manager.getOrCreateCallsForTest() == 0, "mismatch preserves same-name persistent state");
            check(vm.getConfig() == (variant == 11 ? null : stored), "stored config object not rewritten");
        }
    }

    private static void runningPersistentVmIsNotStoppedByAnotherLaunch() {
        VirtualMachine vm = VirtualMachine.withBehavior(() -> {}, port -> memoryDescriptor());
        var manager = new VirtualMachineManager((name, config) -> vm);
        var context = new FakeVirtualizationContext(manager);
        var ownerApi = new ReflectiveAvfVirtualMachineApi(context, getLoader());
        var owner = ownerApi.startCustomVm(spec());
        var otherApi = new ReflectiveAvfVirtualMachineApi(context, getLoader());
        expect(AvfEngineException.VM_CREATE_FAILED, () -> otherApi.startCustomVm(spec()));
        check(vm.getStatus() == VirtualMachine.STATUS_RUNNING && vm.runCountForTest() == 1
                && vm.stopCountForTest() == 0 && vm.closeCountForTest() == 0,
                "running persistent VM is untouched by refused duplicate launch");
        owner.stop();
        check(manager.deleteCallsForTest() == 0, "duplicate launch cannot delete VM");
    }

    private static void unreadablePersistentConfigIsRejectedWithoutRunningOrDeleting() {
        VirtualMachine vm = VirtualMachine.withBehavior(() -> {}, port -> memoryDescriptor());
        var manager = new VirtualMachineManager((name, config) -> vm);
        var context = new FakeVirtualizationContext(manager);
        var first = new ReflectiveAvfVirtualMachineApi(context, getLoader()).startCustomVm(spec());
        first.stop();
        int runs = vm.runCountForTest();
        int closes = vm.closeCountForTest();
        for (RuntimeException refusal : new RuntimeException[] {
                new IllegalStateException("config cannot be read"), new SecurityException("read permission revoked")}) {
            vm.configReadFailureForTest = refusal;
            var restore = new ReflectiveAvfVirtualMachineApi(context, getLoader());
            String reason = refusal instanceof SecurityException ? AvfEngineException.PERMISSION_DENIED
                    : AvfEngineException.CONFIG_MISMATCH;
            check(expect(reason, () -> restore.startCustomVm(spec())).getCause() == refusal,
                    "getConfig failure must preserve its specific cause");
            check(vm.runCountForTest() == runs && vm.closeCountForTest() == closes
                    && manager.deleteCallsForTest() == 0, "unverifiable persistent VM remains untouched");
        }
        vm.configReadFailureForTest = null;
    }

    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static AvfEngineException expect(String reason, Action action) {
        try { action.run(); throw new AssertionError("expected " + reason); }
        catch (AvfEngineException failure) { check(reason.equals(failure.reason()), "failure reason " + failure); return failure; }
        catch (Exception failure) { throw new AssertionError("unexpected exception", failure); }
    }

    private static android.os.ParcelFileDescriptor memoryDescriptor() {
        return new android.os.ParcelFileDescriptor(new java.io.ByteArrayInputStream(new byte[0]),
                new java.io.ByteArrayOutputStream(), () -> {});
    }

    private static ReflectiveAvfVirtualMachineApi apiFor(VirtualMachine vm) {
        return new ReflectiveAvfVirtualMachineApi(new FakeVirtualizationContext(
                new VirtualMachineManager((name, config) -> vm)), getLoader());
    }

    private static ClassLoader getLoader() { return ReflectiveAvfVirtualMachineApiTest.class.getClassLoader(); }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
