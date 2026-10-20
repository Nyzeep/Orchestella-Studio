package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator;
import io.github.nyzeep.orchestella.runtime.capability.AvfPlatformAccess;
import io.github.nyzeep.orchestella.runtime.capability.AvfPlatformFacts;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;

/**
 * The one reflective adapter for the restricted AVF platform surface. It owns
 * the class/method shape, the two capability bits, permission reads and the
 * typed failure classification. The App probe and the launch engine both call
 * {@link #observe()}, so neither keeps a second copy of those facts.
 *
 * <p>The hidden-API exemption is applied once, before the first platform class
 * load. A failed exemption is recorded, not retried: the read continues and is
 * classified honestly by what the platform actually did.
 */
public final class ReflectiveAvfPlatformAccess implements AvfPlatformAccess {
    /** AOSP VirtualMachineManager capability bits. */
    public static final int CAPABILITY_PROTECTED_VM = 1;
    public static final int CAPABILITY_NON_PROTECTED_VM = 2;
    public static final String MANAGE_PERMISSION =
            "android.permission.MANAGE_VIRTUAL_MACHINE";
    public static final String CUSTOM_PERMISSION =
            "android.permission.USE_CUSTOM_VIRTUAL_MACHINE";

    private static final String CONTEXT_CLASS = "android.content.Context";
    private static final String MANAGER_CLASS =
            "android.system.virtualmachine.VirtualMachineManager";
    private static final String CAPABILITY_SOURCE = "VirtualMachineManager.getCapabilities";
    private static final String ACTOR = "app";
    private static final int PERMISSION_GRANTED = 0;

    private final Object context;
    private final PlatformBridge bridge;
    private final Runnable exemptionInstaller;
    private final String romBinding;
    private boolean exemptionAttempted;
    private AvfPlatformFacts.ExemptionState exemptionState =
            AvfPlatformFacts.ExemptionState.NOT_ATTEMPTED;
    private PlatformShape shape;

    public ReflectiveAvfPlatformAccess(Object context, ClassLoader classLoader) {
        this(context, classLoader, null);
    }

    public ReflectiveAvfPlatformAccess(Object context, ClassLoader classLoader,
            Runnable exemptionInstaller) {
        this(context, classLoader, exemptionInstaller, "platform-observation");
    }

    public ReflectiveAvfPlatformAccess(Object context, ClassLoader classLoader,
            Runnable exemptionInstaller, String romBinding) {
        this(context, new ReflectiveBridge(Objects.requireNonNull(classLoader, "classLoader")),
                exemptionInstaller, romBinding);
    }

    /** Test seam: inject the raw platform calls without mocking a global class loader. */
    ReflectiveAvfPlatformAccess(Object context, PlatformBridge bridge,
            Runnable exemptionInstaller, String romBinding) {
        this.context = Objects.requireNonNull(context, "context");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.exemptionInstaller = exemptionInstaller;
        if (romBinding == null || romBinding.isBlank()) {
            throw new IllegalArgumentException("romBinding must not be blank");
        }
        this.romBinding = romBinding;
    }

