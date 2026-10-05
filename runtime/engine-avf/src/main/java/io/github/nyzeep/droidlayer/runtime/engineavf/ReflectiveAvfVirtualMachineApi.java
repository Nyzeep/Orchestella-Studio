package io.github.nyzeep.droidlayer.runtime.engineavf;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Production {@link AvfVirtualMachineApi} that drives the Android AVF SDK
 * purely through reflection (the {@code android.system.virtualmachine.*}
 * surface, including the Custom VM config builder, is not part of the public
 * SDK on the Android 13/14 devices this strategy targets, and the module must
 * stay a pure-JVM, strategy-neutral unit).
 *
 * <p>Reflection on hidden API surface is subject to the platform's hidden-API
 * enforcement. On Android 15+ the SDK classes are public and plain reflection
 * suffices; on Android 13/14 the host app must supply a hidden-API exemption
 * step (e.g. a HiddenApiBypass-style hook) through the constructor. Its
 * absence is recorded as a reflection note, never silently ignored.
 *
 * <p>Every required reflective step fails closed with a precise
 * {@link AvfEngineException} reason; genuinely optional configuration steps
 * are recorded in the handle's reflection notes instead.
 */
public final class ReflectiveAvfVirtualMachineApi implements AvfVirtualMachineApi {
    private static final String MANAGER_CLASS =
            "android.system.virtualmachine.VirtualMachineManager";
    private static final String CONFIG_CLASS =
            "android.system.virtualmachine.VirtualMachineConfig";
    private static final String CONFIG_BUILDER_CLASS =
            "android.system.virtualmachine.VirtualMachineCustomImageConfig$Builder";
    private static final String VM_CLASS = "android.system.virtualmachine.VirtualMachine";
    private static final String VIRTUALIZATION_SERVICE = "virtualization_service";

    private final Object context;
    private final ClassLoader classLoader;
    private final Runnable hiddenApiExemption;
    private boolean exemptionApplied;

    public ReflectiveAvfVirtualMachineApi(Object context, ClassLoader classLoader) {
        this(context, classLoader, null);
    }

    /**
     * @param hiddenApiExemption optional one-shot hook (e.g. HiddenApiBypass)
     *     executed before the first reflective access; null means no-op.
     */
    public ReflectiveAvfVirtualMachineApi(
            Object context, ClassLoader classLoader, Runnable hiddenApiExemption) {
        this.context = Objects.requireNonNull(context, "context");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.hiddenApiExemption = hiddenApiExemption;
    }

    @Override
    public boolean isReflectionAvailable() {
        try {
            classLoader.loadClass(MANAGER_CLASS);
            classLoader.loadClass(CONFIG_CLASS);
            classLoader.loadClass(CONFIG_BUILDER_CLASS);
            classLoader.loadClass(VM_CLASS);
            return true;
        } catch (ClassNotFoundException | LinkageError unavailable) {
            return false;
        }
    }

    @Override
    public AvfVirtualMachineHandle startCustomVm(AvfLaunchSpec spec) throws AvfEngineException {
        Objects.requireNonNull(spec, "spec");
        List<String> notes = new ArrayList<>();
        Class<?> managerClass;
        Class<?> configClass;
        Class<?> builderClass;
        Class<?> vmClass;
        try {
            managerClass = classLoader.loadClass(MANAGER_CLASS);
            configClass = classLoader.loadClass(CONFIG_CLASS);
            builderClass = classLoader.loadClass(CONFIG_BUILDER_CLASS);
            vmClass = classLoader.loadClass(VM_CLASS);
        } catch (ClassNotFoundException | LinkageError unavailable) {
            throw new AvfEngineException(
                    AvfEngineException.REFLECTION_UNAVAILABLE,
                    "AVF SDK classes are not resolvable on this host",
                    unavailable);
        }

        applyHiddenApiExemption(notes);

        Object manager = getSystemService(managerClass);
        Object builder = newInstance(builderClass, notes);
        invokeRequired(
                builderClass, builder, "setKernelPath", new Class<?>[] {String.class},
                new Object[] {spec.kernelPath()}, notes);
        invokeRequired(
                builderClass, builder, "setInitrdPath", new Class<?>[] {String.class},
                new Object[] {spec.initramfsPath()}, notes);
        // The droidlayer.* guest contract (root device, vsock ports, the 9p
        // data-plane rendezvous) reaches the guest only through the kernel
        // command line; an SDK surface without setCmdline makes the guest
        // contract undeliverable, so it is recorded, never silently skipped.
        invokeOptional(
                builderClass, builder, "setCmdline", new Class<?>[] {String.class},
                new Object[] {spec.guestCmdline()}, notes);
        addRootDisk(builderClass, builder, spec.rootVolumePath(), notes);
        applyMemory(builderClass, builder, spec.memoryMb(), notes);
        applyCpuCount(builderClass, builder, spec.cpuCount(), notes);
        invokeOptional(
                builderClass, builder, "setProtected", new Class<?>[] {boolean.class},
                new Object[] {Boolean.FALSE}, notes);
        invokeOptional(
                builderClass, builder, "setNetworkSupported", new Class<?>[] {boolean.class},
                new Object[] {Boolean.TRUE}, notes);
        applyKernelCmdline(builderClass, builder, spec.guestCmdline(), notes);
        Object config = invokeRequired(
                builderClass, builder, "build", new Class<?>[0], new Object[0], notes);
        Object vm = createVm(managerClass, manager, configClass, config, spec.instanceId(), notes);
        return new ReflectiveHandle(spec.instanceId(), vmClass, vm, notes);
    }

