package io.github.nyzeep.orchestella.runtime.fastlane;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Pure-Java boundary for the protected Microdroid app-payload API.
 *
 * <p>The shape is the Android 14/15 {@code android.system.virtualmachine}
 * SystemApi: a payload APK config, {@code VirtualMachineManager}, and
 * {@code VirtualMachine.connectVsock(long)}. Reflection never grants a
 * permission; unavailable or hidden methods fail closed.
 *
 * <p>An existing VM is reused only when every configuration field matches and
 * it is stopped. A mismatch is refused instead of deleting encrypted storage.
 */
public final class ReflectiveFastLaneApi implements FastLaneVirtualMachineApi {
    private static final String PACKAGE = "android.system.virtualmachine.";
    private static final String MANAGE_PERMISSION = "android.permission.MANAGE_VIRTUAL_MACHINE";
    private static final int PERMISSION_GRANTED = 0;
    private static final int ERROR_PAYLOAD_VERIFICATION_FAILED = 1;
    private static final int ERROR_PAYLOAD_CHANGED = 2;
    private static final int ERROR_PAYLOAD_INVALID_CONFIG = 3;
    private static final int STOP_REASON_SHUTDOWN = 3;
    private static final int STOP_REASON_MICRODROID_PAYLOAD_HAS_CHANGED = 12;
    private static final int STOP_REASON_MICRODROID_PAYLOAD_VERIFICATION_FAILED = 13;
    private static final int STOP_REASON_MICRODROID_INVALID_PAYLOAD_CONFIG = 14;
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final long STOP_POLL_MS = 25L;
    private static final long STOP_FORCE_PROBE_MS = 500L;

    private final Object context;
    private final ClassLoader classLoader;

