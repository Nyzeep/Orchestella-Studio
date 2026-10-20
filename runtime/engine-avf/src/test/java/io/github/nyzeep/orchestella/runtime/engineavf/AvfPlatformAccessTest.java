package io.github.nyzeep.orchestella.runtime.engineavf;

import android.system.virtualmachine.FakeVirtualizationContext;
import android.system.virtualmachine.VirtualMachineManager;
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator;
import io.github.nyzeep.orchestella.runtime.capability.AvfPlatformFacts;
import java.util.ArrayList;
import java.util.List;

/**
 * One typed platform-access matrix for the probe and the engine. Every state
 * is driven through the same adapter seam; no Android framework call is
 * mocked globally.
 */
public final class AvfPlatformAccessTest {
    public static void main(String[] arguments) {
        availableReadsBothModesAndPermissions();
        hiddenApiBlockedIsItsOwnState();
        permissionDeniedSkipsTheCapabilityRead();
        protectedOnlyIsModeUnsupported();
        missingPlatformClassIsReflectionUnavailable();
        unexpectedReadFailureIsModeUnknown();
        securityExceptionIsPermissionDenied();
        exemptionRunsOnceBeforeEveryPlatformClassLoad();
        exemptionFailureIsRecordedWithoutGuessing();
        engineMapsTheSameTypedState();
        System.out.println("AVF_PLATFORM_ACCESS_TEST_OK states=6 exemption=2 engine_mapping=3");
    }

    private static void availableReadsBothModesAndPermissions() {
        RecordingBridge bridge = new RecordingBridge(getLoader(), manager(), new ArrayList<>());
        ReflectiveAvfPlatformAccess access = access(bridge, null);
        AvfPlatformFacts facts = access.observe();

        check(facts.accessState() == AvfPlatformFacts.AccessState.AVAILABLE, "both capability bits are available");
        check(facts.managePermission() == AvfCapabilityEvaluator.PermissionState.GRANTED, "MANAGE is read");
        check(facts.customPermission() == AvfCapabilityEvaluator.PermissionState.GRANTED, "CUSTOM is read");
        check(facts.modeFacts().protectedVm() == AvfCapabilityEvaluator.VmSupport.SUPPORTED, "protected bit");
        check(facts.modeFacts().nonProtectedVm() == AvfCapabilityEvaluator.VmSupport.SUPPORTED, "non-protected bit");
        check(access.isReflectionAvailable(), "platform shape is available");
    }

    private static void hiddenApiBlockedIsItsOwnState() {
        RecordingBridge bridge = new RecordingBridge(getLoader(), manager(), new ArrayList<>());
        bridge.capabilityFailure = new NoSuchMethodException("getCapabilities");
        AvfPlatformFacts facts = access(bridge, null).observe();

        check(facts.accessState() == AvfPlatformFacts.AccessState.HIDDEN_API_BLOCKED,
                "a hidden method is not reported as an unknown mode");
        check(facts.exemptionState() == AvfPlatformFacts.ExemptionState.NOT_ATTEMPTED,
                "no exemption hook was supplied");
    }

    private static void permissionDeniedSkipsTheCapabilityRead() {
        RecordingBridge bridge = new RecordingBridge(getLoader(), manager(), new ArrayList<>());
        bridge.permissionResult = -1;
        AvfPlatformFacts facts = access(bridge, null).observe();

        check(facts.accessState() == AvfPlatformFacts.AccessState.PERMISSION_DENIED, "permission gate first");
        check(bridge.capabilityReads == 0, "unreadable permissions never call getCapabilities");
        check(facts.managePermission() == AvfCapabilityEvaluator.PermissionState.DENIED, "MANAGE denial is kept");
        check(facts.customPermission() == AvfCapabilityEvaluator.PermissionState.DENIED, "CUSTOM denial is kept");
    }

    private static void protectedOnlyIsModeUnsupported() {
        RecordingBridge bridge = new RecordingBridge(getLoader(), manager(), new ArrayList<>());
        bridge.capabilityBits = ReflectiveAvfPlatformAccess.CAPABILITY_PROTECTED_VM;
        AvfPlatformFacts facts = access(bridge, null).observe();

        check(facts.accessState() == AvfPlatformFacts.AccessState.MODE_UNSUPPORTED, "protected-only is typed");
        check(facts.modeFacts().protectedVm() == AvfCapabilityEvaluator.VmSupport.SUPPORTED, "protected is visible");
        check(facts.modeFacts().nonProtectedVm() == AvfCapabilityEvaluator.VmSupport.UNSUPPORTED,
                "non-protected is explicitly unsupported");
    }