    @Override
    public AvfPlatformFacts observe() {
        PlatformShape platform;
        try {
            platform = loadPlatformShape();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            AvfPlatformFacts.AccessState state = classify(failure);
            // Shape validation starts by loading both classes. A hidden method
            // or policy denial therefore proves the manager class exists; only
            // an absent class/linkage failure is evidence of no platform.
            boolean managerPresent = state == AvfPlatformFacts.AccessState.HIDDEN_API_BLOCKED
                    || state == AvfPlatformFacts.AccessState.PERMISSION_DENIED;
            return facts(state,
                    AvfCapabilityEvaluator.PermissionState.UNKNOWN,
                    AvfCapabilityEvaluator.PermissionState.UNKNOWN,
                    unknownMode(), managerPresent);
        }
        // The manager class exists at this point. Probe the service before the
        // permission gate so an OEM ROM without the feature string can still be
        // recognized as a platform that needs authorization, not as "no
        // virtualization". A failed probe is not evidence of absence.
        boolean managerPresent = true;
        try {
            Object manager = bridge.getSystemService(context, platform.context(), platform.manager());
            managerPresent = platform.manager().isInstance(manager);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            managerPresent = true;
        }
        AvfCapabilityEvaluator.PermissionState manage;
        AvfCapabilityEvaluator.PermissionState custom;
        try {
            manage = readPermission(platform.context(), MANAGE_PERMISSION);
            custom = readPermission(platform.context(), CUSTOM_PERMISSION);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return facts(classify(failure),
                    AvfCapabilityEvaluator.PermissionState.UNKNOWN,
                    AvfCapabilityEvaluator.PermissionState.UNKNOWN,
                    unknownMode(), managerPresent);
        }
        if (manage != AvfCapabilityEvaluator.PermissionState.GRANTED
                || custom != AvfCapabilityEvaluator.PermissionState.GRANTED) {
            return facts(AvfPlatformFacts.AccessState.PERMISSION_DENIED,
                    manage, custom, unknownMode(), managerPresent);
        }
        try {
            Object manager = bridge.getSystemService(context, platform.context(), platform.manager());
            if (!platform.manager().isInstance(manager)) {
                return facts(AvfPlatformFacts.AccessState.MODE_UNKNOWN,
                        manage, custom, unknownMode(), false);
            }
            int capabilities = bridge.getCapabilities(manager, platform.manager());
            AvfCapabilityEvaluator.AvfModeFacts modeFacts = modeFacts(capabilities);
            AvfPlatformFacts.AccessState access =
                    (capabilities & CAPABILITY_NON_PROTECTED_VM) != 0
                            ? AvfPlatformFacts.AccessState.AVAILABLE
                            : AvfPlatformFacts.AccessState.MODE_UNSUPPORTED;
            return facts(access, manage, custom, modeFacts, managerPresent);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return facts(classify(failure), manage, custom, unknownMode(), managerPresent);
        }
    }

    @Override
    public boolean isReflectionAvailable() {
        try {
            loadPlatformShape();
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return false;
        }
    }

    /** Loads and validates the platform shape once; applies the exemption first. */
    public synchronized PlatformShape loadPlatformShape() throws ReflectiveOperationException {
        ensureExemption();
        if (shape != null) {
            return shape;
        }
        Class<?> contextClass = bridge.loadClass(CONTEXT_CLASS);
        Class<?> managerClass = bridge.loadClass(MANAGER_CLASS);
        if (contextClass.getMethod("getSystemService", Class.class).getReturnType() != Object.class
                || contextClass.getMethod("checkSelfPermission", String.class).getReturnType() != int.class
                || managerClass.getMethod("getCapabilities").getReturnType() != int.class) {
            throw new NoSuchMethodException("official platform signatures are required");
        }
        int protectedBit = managerClass.getField("CAPABILITY_PROTECTED_VM").getInt(null);
        int nonProtectedBit = managerClass.getField("CAPABILITY_NON_PROTECTED_VM").getInt(null);
        if (protectedBit != CAPABILITY_PROTECTED_VM
                || nonProtectedBit != CAPABILITY_NON_PROTECTED_VM) {
            throw new NoSuchFieldException("unexpected capability bit constants");
        }
        shape = new PlatformShape(contextClass, managerClass);
        return shape;
    }

    public AvfPlatformFacts.ExemptionState exemptionState() {
        return exemptionState;
    }

    private synchronized void ensureExemption() {
        if (exemptionAttempted) {
            return;
        }
        exemptionAttempted = true;
        if (exemptionInstaller == null) {
            exemptionState = AvfPlatformFacts.ExemptionState.NOT_ATTEMPTED;
            return;
        }
        try {
            exemptionInstaller.run();
            exemptionState = AvfPlatformFacts.ExemptionState.INSTALLED;
        } catch (RuntimeException | LinkageError failure) {
            // The exemption is not a permission and not a capability. Record the
            // failure; the following read decides the typed access state.
            exemptionState = AvfPlatformFacts.ExemptionState.FAILED;
        }
    }

    private AvfCapabilityEvaluator.PermissionState readPermission(Class<?> contextClass, String permission)
            throws ReflectiveOperationException {
        int value = bridge.checkSelfPermission(context, contextClass, permission);
        return value == PERMISSION_GRANTED
                ? AvfCapabilityEvaluator.PermissionState.GRANTED
                : AvfCapabilityEvaluator.PermissionState.DENIED;
    }