    public ReflectiveFastLaneApi(Object context, ClassLoader classLoader) {
        this.context = Objects.requireNonNull(context, "context");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    @Override
    public FastLaneHandle start(FastLaneLaunchSpec spec) throws FastLaneException {
        Objects.requireNonNull(spec, "spec");
        List<String> notes = new ArrayList<>();
        Shape shape;
        try {
            shape = loadShape();
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw error(FastLaneException.HIDDEN_API_BLOCKED,
                    "required protected-VM SystemApi is absent", failure);
        } catch (SecurityException denied) {
            throw error(FastLaneException.HIDDEN_API_BLOCKED,
                    "protected-VM SystemApi access is denied", denied);
        } catch (RuntimeException failure) {
            throw error(FastLaneException.HIDDEN_API_BLOCKED,
                    "protected-VM SystemApi shape is unusable", failure);
        }
        if (!shape.context.isInstance(context)) {
            throw error(FastLaneException.HIDDEN_API_BLOCKED,
                    "Android Context is required", null);
        }
        Object manager = systemService(shape);
        if (manager == null) {
            throw error(FastLaneException.PROTECTED_UNAVAILABLE,
                    "VirtualMachineManager is unavailable", null);
        }
        int capabilities = capabilities(shape, manager);
        if ((capabilities & shape.capabilityProtected) == 0) {
            throw error(FastLaneException.PROTECTED_UNAVAILABLE,
                    "device does not declare protected VMs", null);
        }
        requirePermission(shape);

        Object config = buildConfig(shape, spec, notes);
        Object vm = obtainVm(shape, manager, spec, config, notes);
        CallbackState state = new CallbackState();
        installCallback(shape, vm, state, notes);
        runVm(shape, vm, notes);
        return new ReflectiveHandle(this, spec, shape, vm, state, notes);
    }

    @Override
    public FastLaneChannel connect(FastLaneHandle handle, int port) throws FastLaneException {
        ReflectiveHandle reflective = requireHandle(handle);
        if (port < 1024 || port > 65535) {
            throw error(FastLaneException.VSOCK_FAILED,
                    "vsock port is outside the launch contract", null);
        }
        reflective.ensureOpen();
        Object raw;
        try {
            raw = invoke(reflective.shape.vm, reflective.vm, "connectVsock",
                    new Class<?>[] {long.class}, new Object[] {(long) port},
                    FastLaneException.VSOCK_FAILED, new ArrayList<>());
        } catch (FastLaneException failure) {
            if (reflective.hostStopped) {
                FastLaneException closed = reflective.closedFailure();
                closed.addSuppressed(failure);
                throw closed;
            }
            throw failure;
        }
        if (raw == null) {
            reflective.ensureOpen();
            throw error(FastLaneException.VSOCK_FAILED,
                    "vsock returned no descriptor", null);
        }
        if (!reflective.shape.pfd.isInstance(raw)) {
            closePfd(reflective.shape, raw, null);
            throw error(FastLaneException.VSOCK_FAILED,
                    "vsock returned an unexpected descriptor", null);
        }
        if (reflective.hostStopped) {
            FastLaneException closed = reflective.closedFailure();
            closePfd(reflective.shape, raw, closed);
            throw closed;
        }
        FastLaneChannel owned;
        try {
            owned = adoptParcelFileDescriptor(reflective.shape, raw);
        } catch (IOException | RuntimeException | LinkageError failure) {
            FastLaneException mapped = reflective.hostStopped
                    ? reflective.closedFailure()
                    : mapped("PFD stream adoption", FastLaneException.VSOCK_FAILED, failure);
            if (reflective.hostStopped) {
                mapped.addSuppressed(failure);
            }
            closePfd(reflective.shape, raw, mapped);
            throw mapped;
        }
        HandleChannel channel = new HandleChannel(reflective, owned);
        if (!reflective.addChannel(channel)) {
            FastLaneException closed = reflective.closedFailure();
            closeChannel(channel, closed);
            throw closed;
        }
        return channel;
    }

    private Shape loadShape() throws ReflectiveOperationException {
        Class<?> contextClass = classLoader.loadClass("android.content.Context");
        Class<?> manager = classLoader.loadClass(PACKAGE + "VirtualMachineManager");
        Class<?> config = classLoader.loadClass(PACKAGE + "VirtualMachineConfig");
        Class<?> configBuilder = classLoader.loadClass(PACKAGE + "VirtualMachineConfig$Builder");
        Class<?> vm = classLoader.loadClass(PACKAGE + "VirtualMachine");
        Class<?> pfd = classLoader.loadClass("android.os.ParcelFileDescriptor");
        Class<?> callback = optionalClass(PACKAGE + "VirtualMachineCallback");

        contextClass.getMethod("getSystemService", Class.class);
        contextClass.getMethod("checkSelfPermission", String.class);
        Constructor<?> builderConstructor = configBuilder.getConstructor(contextClass);
        configBuilder.getMethod("setPayloadBinaryName", String.class);
        configBuilder.getMethod("setProtectedVm", boolean.class);
        configBuilder.getMethod("setMemoryBytes", long.class);
        configBuilder.getMethod("setCpuTopology", int.class);
        configBuilder.getMethod("setEncryptedStorageBytes", long.class);
        configBuilder.getMethod("setDebugLevel", int.class);
        configBuilder.getMethod("build");
        config.getMethod("getPayloadBinaryName");
        config.getMethod("isProtectedVm");
        config.getMethod("getMemoryBytes");
        config.getMethod("getCpuTopology");
        config.getMethod("getEncryptedStorageBytes");
        config.getMethod("getDebugLevel");
        config.getMethod("isCompatibleWith", config);
        manager.getMethod("getCapabilities");
        manager.getMethod("create", String.class, config);
        manager.getMethod("getOrCreate", String.class, config);
        vm.getMethod("run");
        vm.getMethod("stop");
        vm.getMethod("close");
        vm.getMethod("getStatus");
        vm.getMethod("getConfig");
        vm.getMethod("connectVsock", long.class);
        pfd.getMethod("dup");
        pfd.getMethod("close");

        Class<?> pfdInput = classLoader.loadClass("android.os.ParcelFileDescriptor$AutoCloseInputStream");
        Class<?> pfdOutput = classLoader.loadClass("android.os.ParcelFileDescriptor$AutoCloseOutputStream");
        Constructor<?> inputConstructor = pfdInput.getConstructor(pfd);
        Constructor<?> outputConstructor = pfdOutput.getConstructor(pfd);

        if (configBuilder.getMethod("build").getReturnType() != config
                || manager.getMethod("create", String.class, config).getReturnType() != vm
                || manager.getMethod("getOrCreate", String.class, config).getReturnType() != vm
                || vm.getMethod("getConfig").getReturnType() != config
                || vm.getMethod("connectVsock", long.class).getReturnType() != pfd
                || vm.getMethod("getStatus").getReturnType() != int.class
                || vm.getMethod("run").getReturnType() != void.class
                || vm.getMethod("stop").getReturnType() != void.class
                || vm.getMethod("close").getReturnType() != void.class
                || pfd.getMethod("dup").getReturnType() != pfd
                || !InputStream.class.isAssignableFrom(pfdInput)
                || !OutputStream.class.isAssignableFrom(pfdOutput)) {
            throw new NoSuchMethodException(
                    "protected-VM config/create/run/connectVsock/PFD signatures are required");
        }

        int cpuOne = intField(config, "CPU_TOPOLOGY_ONE_CPU");
        int cpuMatch = intField(config, "CPU_TOPOLOGY_MATCH_HOST");
        int debugNone = intField(config, "DEBUG_LEVEL_NONE");
        int debugFull = intField(config, "DEBUG_LEVEL_FULL");
        int capabilityProtected = intField(manager, "CAPABILITY_PROTECTED_VM");

        boolean callbackSupported = false;
        if (callback != null) {
            try {
                vm.getMethod("setCallback", Executor.class, callback);
                callback.getMethod("onPayloadStarted", vm);
                callback.getMethod("onPayloadReady", vm);
                callback.getMethod("onPayloadFinished", vm, int.class);
                callback.getMethod("onError", vm, int.class, String.class);
                callback.getMethod("onStopped", vm, int.class);
                callbackSupported = true;
            } catch (NoSuchMethodException missingCallback) {
                callbackSupported = false;
            }
        }
        return new Shape(contextClass, manager, config, configBuilder, vm, pfd, callback,
                builderConstructor, inputConstructor, outputConstructor,
                cpuOne, cpuMatch, debugNone, debugFull, capabilityProtected,
                callbackSupported);
    }

    private Class<?> optionalClass(String name) {
        try {
            return classLoader.loadClass(name);
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }

    private void requirePermission(Shape shape) throws FastLaneException {
        Object value;
        try {
            value = shape.context.getMethod("checkSelfPermission", String.class)
                    .invoke(context, MANAGE_PERMISSION);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof SecurityException) {
                throw error(FastLaneException.PERMISSION_DENIED,
                        "MANAGE_VIRTUAL_MACHINE is denied", cause);
            }
            throw error(FastLaneException.HIDDEN_API_BLOCKED,
                    "permission read failed", cause);
        } catch (ReflectiveOperationException | LinkageError | SecurityException failure) {
            throw error(FastLaneException.HIDDEN_API_BLOCKED,
                    "permission read is unavailable", failure);
        }
        if (!(value instanceof Integer permission)) {
            throw error(FastLaneException.HIDDEN_API_BLOCKED,
                    "permission read returned a non-integer", null);
        }
        if (permission != PERMISSION_GRANTED) {
            throw error(FastLaneException.PERMISSION_DENIED,
                    "required permission missing: " + MANAGE_PERMISSION, null);
        }
    }

    private Object systemService(Shape shape) throws FastLaneException {
        try {
            Object manager = shape.context.getMethod("getSystemService", Class.class)
                    .invoke(context, shape.manager);
            if (manager == null || !shape.manager.isInstance(manager)) {
                return null;
            }
            return manager;
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof SecurityException) {
                throw error(FastLaneException.PERMISSION_DENIED,
                        "VirtualMachineManager access is denied", cause);
            }
            throw error(FastLaneException.HIDDEN_API_BLOCKED,
                    "VirtualMachineManager access failed", cause);
        } catch (ReflectiveOperationException | LinkageError | SecurityException failure) {
            throw error(FastLaneException.HIDDEN_API_BLOCKED,
                    "VirtualMachineManager access is unavailable", failure);
        }
    }