    private static void missingPlatformClassIsReflectionUnavailable() {
        RecordingBridge bridge = new RecordingBridge(getLoader(), manager(), new ArrayList<>());
        bridge.classFailure = new ClassNotFoundException("android.system.virtualmachine.VirtualMachineManager");
        AvfPlatformFacts facts = access(bridge, null).observe();

        check(facts.accessState() == AvfPlatformFacts.AccessState.REFLECTION_UNAVAILABLE,
                "a missing platform class is not a mode guess");
    }

    private static void unexpectedReadFailureIsModeUnknown() {
        RecordingBridge bridge = new RecordingBridge(getLoader(), manager(), new ArrayList<>());
        bridge.capabilityFailure = new IllegalStateException("synthetic read failure");
        AvfPlatformFacts facts = access(bridge, null).observe();

        check(facts.accessState() == AvfPlatformFacts.AccessState.MODE_UNKNOWN, "unexpected reads stay unknown");
    }

    private static void securityExceptionIsPermissionDenied() {
        RecordingBridge bridge = new RecordingBridge(getLoader(), manager(), new ArrayList<>());
        bridge.capabilityFailure = new SecurityException("synthetic platform denial");
        AvfPlatformFacts facts = access(bridge, null).observe();

        check(facts.accessState() == AvfPlatformFacts.AccessState.PERMISSION_DENIED,
                "SecurityException is a permission state, not an unknown mode");
    }

    private static void exemptionRunsOnceBeforeEveryPlatformClassLoad() {
        List<String> events = new ArrayList<>();
        RecordingBridge bridge = new RecordingBridge(getLoader(), manager(), events);
        ReflectiveAvfPlatformAccess access = new ReflectiveAvfPlatformAccess(
                new FakeVirtualizationContext(manager()), bridge, () -> events.add("exempt"), "test-rom");

        AvfPlatformFacts first = access.observe();
        AvfPlatformFacts second = access.observe();

        check(first.exemptionState() == AvfPlatformFacts.ExemptionState.INSTALLED, "exemption is recorded");
        check(second.exemptionState() == AvfPlatformFacts.ExemptionState.INSTALLED, "state stays installed");
        check(count(events, "exempt") == 1, "exemption runs exactly once");
        check(!events.isEmpty() && "exempt".equals(events.get(0)), "exemption precedes the first class load");
        check(events.indexOf("exempt") < indexOfPrefix(events, "load:"), "ordering is exempt -> load");
    }

    private static void exemptionFailureIsRecordedWithoutGuessing() {
        List<String> events = new ArrayList<>();
        RecordingBridge bridge = new RecordingBridge(getLoader(), manager(), events);
        ReflectiveAvfPlatformAccess access = new ReflectiveAvfPlatformAccess(
                new FakeVirtualizationContext(manager()), bridge, () -> {
                    events.add("exempt");
                    throw new IllegalStateException("synthetic ART rejection");
                }, "test-rom");

        AvfPlatformFacts facts = access.observe();

        check(facts.exemptionState() == AvfPlatformFacts.ExemptionState.FAILED, "failure is recorded");
        check(facts.accessState() == AvfPlatformFacts.AccessState.AVAILABLE,
                "a failed exemption does not invent a mode failure");
    }