    @Override
    public AvfVsockChannel connectVsock(AvfVirtualMachineHandle handle, int port)
            throws AvfEngineException {
        ReflectiveHandle reflective = requireReflective(handle);
        Object raw;
        try {
            Method connect = reflective.vmClass.getMethod("connectVsock", int.class);
            raw = connect.invoke(reflective.vm, port);
        } catch (NoSuchMethodException missing) {
            throw new AvfEngineException(
                    AvfEngineException.REFLECTION_UNAVAILABLE,
                    "connectVsock(int) is not resolvable",
                    missing);
        } catch (IllegalAccessException | InvocationTargetException failure) {
            Throwable cause =
                    failure instanceof InvocationTargetException ? failure.getCause() : failure;
            throw new AvfEngineException(
                    AvfEngineException.VSOCK_CONNECT_FAILED,
                    "vsock connect to port " + port + " failed",
                    cause);
        }
        return adaptChannel(raw, port);
    }

    private void applyHiddenApiExemption(List<String> notes) {
        if (exemptionApplied) {
            return;
        }
        exemptionApplied = true;
        if (hiddenApiExemption == null) {
            notes.add("hidden-api-exemption: none supplied (public-SDK devices only)");
        } else {
            hiddenApiExemption.run();
            notes.add("hidden-api-exemption: applied");
        }
    }

    private Object getSystemService(Class<?> managerClass) throws AvfEngineException {
        Object manager;
        try {
            manager = context.getClass()
                    .getMethod("getSystemService", String.class)
                    .invoke(context, VIRTUALIZATION_SERVICE);
        } catch (ReflectiveOperationException | IllegalArgumentException failure) {
            throw new AvfEngineException(
                    AvfEngineException.VM_MANAGER_UNAVAILABLE,
                    "getSystemService reflection failed",
                    failure);
        }
        if (manager == null || !managerClass.isInstance(manager)) {
            throw new AvfEngineException(
                    AvfEngineException.VM_MANAGER_UNAVAILABLE,
                    "virtualization service is not available on this device");
        }
        return manager;
    }