    private int capabilities(Shape shape, Object manager) throws FastLaneException {
        Object value = invoke(shape.manager, manager, "getCapabilities",
                new Class<?>[0], new Object[0], FastLaneException.HIDDEN_API_BLOCKED,
                new ArrayList<>());
        if (!(value instanceof Integer capabilities)) {
            throw error(FastLaneException.HIDDEN_API_BLOCKED,
                    "capability read returned a non-integer", null);
        }
        return capabilities;
    }

    private Object buildConfig(Shape shape, FastLaneLaunchSpec spec, List<String> notes)
            throws FastLaneException {
        Object builder;
        try {
            builder = shape.builderConstructor.newInstance(context);
        } catch (InvocationTargetException failure) {
            throw mapped("config builder construction",
                    FastLaneException.VM_CREATE_FAILED, failure.getCause());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            throw error(FastLaneException.HIDDEN_API_BLOCKED,
                    "config builder is inaccessible", failure);
        }
        set(shape.configBuilder, builder, "setPayloadBinaryName", String.class,
                spec.payloadBinaryName(), notes);
        // Two-package redirect: when the payload lives in the accelerator
        // extension's APK, the platform must be told explicitly — otherwise
        // it defaults to the calling app's own APK and finds no payload.
        // setApkPath is resolved lazily (same API-34 surface as the rest of
        // the shape, but optional for the non-redirect path so a truncated
        // platform still serves own-APK payloads).
        if (spec.payloadApkPath() != null) {
            set(shape.configBuilder, builder, "setApkPath", String.class,
                    spec.payloadApkPath(), notes);
        }
        set(shape.configBuilder, builder, "setProtectedVm", boolean.class, true, notes);
        set(shape.configBuilder, builder, "setMemoryBytes", long.class,
                Math.multiplyExact(spec.memoryMb(), BYTES_PER_MB), notes);
        set(shape.configBuilder, builder, "setCpuTopology", int.class,
                spec.cpuTopology() == FastLaneLaunchSpec.CpuTopology.ONE_CPU
                        ? shape.cpuOne : shape.cpuMatch, notes);
        set(shape.configBuilder, builder, "setEncryptedStorageBytes", long.class,
                spec.encryptedStorageBytes(), notes);
        set(shape.configBuilder, builder, "setDebugLevel", int.class,
                spec.debugLevel() == FastLaneLaunchSpec.DebugLevel.NONE
                        ? shape.debugNone : shape.debugFull, notes);
        Object config = invoke(shape.configBuilder, builder, "build",
                new Class<?>[0], new Object[0], FastLaneException.VM_CREATE_FAILED, notes);
        if (!shape.config.isInstance(config)) {
            throw error(FastLaneException.HIDDEN_API_BLOCKED,
                    "config builder returned an unexpected type", null);
        }
        return config;
    }