    private AvfCapabilityEvaluator.AvfModeFacts modeFacts(int capabilities) {
        return new AvfCapabilityEvaluator.AvfModeFacts(
                (capabilities & CAPABILITY_PROTECTED_VM) != 0
                        ? AvfCapabilityEvaluator.VmSupport.SUPPORTED
                        : AvfCapabilityEvaluator.VmSupport.UNSUPPORTED,
                (capabilities & CAPABILITY_NON_PROTECTED_VM) != 0
                        ? AvfCapabilityEvaluator.VmSupport.SUPPORTED
                        : AvfCapabilityEvaluator.VmSupport.UNSUPPORTED,
                CAPABILITY_SOURCE, ACTOR, System.currentTimeMillis(), romBinding);
    }

    private AvfCapabilityEvaluator.AvfModeFacts unknownMode() {
        return AvfCapabilityEvaluator.AvfModeFacts.unknown(
                CAPABILITY_SOURCE, ACTOR, System.currentTimeMillis(), romBinding);
    }

    private AvfPlatformFacts facts(AvfPlatformFacts.AccessState access,
            AvfCapabilityEvaluator.PermissionState manage,
            AvfCapabilityEvaluator.PermissionState custom,
            AvfCapabilityEvaluator.AvfModeFacts modeFacts,
            boolean managerPresent) {
        return new AvfPlatformFacts(access, exemptionState, manage, custom, modeFacts, managerPresent);
    }

    /**
     * SecurityException is a permission/policy answer; a missing member on an
     * existing class is what hidden-API enforcement looks like; a missing class
     * or linkage failure is an absent platform surface. Everything else is an
     * unreadable mode, never a guessed "unsupported".
     */
    static AvfPlatformFacts.AccessState classify(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof InvocationTargetException wrapped && wrapped.getCause() != null) {
            cause = wrapped.getCause();
        }
        if (cause instanceof SecurityException) {
            return AvfPlatformFacts.AccessState.PERMISSION_DENIED;
        }
        if (cause instanceof ClassNotFoundException || cause instanceof NoClassDefFoundError) {
            return AvfPlatformFacts.AccessState.REFLECTION_UNAVAILABLE;
        }
        if (cause instanceof NoSuchMethodException
                || cause instanceof NoSuchFieldException
                || cause instanceof IllegalAccessException) {
            return AvfPlatformFacts.AccessState.HIDDEN_API_BLOCKED;
        }
        if (cause instanceof UnsupportedOperationException) {
            return AvfPlatformFacts.AccessState.MODE_UNSUPPORTED;
        }
        if (cause instanceof LinkageError) {
            return AvfPlatformFacts.AccessState.REFLECTION_UNAVAILABLE;
        }
        return AvfPlatformFacts.AccessState.MODE_UNKNOWN;
    }

    /** The validated platform classes, cached per adapter instance. */
    public record PlatformShape(Class<?> context, Class<?> manager) {}

    /** Raw platform calls; the production implementation is the only reflective one. */
    interface PlatformBridge {
        Class<?> loadClass(String name) throws ClassNotFoundException;

        Object getSystemService(Object context, Class<?> contextClass, Class<?> managerClass)
                throws ReflectiveOperationException;

        int getCapabilities(Object manager, Class<?> managerClass)
                throws ReflectiveOperationException;

        int checkSelfPermission(Object context, Class<?> contextClass, String permission)
                throws ReflectiveOperationException;
    }

    private static final class ReflectiveBridge implements PlatformBridge {
        private final ClassLoader classLoader;

        private ReflectiveBridge(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            return classLoader.loadClass(name);
        }

        @Override
        public Object getSystemService(Object context, Class<?> contextClass, Class<?> managerClass)
                throws ReflectiveOperationException {
            return contextClass.getMethod("getSystemService", Class.class)
                    .invoke(context, managerClass);
        }

        @Override
        public int getCapabilities(Object manager, Class<?> managerClass)
                throws ReflectiveOperationException {
            Object value = managerClass.getMethod("getCapabilities").invoke(manager);
            if (!(value instanceof Integer capabilities)) {
                throw new NoSuchMethodException("getCapabilities did not return int");
            }
            return capabilities;
        }

        @Override
        public int checkSelfPermission(Object context, Class<?> contextClass, String permission)
                throws ReflectiveOperationException {
            Object value = contextClass.getMethod("checkSelfPermission", String.class)
                    .invoke(context, permission);
            if (!(value instanceof Integer state)) {
                throw new NoSuchMethodException("checkSelfPermission did not return int");
            }
            return state;
        }
    }
}