    private Object newInstance(Class<?> type, List<String> notes) throws AvfEngineException {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException failure) {
            notes.add("builder construction failed");
            throw new AvfEngineException(
                    AvfEngineException.REFLECTION_UNAVAILABLE,
                    "cannot construct " + type.getName(),
                    failure);
        }
    }

    private void addRootDisk(Class<?> builderClass, Object builder, String rootVolumePath,
            List<String> notes) throws AvfEngineException {
        try {
            builderClass.getMethod("addDisk", String.class, boolean.class)
                    .invoke(builder, rootVolumePath, Boolean.TRUE);
            notes.add("addDisk(String,boolean): applied");
        } catch (NoSuchMethodException fallback) {
            try {
                builderClass.getMethod("addDisk", String.class).invoke(builder, rootVolumePath);
                notes.add("addDisk(String): applied (writable-by-default variant)");
            } catch (ReflectiveOperationException failure) {
                throw new AvfEngineException(
                        AvfEngineException.REFLECTION_UNAVAILABLE,
                        "no usable addDisk variant for the root volume",
                        failure);
            } catch (IllegalArgumentException failure) {
                throw new AvfEngineException(
                        AvfEngineException.REFLECTION_UNAVAILABLE,
                        "addDisk(String) invocation failed",
                        failure);
            }
        } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException
                failure) {
            throw new AvfEngineException(
                    AvfEngineException.REFLECTION_UNAVAILABLE,
                    "addDisk(String,boolean) invocation failed",
                    failure);
        }
    }

    private void applyMemory(Class<?> builderClass, Object builder, long memoryMb,
            List<String> notes) {
        try {
            builderClass.getMethod("setMemoryMbs", int.class)
                    .invoke(builder, (int) memoryMb);
            notes.add("setMemoryMbs(int): applied");
        } catch (NoSuchMethodException fallback) {
            try {
                builderClass.getMethod("setMemoryBytes", long.class)
                        .invoke(builder, memoryMb * 1024L * 1024L);
                notes.add("setMemoryBytes(long): applied");
            } catch (NoSuchMethodException absent) {
                notes.add("memory configuration skipped: no supported setter");
            } catch (ReflectiveOperationException | IllegalArgumentException failure) {
                notes.add("memory configuration skipped: " + failure.getClass().getSimpleName());
            }
        } catch (ReflectiveOperationException | IllegalArgumentException failure) {
            notes.add("memory configuration skipped: " + failure.getClass().getSimpleName());
        }
    }

    private void applyCpuCount(Class<?> builderClass, Object builder, int cpuCount,
            List<String> notes) {
        try {
            builderClass.getMethod("setCpuCount", int.class).invoke(builder, cpuCount);
            notes.add("setCpuCount(int): applied");
        } catch (NoSuchMethodException absent) {
            notes.add("cpuCount configuration skipped: no setCpuCount(int) setter");
        } catch (ReflectiveOperationException | IllegalArgumentException failure) {
            notes.add("cpuCount configuration skipped: " + failure.getClass().getSimpleName());
        }
    }

    /**
     * Delivers the guest network contract keys (vsock service ports,
     * {@code droidlayer.dns=}) through the platform's optional kernel-cmdline
     * setter. Platform versions expose the surface under different names
     * ({@code setKernelCmdline}/{@code setParams}); the first resolvable
     * variant wins and its application is recorded, while a fully absent
     * surface degrades to a note — the guest falls back to the fixed vsock
     * port conventions and the embedded-provision resolver defaults.
     */
    private void applyKernelCmdline(Class<?> builderClass, Object builder, String cmdline,
            List<String> notes) {
        String[][] candidates = {{"setKernelCmdline"}, {"setParams"}};
        for (String[] candidate : candidates) {
            String method = candidate[0];
            try {
                builderClass.getMethod(method, String.class).invoke(builder, cmdline);
                notes.add(method + ": applied");
                return;
            } catch (NoSuchMethodException absent) {
                notes.add(method + "(String): not resolvable");
            } catch (ReflectiveOperationException | IllegalArgumentException failure) {
                notes.add(method + ": skipped (" + failure.getClass().getSimpleName() + ")");
                return;
            }
        }
        notes.add("guest cmdline delivery skipped: no cmdline setter on this platform");
    }

    private Object invokeRequired(
            Class<?> declared, Object target, String method, Class<?>[] parameterTypes,
            Object[] arguments, List<String> notes) throws AvfEngineException {
        try {
            return declared.getMethod(method, parameterTypes).invoke(target, arguments);
        } catch (NoSuchMethodException missing) {
            notes.add("required method missing: " + method);
            throw new AvfEngineException(
                    AvfEngineException.REFLECTION_UNAVAILABLE,
                    "required method is not resolvable: " + method,
                    missing);
        } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException
                failure) {
            notes.add("required invocation failed: " + method);
            throw new AvfEngineException(
                    AvfEngineException.REFLECTION_UNAVAILABLE,
                    "required invocation failed: " + method,
                    failure);
        }
    }

    private void invokeOptional(
            Class<?> declared, Object target, String method, Class<?>[] parameterTypes,
            Object[] arguments, List<String> notes) {
        try {
            declared.getMethod(method, parameterTypes).invoke(target, arguments);
            notes.add(method + ": applied");
        } catch (ReflectiveOperationException | IllegalArgumentException failure) {
            notes.add(method + ": skipped (" + failure.getClass().getSimpleName() + ")");
        }
    }

    private Object createVm(
            Class<?> managerClass,
            Object manager,
            Class<?> configClass,
            Object config,
            String instanceId,
            List<String> notes) throws AvfEngineException {
        for (String candidate : new String[] {"createVm", "getOrCreateVm"}) {
            try {
                return managerClass
                        .getMethod(candidate, String.class, configClass)
                        .invoke(manager, instanceId, config);
            } catch (NoSuchMethodException missing) {
                notes.add(candidate + "(String,config): not resolvable");
            } catch (IllegalAccessException failure) {
                notes.add(candidate + ": inaccessible");
            } catch (InvocationTargetException failure) {
                throw mapCreateFailure(candidate, failure.getCause(), notes);
            }
        }
        throw new AvfEngineException(
                AvfEngineException.REFLECTION_UNAVAILABLE,
                "no usable VM creation method on the manager");
    }

    private AvfEngineException mapCreateFailure(String method, Throwable cause,
            List<String> notes) {
        String causeName = cause == null ? "" : cause.getClass().getName();
        if (cause instanceof SecurityException) {
            notes.add(method + ": permission denied");
            return new AvfEngineException(
                    AvfEngineException.PERMISSION_DENIED,
                    "VM creation was refused by the permission gate",
                    cause);
        }
        if (cause instanceof UnsupportedOperationException
                || causeName.contains("Unsupported")
                || (cause != null
                        && String.valueOf(cause.getMessage()).contains("not supported"))) {
            notes.add(method + ": hardware refused non-protected VM");
            return new AvfEngineException(
                    AvfEngineException.HARDWARE_UNSUPPORTED,
                    "the hardware or firmware refused the non-protected custom VM",
                    cause);
        }
        notes.add(method + ": creation failed (" + causeName + ")");
        return new AvfEngineException(
                AvfEngineException.VM_CREATE_FAILED,
                "VM creation failed",
                cause);
    }

    private AvfVsockChannel adaptChannel(Object raw, int port) throws AvfEngineException {
        if (raw == null) {
            throw new AvfEngineException(
                    AvfEngineException.VSOCK_CONNECT_FAILED,
                    "vsock connect to port " + port + " returned no channel");
        }
        try {
            Method in = raw.getClass().getMethod("getInputStream");
            Method out = raw.getClass().getMethod("getOutputStream");
            return new ReflectiveVsockChannel(in.invoke(raw), out.invoke(raw));
        } catch (ReflectiveOperationException | IllegalArgumentException failure) {
            throw new AvfEngineException(
                    AvfEngineException.VSOCK_CONNECT_FAILED,
                    "vsock connection object for port " + port + " exposes no stream pair",
                    failure);
        }
    }

    private ReflectiveHandle requireReflective(AvfVirtualMachineHandle handle) {
        if (!(handle instanceof ReflectiveHandle reflective)) {
            throw new IllegalArgumentException("foreign handle for reflective api");
        }
        return reflective;
    }

    private static final class ReflectiveHandle implements AvfVirtualMachineHandle {
        private final String instanceId;
        private final Class<?> vmClass;
        private final Object vm;
        private final List<String> notes;
        private volatile boolean hostStopped;

        ReflectiveHandle(String instanceId, Class<?> vmClass, Object vm, List<String> notes) {
            this.instanceId = instanceId;
            this.vmClass = vmClass;
            this.vm = vm;
            this.notes = Collections.synchronizedList(notes);
        }

        @Override
        public String instanceId() {
            return instanceId;
        }

        @Override
        public List<String> reflectionNotes() {
            return Collections.unmodifiableList(new ArrayList<>(notes));
        }

        @Override
        public int waitFor() throws InterruptedException {
            while (true) {
                Integer stopped = reflectGuestStopped();
                if (stopped != null) {
                    return stopped;
                }
                if (hostStopped) {
                    return 0;
                }
                synchronized (this) {
                    wait(500);
                }
            }
        }

        @Override
        public void stop() {
            hostStopped = true;
            try {
                vmClass.getMethod("stop").invoke(vm);
            } catch (ReflectiveOperationException | IllegalArgumentException failure) {
                notes.add("stop: skipped (" + failure.getClass().getSimpleName() + ")");
            } finally {
                synchronized (this) {
                    notifyAll();
                }
            }
        }

        /**
         * Null while the guest is running or its status cannot be read. Real
         * AOSP VirtualMachine.getStatus() returns the {@code @Status} int
         * (0=STOPPED, 1=RUNNING, 2=DELETED) boxed to an Integer; the JVM
         * fixture stub returns a named enum — both surfaces must report
         * guest exit.
         */
        private Integer reflectGuestStopped() {
            try {
                Object status = vmClass.getMethod("getStatus").invoke(vm);
                if (status instanceof Number numeric) {
                    int value = numeric.intValue();
                    if (value == 0 || value == 2) {
                        return 0;
                    }
                    return null;
                }
                if (status instanceof Enum<?> named) {
                    String name = named.name();
                    if (name.contains("STOP") || name.contains("DEAD")) {
                        return 0;
                    }
                }
                return null;
            } catch (ReflectiveOperationException | IllegalArgumentException unreadable) {
                return null;
            }
        }
    }

    private static final class ReflectiveVsockChannel implements AvfVsockChannel {
        private final InputStream inputStream;
        private final OutputStream outputStream;

        ReflectiveVsockChannel(Object input, Object output) {
            this.inputStream = (InputStream) input;
            this.outputStream = (OutputStream) output;
        }

        @Override
        public InputStream inputStream() {
            return inputStream;
        }

        @Override
        public OutputStream outputStream() {
            return outputStream;
        }

        @Override
        public void close() throws IOException {
            inputStream.close();
            outputStream.close();
        }
    }
}