    private Object obtainVm(Shape shape, Object manager, FastLaneLaunchSpec spec,
            Object config, List<String> notes) throws FastLaneException {
        // getOrCreate is atomic inside VirtualMachineManager. A get-then-create
        // pair would let two starts of the same instance race and create twice.
        Object vm = invoke(shape.manager, manager, "getOrCreate",
                new Class<?>[] {String.class, shape.config},
                new Object[] {spec.instanceId(), config},
                FastLaneException.VM_CREATE_FAILED, notes);
        if (vm == null || !shape.vm.isInstance(vm)) {
            throw error(FastLaneException.VM_CREATE_FAILED,
                    "manager returned no VM", null);
        }
        Object stored = invoke(shape.vm, vm, "getConfig",
                new Class<?>[0], new Object[0], FastLaneException.VM_CREATE_FAILED, notes);
        if (!configMatches(shape, stored, config)) {
            throw error(FastLaneException.VM_CREATE_FAILED,
                    "existing VM config differs; explicit delete is required", null);
        }
        int status = statusOf(shape, vm);
        if (status != 0) {
            throw error(FastLaneException.VM_CREATE_FAILED,
                    "existing VM must be stopped before launch", null);
        }
        notes.add("vm: ready");
        return vm;
    }

    private boolean configMatches(Shape shape, Object stored, Object expected)
            throws FastLaneException {
        if (!shape.config.isInstance(stored)) {
            return false;
        }
        Object compatible = invoke(shape.config, stored, "isCompatibleWith",
                new Class<?>[] {shape.config}, new Object[] {expected},
                FastLaneException.HIDDEN_API_BLOCKED, new ArrayList<>());
        if (!Boolean.TRUE.equals(compatible)) {
            return false;
        }
        return Objects.equals(
                        configValue(shape, stored, "getPayloadBinaryName"),
                        configValue(shape, expected, "getPayloadBinaryName"))
                && Objects.equals(
                        configValue(shape, stored, "isProtectedVm"),
                        configValue(shape, expected, "isProtectedVm"))
                && Objects.equals(
                        configValue(shape, stored, "getMemoryBytes"),
                        configValue(shape, expected, "getMemoryBytes"))
                && Objects.equals(
                        configValue(shape, stored, "getCpuTopology"),
                        configValue(shape, expected, "getCpuTopology"))
                && Objects.equals(
                        configValue(shape, stored, "getEncryptedStorageBytes"),
                        configValue(shape, expected, "getEncryptedStorageBytes"))
                && Objects.equals(
                        configValue(shape, stored, "getDebugLevel"),
                        configValue(shape, expected, "getDebugLevel"));
    }

    private static Object configValue(Shape shape, Object config, String getter)
            throws FastLaneException {
        return invoke(shape.config, config, getter, new Class<?>[0], new Object[0],
                FastLaneException.HIDDEN_API_BLOCKED, new ArrayList<>());
    }