    private static void engineMapsTheSameTypedState() {
        VirtualMachineManager manager = manager();
        FakeVirtualizationContext context = new FakeVirtualizationContext(manager);

        RecordingBridge unsupported = new RecordingBridge(getLoader(), manager, new ArrayList<>());
        unsupported.capabilityBits = ReflectiveAvfPlatformAccess.CAPABILITY_PROTECTED_VM;
        expect(AvfEngineException.HARDWARE_UNSUPPORTED, () -> engine(context, unsupported).startCustomVm(spec()));

        RecordingBridge denied = new RecordingBridge(getLoader(), manager, new ArrayList<>());
        denied.permissionResult = -1;
        AvfEngineException deniedError = expect(AvfEngineException.PERMISSION_DENIED,
                () -> engine(context, denied).startCustomVm(spec()));
        check(deniedError.getMessage().contains("MANAGE_VIRTUAL_MACHINE"), "engine names the missing permission");

        RecordingBridge blocked = new RecordingBridge(getLoader(), manager, new ArrayList<>());
        blocked.capabilityFailure = new NoSuchMethodException("getCapabilities");
        expect(AvfEngineException.REFLECTION_UNAVAILABLE, () -> engine(context, blocked).startCustomVm(spec()));
    }

    private static ReflectiveAvfVirtualMachineApi engine(
            FakeVirtualizationContext context, RecordingBridge bridge) {
        ReflectiveAvfPlatformAccess access = new ReflectiveAvfPlatformAccess(
                context, bridge, null, "test-rom");
        return new ReflectiveAvfVirtualMachineApi(context, getLoader(), null, null, access);
    }

    private static AvfLaunchSpec spec() {
        return AvfLaunchSpec.builder("platform-access", "/kernel", "/initrd", "/root.raw")
                .memoryMb(256)
                .build();
    }

    private static ReflectiveAvfPlatformAccess access(RecordingBridge bridge, Runnable exemption) {
        return new ReflectiveAvfPlatformAccess(
                new FakeVirtualizationContext(manager()), bridge, exemption, "test-rom");
    }

    private static VirtualMachineManager manager() {
        return new VirtualMachineManager((name, config) -> null);
    }

    private static ClassLoader getLoader() {
        return AvfPlatformAccessTest.class.getClassLoader();
    }

    private static int count(List<String> events, String value) {
        int count = 0;
        for (String event : events) {
            if (value.equals(event)) count++;
        }
        return count;
    }

    private static int indexOfPrefix(List<String> events, String prefix) {
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index).startsWith(prefix)) return index;
        }
        return Integer.MAX_VALUE;
    }

    private static AvfEngineException expect(String reason, ThrowingAction action) {
        try {
            action.run();
            throw new AssertionError("expected " + reason);
        } catch (AvfEngineException failure) {
            check(reason.equals(failure.reason()), "expected " + reason + " but was " + failure.reason());
            return failure;
        }
    }

    private static void check(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }

    /**
     * The raw platform calls, faked at the adapter seam. The production bridge
     * below is the only reflective implementation.
     */
    private static final class RecordingBridge implements ReflectiveAvfPlatformAccess.PlatformBridge {
        private final ClassLoader loader;
        private final Object manager;
        private final List<String> events;
        private int capabilityBits =
                ReflectiveAvfPlatformAccess.CAPABILITY_PROTECTED_VM
                        | ReflectiveAvfPlatformAccess.CAPABILITY_NON_PROTECTED_VM;
        private int permissionResult;
        private int capabilityReads;
        private Throwable classFailure;
        private Throwable capabilityFailure;
        private Throwable permissionFailure;
        private Throwable serviceFailure;

        private RecordingBridge(ClassLoader loader, Object manager, List<String> events) {
            this.loader = loader;
            this.manager = manager;
            this.events = events;
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            events.add("load:" + name);
            if (classFailure instanceof ClassNotFoundException failure) throw failure;
            if (classFailure instanceof LinkageError failure) throw failure;
            return loader.loadClass(name);
        }

        @Override
        public Object getSystemService(Object context, Class<?> contextClass, Class<?> managerClass)
                throws ReflectiveOperationException {
            events.add("service");
            rethrow(serviceFailure);
            return manager;
        }

        @Override
        public int getCapabilities(Object manager, Class<?> managerClass)
                throws ReflectiveOperationException {
            events.add("capabilities");
            capabilityReads++;
            rethrow(capabilityFailure);
            return capabilityBits;
        }

        @Override
        public int checkSelfPermission(Object context, Class<?> contextClass, String permission)
                throws ReflectiveOperationException {
            events.add("permission:" + permission);
            rethrow(permissionFailure);
            return permissionResult;
        }

        private static void rethrow(Throwable failure) throws ReflectiveOperationException {
            if (failure instanceof ReflectiveOperationException reflective) throw reflective;
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        }
    }
}