    private void installCallback(Shape shape, Object vm, CallbackState state, List<String> notes) {
        if (!shape.callbackSupported) {
            notes.add("callback: unavailable");
            return;
        }
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "onPayloadStarted" -> { }
                case "onPayloadReady" -> state.payloadReady = true;
                case "onPayloadFinished" -> {
                    state.payloadFinished = true;
                    state.exitCode = args == null ? -1 : ((Integer) args[1]);
                }
                case "onError" -> {
                    state.errored = true;
                    state.errorCode = args == null ? -1 : ((Integer) args[1]);
                }
                case "onStopped" -> {
                    state.stopped = true;
                    state.stopReason = args == null ? -1 : ((Integer) args[1]);
                }
                case "toString" -> {
                    return "FastLaneCallback";
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "equals" -> {
                    return args != null && args.length == 1 && proxy == args[0];
                }
                default -> {
                    return null;
                }
            }
            return null;
        };
        try {
            Object callback = Proxy.newProxyInstance(
                    shape.callback.getClassLoader(), new Class<?>[] {shape.callback}, handler);
            shape.vm.getMethod("setCallback", Executor.class, shape.callback)
                    .invoke(vm, (Executor) Runnable::run, callback);
            notes.add("callback: installed");
        } catch (InvocationTargetException failure) {
            notes.add("callback: install failed");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            notes.add("callback: unavailable");
        }
    }

    private void runVm(Shape shape, Object vm, List<String> notes) throws FastLaneException {
        try {
            invoke(shape.vm, vm, "run", new Class<?>[0], new Object[0],
                    FastLaneException.VM_CREATE_FAILED, notes);
        } catch (FastLaneException failure) {
            closeAfterFailure(shape, vm, failure);
            throw failure;
        }
    }

    private static void closeVm(Shape shape, Object vm, List<String> notes) {
        try {
            invoke(shape.vm, vm, "close", new Class<?>[0], new Object[0],
                    FastLaneException.STOP_UNCONFIRMED, notes);
        } catch (FastLaneException failure) {
            notes.add("close: failed (" + failure.reason() + ")");
        }
    }

    private static void closeAfterFailure(Shape shape, Object vm, Throwable primary) {
        try {
            shape.vm.getMethod("close").invoke(vm);
        } catch (InvocationTargetException failure) {
            primary.addSuppressed(failure.getCause());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            primary.addSuppressed(failure);
        }
    }

    private static void set(Class<?> type, Object target, String method, Class<?> param,
            Object value, List<String> notes) throws FastLaneException {
        invoke(type, target, method, new Class<?>[] {param}, new Object[] {value},
                FastLaneException.VM_CREATE_FAILED, notes);
    }

    private static Object invoke(Class<?> type, Object target, String name, Class<?>[] params,
            Object[] args, String reason, List<String> notes) throws FastLaneException {
        try {
            Object value = type.getMethod(name, params).invoke(target, args);
            notes.add(name + ": applied");
            return value;
        } catch (InvocationTargetException failure) {
            throw mapped(name, reason, failure.getCause());
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw error(FastLaneException.HIDDEN_API_BLOCKED,
                    "required AVF method inaccessible: " + name, failure);
        } catch (SecurityException | IllegalArgumentException failure) {
            throw error(FastLaneException.HIDDEN_API_BLOCKED,
                    "required AVF method rejected: " + name, failure);
        }
    }

    private static FastLaneException mapped(String stage, String reason, Throwable cause) {
        Throwable boundary = unwrapIoBoundary(cause);
        String mappedReason = reason;
        if (boundary instanceof SecurityException) {
            mappedReason = FastLaneException.PERMISSION_DENIED;
        } else if (boundary instanceof UnsupportedOperationException) {
            mappedReason = FastLaneException.PROTECTED_UNAVAILABLE;
        } else if (boundary instanceof ReflectiveOperationException
                || boundary instanceof LinkageError) {
            mappedReason = FastLaneException.HIDDEN_API_BLOCKED;
        } else if (looksLikePayloadVerificationFailure(boundary)) {
            mappedReason = FastLaneException.PAYLOAD_VERIFY_FAILED;
        }
        return error(mappedReason, "Fast Lane operation failed: " + stage, cause);
    }

    /**
     * A descriptor-adoption failure wraps the real boundary exception in an
     * IOException. A platform VirtualMachineException keeps its own identity so
     * payload verification is not mistaken for a transport error.
     */
    private static Throwable unwrapIoBoundary(Throwable cause) {
        if (cause instanceof IOException && cause.getCause() != null
                && !isVirtualMachineException(cause.getCause())) {
            return cause.getCause();
        }
        return cause;
    }

    private static boolean looksLikePayloadVerificationFailure(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (isVirtualMachineException(current)) {
                String message = current.getMessage();
                if (message != null) {
                    String lower = message.toLowerCase(Locale.ROOT);
                    if (lower.contains("verif")
                            || lower.contains("payload changed")
                            || lower.contains("payload has changed")) {
                        return true;
                    }
                }
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static boolean isVirtualMachineException(Throwable value) {
        return "android.system.virtualmachine.VirtualMachineException"
                .equals(value.getClass().getName());
    }

    private static FastLaneException error(String reason, String message, Throwable cause) {
        return new FastLaneException(reason, message, cause);
    }

    private static void closePfd(Shape shape, Object pfd, Throwable primary) {
        try {
            shape.pfd.getMethod("close").invoke(pfd);
        } catch (InvocationTargetException failure) {
            if (primary != null) {
                primary.addSuppressed(failure.getCause());
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (primary != null) {
                primary.addSuppressed(failure);
            }
        }
    }

    private static void closeChannel(FastLaneChannel channel, Throwable primary) {
        try {
            channel.close();
        } catch (IOException | RuntimeException failure) {
            primary.addSuppressed(failure);
        }
    }

    private static int intField(Class<?> type, String name) throws NoSuchFieldException {
        try {
            return type.getField(name).getInt(null);
        } catch (IllegalAccessException | IllegalArgumentException failure) {
            throw new NoSuchFieldException(name + ": " + failure.getMessage());
        }
    }

    private FastLaneChannel adoptParcelFileDescriptor(Shape shape, Object raw) throws IOException {
        Object duplicate = null;
        try {
            duplicate = shape.pfd.getMethod("dup").invoke(raw);
            if (!shape.pfd.isInstance(duplicate) || duplicate == raw) {
                throw new IOException("PFD dup returned no independent descriptor");
            }
            InputStream input = (InputStream) shape.pfdInputConstructor.newInstance(raw);
            OutputStream output = (OutputStream) shape.pfdOutputConstructor.newInstance(duplicate);
            return new OwnedStreams(input, output);
        } catch (InvocationTargetException failure) {
            closePfdQuietly(shape, duplicate, failure);
            Throwable cause = failure.getCause();
            throw new IOException("PFD stream adoption failed", cause);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            closePfdQuietly(shape, duplicate, failure);
            throw new IOException("PFD stream adoption failed", failure);
        }
    }

    private static void closePfdQuietly(Shape shape, Object pfd, Throwable primary) {
        if (pfd == null) {
            return;
        }
        try {
            shape.pfd.getMethod("close").invoke(pfd);
        } catch (InvocationTargetException failure) {
            primary.addSuppressed(failure.getCause());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            primary.addSuppressed(failure);
        }
    }

    private ReflectiveHandle requireHandle(FastLaneHandle handle) throws FastLaneException {
        if (!(handle instanceof ReflectiveHandle reflective) || reflective.owner != this) {
            throw error(FastLaneException.VSOCK_FAILED,
                    "VM handle belongs to another adapter", null);
        }
        return reflective;
    }

    private static int statusOf(Shape shape, Object vm) {
        try {
            Object value = shape.vm.getMethod("getStatus").invoke(vm);
            if (!(value instanceof Integer status)) {
                return -1;
            }
            return status >= 0 && status <= 2 ? status : -1;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return -1;
        }
    }

    private static final class Shape {
        private final Class<?> context;
        private final Class<?> manager;
        private final Class<?> config;
        private final Class<?> configBuilder;
        private final Class<?> vm;
        private final Class<?> pfd;
        private final Class<?> callback;
        private final Constructor<?> builderConstructor;
        private final Constructor<?> pfdInputConstructor;
        private final Constructor<?> pfdOutputConstructor;
        private final int cpuOne;
        private final int cpuMatch;
        private final int debugNone;
        private final int debugFull;
        private final int capabilityProtected;
        private final boolean callbackSupported;

        private Shape(Class<?> context, Class<?> manager, Class<?> config,
                Class<?> configBuilder, Class<?> vm, Class<?> pfd, Class<?> callback,
                Constructor<?> builderConstructor, Constructor<?> pfdInputConstructor,
                Constructor<?> pfdOutputConstructor, int cpuOne, int cpuMatch,
                int debugNone, int debugFull, int capabilityProtected,
                boolean callbackSupported) {
            this.context = context;
            this.manager = manager;
            this.config = config;
            this.configBuilder = configBuilder;
            this.vm = vm;
            this.pfd = pfd;
            this.callback = callback;
            this.builderConstructor = builderConstructor;
            this.pfdInputConstructor = pfdInputConstructor;
            this.pfdOutputConstructor = pfdOutputConstructor;
            this.cpuOne = cpuOne;
            this.cpuMatch = cpuMatch;
            this.debugNone = debugNone;
            this.debugFull = debugFull;
            this.capabilityProtected = capabilityProtected;
            this.callbackSupported = callbackSupported;
        }
    }

    private static final class CallbackState {
        private volatile boolean payloadReady;
        private volatile boolean payloadFinished;
        private volatile int exitCode = -1;
        private volatile boolean errored;
        private volatile int errorCode = -1;
        private volatile boolean stopped;
        private volatile int stopReason = -1;
    }

    private static final class ReflectiveHandle implements FastLaneHandle {
        private final ReflectiveFastLaneApi owner;
        private final FastLaneLaunchSpec spec;
        private final Shape shape;
        private final Object vm;
        private final CallbackState state;
        private final List<String> notes;
        private final Set<HandleChannel> channels =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private volatile boolean hostStopped;
        private volatile boolean stopRequested;
        private volatile FastLaneStopResult lastStopResult;

        private ReflectiveHandle(ReflectiveFastLaneApi owner, FastLaneLaunchSpec spec,
                Shape shape, Object vm, CallbackState state, List<String> notes) {
            this.owner = owner;
            this.spec = spec;
            this.shape = shape;
            this.vm = vm;
            this.state = state;
            this.notes = new ArrayList<>(notes);
        }

        @Override
        public String instanceId() {
            return spec.instanceId();
        }

        @Override
        public int status() {
            return statusOf(shape, vm);
        }

        @Override
        public boolean payloadReady() {
            return state.payloadReady;
        }

        @Override
        public FastLaneExitInfo waitForExit() throws InterruptedException, FastLaneException {
            long started = System.nanoTime();
            while (true) {
                if (state.errored && isPayloadVerificationError(state.errorCode)) {
                    throw payloadVerificationFailure();
                }
                if (state.stopped && isPayloadVerificationStop(state.stopReason)) {
                    throw payloadVerificationFailure();
                }
                if (state.errored && state.errorCode == ERROR_PAYLOAD_INVALID_CONFIG) {
                    throw invalidPayloadConfigFailure();
                }
                if (state.stopped
                        && state.stopReason == STOP_REASON_MICRODROID_INVALID_PAYLOAD_CONFIG) {
                    throw invalidPayloadConfigFailure();
                }
                long elapsed = elapsedMs(started);
                int status = status();
                if (status == 2) {
                    return FastLaneExitInfo.deleted();
                }
                if (state.payloadFinished || state.stopped || state.errored) {
                    if (stopRequested) {
                        return FastLaneExitInfo.stopRequested(elapsed);
                    }
                    FastLaneExitInfo classified = classifyTerminalState(elapsed);
                    if (classified != null) {
                        return classified;
                    }
                }
                if (status == 0) {
                    if (stopRequested) {
                        return FastLaneExitInfo.stopRequested(elapsed);
                    }
                    FastLaneExitInfo classified = classifyTerminalState(elapsed);
                    return classified != null ? classified : FastLaneExitInfo.normal(elapsed);
                }
                if (status < 0) {
                    if (lastStopResult == FastLaneStopResult.UNCONFIRMED) {
                        throw stopUnconfirmed();
                    }
                    FastLaneExitInfo classified = classifyTerminalState(elapsed);
                    return classified != null
                            ? classified
                            : FastLaneExitInfo.observeFailed("platform status unavailable");
                }
                if (lastStopResult == FastLaneStopResult.UNCONFIRMED && stopRequested) {
                    throw stopUnconfirmed();
                }
                synchronized (this) {
                    wait(100L);
                }
            }
        }

        private FastLaneExitInfo classifyTerminalState(long elapsed) {
            if (state.payloadFinished) {
                if (state.exitCode == 0) {
                    return FastLaneExitInfo.normal(elapsed);
                }
                return FastLaneExitInfo.crashed(state.exitCode, elapsed,
                        "payload exit code=" + state.exitCode);
            }
            if (state.stopped) {
                if (state.stopReason == STOP_REASON_SHUTDOWN) {
                    return FastLaneExitInfo.normal(elapsed);
                }
                return FastLaneExitInfo.crashed(-1, elapsed,
                        "platform stop reason=" + state.stopReason);
            }
            if (state.errored) {
                return FastLaneExitInfo.crashed(-1, elapsed,
                        "platform error code=" + state.errorCode);
            }
            return null;
        }

        @Override
        public FastLaneStopResult stopRuntime(long graceMs) {
            synchronized (this) {
                if (lastStopResult == FastLaneStopResult.CONFIRMED
                        || lastStopResult == FastLaneStopResult.ALREADY_STOPPED) {
                    return lastStopResult;
                }
                int initial = status();
                if (initial == 0) {
                    stopRequested = true;
                    hostStopped = true;
                    notifyAll();
                    closeChannels();
                    closeVm(shape, vm, notes);
                    lastStopResult = FastLaneStopResult.ALREADY_STOPPED;
                    addNote("stop: already-stopped");
                    return lastStopResult;
                }
                if (initial == 2) {
                    // AOSP can report DELETED while the VM still runs and its
                    // backing files are gone. That is not proof of a stop.
                    stopRequested = true;
                    hostStopped = true;
                    notifyAll();
                    closeChannels();
                    closeVm(shape, vm, notes);
                    lastStopResult = FastLaneStopResult.UNCONFIRMED;
                    addNote("stop: deleted-unconfirmed");
                    return lastStopResult;
                }
                stopRequested = true;
                hostStopped = true;
                notifyAll();
                closeChannels();
                try {
                    invoke(shape.vm, vm, "stop", new Class<?>[0], new Object[0],
                            FastLaneException.STOP_UNCONFIRMED, notes);
                } catch (FastLaneException failure) {
                    addNote("stop: failed (" + failure.reason() + ")");
                }
                boolean stopped = awaitStatus(graceMs);
                closeVm(shape, vm, notes);
                if (!stopped) {
                    stopped = awaitStatus(STOP_FORCE_PROBE_MS);
                }
                lastStopResult = stopped
                        ? FastLaneStopResult.CONFIRMED : FastLaneStopResult.UNCONFIRMED;
                addNote("stop-result: " + lastStopResult.name());
                return lastStopResult;
            }
        }

        @Override
        public void close() {
            stopRuntime(0L);
        }

        @Override
        public List<String> notes() {
            synchronized (this) {
                return List.copyOf(notes);
            }
        }

        private void addNote(String note) {
            synchronized (this) {
                notes.add(note);
            }
        }

        private boolean addChannel(HandleChannel channel) {
            synchronized (this) {
                if (hostStopped) {
                    return false;
                }
                channels.add(channel);
                return true;
            }
        }

        private void ensureOpen() throws FastLaneException {
            if (hostStopped) {
                throw closedFailure();
            }
        }

        private FastLaneException closedFailure() {
            return error(FastLaneException.VSOCK_FAILED, "VM handle is closed", null);
        }

        private void closeChannels() {
            List<HandleChannel> closing;
            synchronized (this) {
                closing = new ArrayList<>(channels);
                channels.clear();
            }
            for (HandleChannel channel : closing) {
                try {
                    channel.close();
                } catch (IOException | RuntimeException failure) {
                    addNote("channel-close: failed");
                }
            }
        }

        private boolean awaitStatus(long graceMs) {
            long deadline = System.nanoTime() + Math.max(0L, graceMs) * 1_000_000L;
            while (true) {
                int status = status();
                if (status == 0 || status == 2) {
                    return true;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return false;
                }
                synchronized (this) {
                    try {
                        wait(Math.max(1L, Math.min(STOP_POLL_MS,
                                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining))));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }

        private FastLaneException stopUnconfirmed() {
            return error(FastLaneException.STOP_UNCONFIRMED,
                    "platform never reported STOPPED or DELETED", null);
        }

        private static FastLaneException payloadVerificationFailure() {
            return error(FastLaneException.PAYLOAD_VERIFY_FAILED,
                    "platform reported payload verification failure", null);
        }

        private static FastLaneException invalidPayloadConfigFailure() {
            return error(FastLaneException.VM_CREATE_FAILED,
                    "platform rejected the payload configuration", null);
        }

        private static boolean isPayloadVerificationError(int code) {
            return code == ERROR_PAYLOAD_VERIFICATION_FAILED || code == ERROR_PAYLOAD_CHANGED;
        }

        private static boolean isPayloadVerificationStop(int reason) {
            return reason == STOP_REASON_MICRODROID_PAYLOAD_HAS_CHANGED
                    || reason == STOP_REASON_MICRODROID_PAYLOAD_VERIFICATION_FAILED;
        }

        private static long elapsedMs(long started) {
            return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - started);
        }
    }

    private static final class HandleChannel implements FastLaneChannel {
        private final ReflectiveHandle handle;
        private final FastLaneChannel raw;
        private final InputStream input;
        private final OutputStream output;
        private boolean closed;

        private HandleChannel(ReflectiveHandle handle, FastLaneChannel raw) {
            this.handle = handle;
            this.raw = raw;
            input = new java.io.FilterInputStream(raw.inputStream()) {
                @Override
                public void close() throws IOException {
                    HandleChannel.this.close();
                }
            };
            output = new java.io.FilterOutputStream(raw.outputStream()) {
                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    out.write(bytes, offset, length);
                }

                @Override
                public void close() throws IOException {
                    HandleChannel.this.close();
                }
            };
        }

        @Override
        public InputStream inputStream() {
            return input;
        }

        @Override
        public OutputStream outputStream() {
            return output;
        }

        @Override
        public void close() throws IOException {
            synchronized (handle) {
                if (closed) {
                    return;
                }
                closed = true;
                handle.channels.remove(this);
            }
            raw.close();
        }
    }

    private static final class OwnedStreams implements FastLaneChannel {
        private final InputStream input;
        private final OutputStream output;
        private final InputStream inputView;
        private final OutputStream outputView;
        private boolean closed;

        private OwnedStreams(InputStream input, OutputStream output) {
            this.input = input;
            this.output = output;
            inputView = new java.io.FilterInputStream(input) {
                @Override
                public void close() throws IOException {
                    OwnedStreams.this.close();
                }
            };
            outputView = new java.io.FilterOutputStream(output) {
                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    out.write(bytes, offset, length);
                }

                @Override
                public void close() throws IOException {
                    OwnedStreams.this.close();
                }
            };
        }

        @Override
        public InputStream inputStream() {
            return inputView;
        }

        @Override
        public OutputStream outputStream() {
            return outputView;
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = closeStream(output, null);
            failure = closeStream(input, failure);
            if (failure != null) {
                throw failure;
            }
        }

        private static IOException closeStream(AutoCloseable stream, IOException primary) {
            try {
                stream.close();
            } catch (Exception failure) {
                IOException io = failure instanceof IOException value
                        ? value : new IOException("FD stream close failed", failure);
                if (primary == null) {
                    return io;
                }
                if (primary != io) {
                    primary.addSuppressed(io);
                }
            }
            return primary;
        }
    }
}
