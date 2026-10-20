package io.github.nyzeep.orchestella.runtime.engineavf;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure-Java boundary for the restricted AOSP AVF SystemApi. Based on Virtualization revision
 * c984fc337c11ca5edc03ccf02037b2455dd8fcaf (Android 16), not a duck-typed stream API.
 * MANAGE_VIRTUAL_MACHINE and USE_CUSTOM_VIRTUAL_MACHINE are both required. Reflection never
 * grants permission or makes hidden APIs public; unavailable methods fail closed.
 */
public final class ReflectiveAvfVirtualMachineApi implements AvfVirtualMachineApi {
    private static final String PACKAGE = "android.system.virtualmachine.";
    private static final String MANAGE_PERMISSION = "android.permission.MANAGE_VIRTUAL_MACHINE";
    private static final String CUSTOM_PERMISSION = "android.permission.USE_CUSTOM_VIRTUAL_MACHINE";
    private final Object context;
    private final ClassLoader classLoader;
    private final Runnable hiddenApiExemption;
    private final DescriptorAdapter descriptorAdapter;
    private boolean exemptionApplied;

    public ReflectiveAvfVirtualMachineApi(Object context, ClassLoader classLoader) {
        this(context, classLoader, null);
    }

    /** Optional explicitly supplied access hook; no exemption is installed by this adapter. */
    public ReflectiveAvfVirtualMachineApi(Object context, ClassLoader classLoader,
            Runnable hiddenApiExemption) {
        this(context, classLoader, hiddenApiExemption, null);
    }

    /** Inject a typed App FD adapter, or use the exact PFD AutoCloseStreams reflection boundary. */
    public ReflectiveAvfVirtualMachineApi(Object context, ClassLoader classLoader,
            Runnable hiddenApiExemption, DescriptorAdapter descriptorAdapter) {
        this.context = Objects.requireNonNull(context, "context");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.hiddenApiExemption = hiddenApiExemption;
        this.descriptorAdapter = descriptorAdapter == null ? this::adoptParcelFileDescriptor : descriptorAdapter;
    }

    @Override
    public boolean isReflectionAvailable() {
        try {
            loadShape();
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    public AvfVirtualMachineHandle startCustomVm(AvfLaunchSpec spec) throws AvfEngineException {
        return startCustomVm(spec, AvfLaunchTrace.NONE);
    }

    @Override
    public AvfVirtualMachineHandle startCustomVm(AvfLaunchSpec spec, AvfLaunchTrace trace)
            throws AvfEngineException {
        Objects.requireNonNull(spec, "spec");
        AvfLaunchTrace stages = trace == null ? AvfLaunchTrace.NONE : trace;
        List<String> notes = new ArrayList<>();
        applyAccessHook(notes);
        Shape shape;
        try {
            shape = loadShape();
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw error(AvfEngineException.REFLECTION_UNAVAILABLE, "required AVF SystemApi is absent", failure);
        } catch (SecurityException denied) {
            throw error(AvfEngineException.REFLECTION_UNAVAILABLE, "AVF SystemApi access is denied", denied);
        }
        if (!shape.context.isInstance(context)) {
            throw error(AvfEngineException.REFLECTION_UNAVAILABLE, "Android Context is required", null);
        }
        requirePermission(shape, MANAGE_PERMISSION, notes);
        requirePermission(shape, CUSTOM_PERMISSION, notes);
        Object manager = invoke(shape.context, context, "getSystemService", new Class<?>[] {Class.class},
                new Object[] {shape.manager}, AvfEngineException.VM_MANAGER_UNAVAILABLE, notes);
        if (!shape.manager.isInstance(manager)) {
            throw error(AvfEngineException.VM_MANAGER_UNAVAILABLE, "AVF manager is unavailable", null);
        }
        Object capabilities = invoke(shape.manager, manager, "getCapabilities", new Class<?>[0],
                new Object[0], AvfEngineException.VM_MANAGER_UNAVAILABLE, notes);
        int nonProtected = intField(shape.manager, "CAPABILITY_NON_PROTECTED_VM");
        if (!(capabilities instanceof Integer value) || (value & nonProtected) == 0) {
            throw error(AvfEngineException.HARDWARE_UNSUPPORTED, "non-protected custom VMs are unavailable", null);
        }
        // The pinned public SystemApi has ONE_CPU/MATCH_HOST topology, not an exact-count setter.
        // MATCH_HOST is not a faithful substitute for the launch spec's requested count.
        if (spec.cpuCount() != 1) {
            throw error(AvfEngineException.HARDWARE_UNSUPPORTED,
                    "this AVF SystemApi cannot express the requested exact vCPU count", null);
        }
        Object imageBuilder = construct(shape.imageBuilder, new Class<?>[0], new Object[0]);
        set(shape.imageBuilder, imageBuilder, "setName", String.class, spec.instanceId(), notes);
        set(shape.imageBuilder, imageBuilder, "setKernelPath", String.class, spec.kernelPath(), notes);
        set(shape.imageBuilder, imageBuilder, "setInitrdPath", String.class, spec.initramfsPath(), notes);
        for (String param : spec.guestCmdline().split("\\s+")) {
            set(shape.imageBuilder, imageBuilder, "addParam", String.class, param, notes);
        }
        Object disk = invoke(shape.disk, null, "RWDisk", new Class<?>[] {String.class},
                new Object[] {spec.rootVolumePath()}, AvfEngineException.VM_CREATE_FAILED, notes);
        set(shape.imageBuilder, imageBuilder, "addDisk", shape.disk, disk, notes);
        AvfNetworkPlan networkPlan = spec.networkPlan();
        if (networkPlan.enabled()) {
            GpuConfigOutcome gpu = applyHeadlessGpu(shape, imageBuilder);
            notes.add("gpu-config: " + gpu.name().toLowerCase(java.util.Locale.ROOT));
            if (gpu == GpuConfigOutcome.FAILED) {
                if (!networkPlan.allowDegradeToNone()) {
                    throw error(AvfEngineException.NETWORK_BACKEND_UNAVAILABLE,
                            "network requested but the headless GPU workaround failed", null);
                }
                // Keep the VM bootable without a network instead of failing the
                // whole instance; the degradation is visible in the notes.
                notes.add("network: degraded");
            } else {
                // UNSUPPORTED is benign: older platforms has no GPU API and the
                // legacy useNetwork path still works there.
                set(shape.imageBuilder, imageBuilder, "useNetwork", boolean.class, true, notes);
            }
        } else {
            notes.add("useNetwork: skipped");
        }
        Object image = invoke(shape.imageBuilder, imageBuilder, "build", new Class<?>[0],
                new Object[0], AvfEngineException.VM_CREATE_FAILED, notes);
        if (!shape.image.isInstance(image)) {
            throw error(AvfEngineException.REFLECTION_UNAVAILABLE, "invalid custom image config type", null);
        }
        Object configBuilder = construct(shape.configBuilder, new Class<?>[] {shape.context},
                new Object[] {context});
        set(shape.configBuilder, configBuilder, "setCustomImageConfig", shape.image, image, notes);
        set(shape.configBuilder, configBuilder, "setProtectedVm", boolean.class, false, notes);
        set(shape.configBuilder, configBuilder, "setMemoryBytes", long.class,
                Math.multiplyExact(spec.memoryMb(), 1024L * 1024L), notes);
        set(shape.configBuilder, configBuilder, "setCpuTopology", int.class,
                intField(shape.config, "CPU_TOPOLOGY_ONE_CPU"), notes);
        Object config = invoke(shape.configBuilder, configBuilder, "build", new Class<?>[0],
                new Object[0], AvfEngineException.VM_CREATE_FAILED, notes);
        if (!shape.config.isInstance(config)) {
            throw error(AvfEngineException.REFLECTION_UNAVAILABLE, "invalid VM config type", null);
        }
        // getOrCreate would silently ignore a new config for an existing name. Retrieve first,
        // verify every supported boot setting, and never delete or rewrite persistent VM data.
        Object vm = invoke(shape.manager, manager, "get", new Class<?>[] {String.class},
                new Object[] {spec.instanceId()}, AvfEngineException.VM_CREATE_FAILED, notes);
        if (vm == null) {
            vm = invoke(shape.manager, manager, "create", new Class<?>[] {String.class, shape.config},
                    new Object[] {spec.instanceId(), config}, AvfEngineException.VM_CREATE_FAILED, notes);
            if (!shape.vm.isInstance(vm)) {
                throw error(AvfEngineException.VM_CREATE_FAILED, "manager returned no VM", null);
            }
            notes.add("persistent-vm: created");
        } else {
            if (!shape.vm.isInstance(vm)) throw configMismatch("VM type");
            Object stored = invoke(shape.vm, vm, "getConfig", new Class<?>[0], new Object[0],
                    AvfEngineException.CONFIG_MISMATCH, notes);
            if (storedConfigMatches(shape, stored, config)) {
                Object status = invoke(shape.vm, vm, "getStatus", new Class<?>[0], new Object[0],
                        AvfEngineException.VM_CREATE_FAILED, notes);
                if (!(status instanceof Integer state) || state != 0) {
                    throw error(AvfEngineException.VM_CREATE_FAILED,
                            "persistent VM must be stopped before cold restore", null);
                }
                notes.add("persistent-vm: reused");
            } else {
                Object status = invoke(shape.vm, vm, "getStatus", new Class<?>[0], new Object[0],
                        AvfEngineException.VM_STOP_UNCONFIRMED, notes);
                if (!(status instanceof Integer state) || state != 0) {
                    // A rebuild must never delete config/state of a VM that may be alive.
                    throw error(AvfEngineException.VM_STOP_UNCONFIRMED,
                            "persistent VM cannot be rebuilt until it is confirmed stopped", null);
                }
                // delete() removes the VM config/state, never the user's raw root volume.
                invoke(shape.manager, manager, "delete", new Class<?>[] {String.class},
                        new Object[] {spec.instanceId()}, AvfEngineException.VM_CREATE_FAILED, notes);
                notes.add("persistent-vm: deleted for rebuild");
                vm = invoke(shape.manager, manager, "create", new Class<?>[] {String.class, shape.config},
                        new Object[] {spec.instanceId(), config}, AvfEngineException.VM_CREATE_FAILED, notes);
                if (!shape.vm.isInstance(vm)) {
                    throw error(AvfEngineException.VM_CREATE_FAILED, "manager returned no VM", null);
                }
                notes.add("persistent-vm: recreated");
            }
        }
        stages.mark(AvfLaunchTrace.Stage.VM_CREATED);
        try {
            invoke(shape.vm, vm, "run", new Class<?>[0], new Object[0],
                    AvfEngineException.VM_CREATE_FAILED, notes);
        } catch (AvfEngineException failure) {
            closeAfterFailure(shape.vm, vm, failure);
            throw failure;
        }
        stages.mark(AvfLaunchTrace.Stage.RUN_RETURNED);
        return new ReflectiveHandle(this, spec.instanceId(), shape.vm, vm, notes);
    }

    /** Typed result of the Android 17 headless-2D GPU workaround. */
    private enum GpuConfigOutcome {
        APPLIED,
        /** The platform has no GPU config API; benign, the legacy attempt remains. */
        UNSUPPORTED,
        /** The API exists but failed; the caller degrades or refuses by policy. */
        FAILED
    }

    /**
     * Declares a surfaceless 2D GPU so virtmgr routes a network-enabled VM to
     * the full crosvm instead of {@code crosvm_minimal} (which has no net
     * feature). Resolved lazily: requiring these classes in {@link #loadShape()}
     * would make every older device fail reflection entirely.
     */
    private GpuConfigOutcome applyHeadlessGpu(Shape shape, Object imageBuilder) {
        Class<?> gpuClass;
        Class<?> gpuBuilderClass;
        try {
            gpuClass = classLoader.loadClass(PACKAGE + "VirtualMachineCustomImageConfig$GpuConfig");
            gpuBuilderClass = classLoader.loadClass(
                    PACKAGE + "VirtualMachineCustomImageConfig$GpuConfig$Builder");
        } catch (ClassNotFoundException | LinkageError unavailable) {
            return GpuConfigOutcome.UNSUPPORTED;
        }
        try {
            List<String> ignored = new ArrayList<>();
            Object gpuBuilder = construct(gpuBuilderClass, new Class<?>[0], new Object[0]);
            set(gpuBuilderClass, gpuBuilder, "setBackend", String.class, "2d", ignored);
            try {
                // Surfaceless is the workaround's second half; an older API that
                // only exposes the backend keeps APPLIED but records the gap.
                set(gpuBuilderClass, gpuBuilder, "setSurfaceless", boolean.class, true, ignored);
            } catch (AvfEngineException missingSetter) {
                if (!AvfEngineException.REFLECTION_UNAVAILABLE.equals(missingSetter.reason())) {
                    return GpuConfigOutcome.FAILED;
                }
            }
            Object gpu = invoke(gpuBuilderClass, gpuBuilder, "build", new Class<?>[0], new Object[0],
                    AvfEngineException.VM_CREATE_FAILED, ignored);
            if (!gpuClass.isInstance(gpu)) return GpuConfigOutcome.FAILED;
            set(shape.imageBuilder, imageBuilder, "setGpuConfig", gpuClass, gpu, ignored);
            return GpuConfigOutcome.APPLIED;
        } catch (AvfEngineException failure) {
            if (AvfEngineException.REFLECTION_UNAVAILABLE.equals(failure.reason())
                    || AvfEngineException.HARDWARE_UNSUPPORTED.equals(failure.reason())) {
                return GpuConfigOutcome.UNSUPPORTED;
            }
            return GpuConfigOutcome.FAILED;
        }
    }

    /**
     * Identity compatibility plus the full stored-vs-requested field set.
     * A mismatch is a rebuild signal, not an error: the caller must only
     * rebuild after observing a stopped VM.
     */
    private static boolean storedConfigMatches(Shape shape, Object stored, Object expected) {
        if (!shape.config.isInstance(stored)) throw configMismatch("VM config type");
        try {
            // Official isCompatibleWith tests identity compatibility, not full custom config equality.
            Object compatible = invoke(shape.config, stored, "isCompatibleWith", new Class<?>[] {shape.config},
                    new Object[] {expected}, AvfEngineException.CONFIG_MISMATCH, new ArrayList<>());
            if (!Boolean.TRUE.equals(compatible)) return false;
            for (String getter : new String[] {"isProtectedVm", "getMemoryBytes", "getCpuTopology",
                    "getEncryptedStorageBytes", "shouldUseHugepages"}) {
                if (!sameField(shape.config, stored, expected, getter)) return false;
            }
            Object actualImage = configValue(shape.config, stored, "getCustomImageConfig");
            Object expectedImage = configValue(shape.config, expected, "getCustomImageConfig");
            if (!shape.image.isInstance(actualImage) || !shape.image.isInstance(expectedImage)) {
                throw configMismatch("custom image type");
            }
            for (String getter : new String[] {"getName", "getKernelPath", "getInitrdPath", "getBootloaderPath",
                    "getParams", "useNetwork", "getSharedPaths", "getAudioConfig", "getDisplayConfig",
                    "getGpuConfig", "getUsbConfig", "useTouch", "useKeyboard", "useMouse", "useSwitches",
                    "useTrackpad", "useAutoMemoryBalloon"}) {
                if (!sameField(shape.image, actualImage, expectedImage, getter)) return false;
            }
            Object actualDisks = configValue(shape.image, actualImage, "getDisks");
            Object wantedDisks = configValue(shape.image, expectedImage, "getDisks");
            if (!(actualDisks instanceof Object[] disks) || !(wantedDisks instanceof Object[] wanted)) {
                throw configMismatch("root disk type");
            }
            if (disks.length != 1 || wanted.length != 1
                    || !shape.disk.isInstance(disks[0]) || !shape.disk.isInstance(wanted[0])) {
                // A different disk set is a rebuild signal, not an unreadable config.
                return false;
            }
            if (!sameField(shape.disk, disks[0], wanted[0], "getImagePath")) return false;
            if (!sameField(shape.disk, disks[0], wanted[0], "isWritable")) return false;
            return sameField(shape.disk, disks[0], wanted[0], "getPartitions");
        } catch (AvfEngineException failure) {
            if (AvfEngineException.PERMISSION_DENIED.equals(failure.reason())
                    || AvfEngineException.CONFIG_MISMATCH.equals(failure.reason())) throw failure;
            throw error(AvfEngineException.CONFIG_MISMATCH,
                    "persistent VM configuration cannot be verified with this platform API", failure);
        }
    }

    private static Object configValue(Class<?> type, Object config, String getter) {
        return invoke(type, config, getter, new Class<?>[0], new Object[0],
                AvfEngineException.CONFIG_MISMATCH, new ArrayList<>());
    }

    private static boolean sameField(Class<?> type, Object actual, Object expected, String getter) {
        // No stored/requested paths or user values are embedded in diagnostics.
        return Objects.deepEquals(configValue(type, actual, getter), configValue(type, expected, getter));
    }

    private static AvfEngineException configMismatch(String field) {
        return error(AvfEngineException.CONFIG_MISMATCH,
                "persistent VM configuration mismatch: " + field, null);
    }

    @Override
    public AvfVsockChannel connectVsock(AvfVirtualMachineHandle handle, int port)
            throws AvfEngineException {
        ReflectiveHandle reflective = requireHandle(handle);
        if (port < 1024 || port > 65535) {
            throw error(AvfEngineException.VSOCK_CONNECT_FAILED, "vsock port is outside the launch contract", null);
        }
        Class<?> pfdClass;
        try { pfdClass = classLoader.loadClass("android.os.ParcelFileDescriptor"); }
        catch (ClassNotFoundException failure) {
            throw error(AvfEngineException.REFLECTION_UNAVAILABLE, "PFD API unavailable", failure);
        }
        reflective.ensureOpen();
        Object raw;
        try {
            // No Java handle monitor spans a platform call that can block.
            raw = invoke(reflective.vmClass, reflective.vm, "connectVsock",
                    new Class<?>[] {long.class}, new Object[] {(long) port},
                    AvfEngineException.VSOCK_CONNECT_FAILED, new ArrayList<>());
        } catch (AvfEngineException failure) {
            if (reflective.hostStopped) {
                AvfEngineException closed = reflective.closedFailure();
                closed.addSuppressed(failure);
                throw closed;
            }
            throw failure;
        }
        if (raw == null) {
            reflective.ensureOpen();
            throw error(AvfEngineException.VSOCK_CONNECT_FAILED, "vsock returned no descriptor", null);
        }
        if (!pfdClass.isInstance(raw)) {
            throw error(AvfEngineException.REFLECTION_UNAVAILABLE, "vsock did not return a PFD", null);
        }
        if (reflective.hostStopped) {
            AvfEngineException closed = reflective.closedFailure();
            closeAfterFailure(pfdClass, raw, closed);
            throw closed;
        }
        AvfVsockChannel channel = null;
        InputStream input;
        OutputStream output;
        try {
            // In-flight adoption owns raw until it can publish. STOP does not close raw while
            // dup/AutoCloseStreams are constructing; late completion closes the adopted owner.
            channel = descriptorAdapter.adopt(raw);
            input = channel == null ? null : channel.inputStream();
            output = channel == null ? null : channel.outputStream();
            if (input == null || output == null) throw new IOException("FD adapter returned no complete channel");
        } catch (IOException | RuntimeException | LinkageError failure) {
            AvfEngineException mapped = reflective.hostStopped ? reflective.closedFailure()
                    : mapped("PFD stream adoption", AvfEngineException.VSOCK_CONNECT_FAILED, failure);
            if (reflective.hostStopped) mapped.addSuppressed(failure);
            if (channel == null) closeAfterFailure(pfdClass, raw, mapped);
            else closeChannelAfterFailure(channel, mapped);
            throw mapped;
        }
        HandleChannel owned = new HandleChannel(reflective, channel, input, output);
        synchronized (reflective) {
            if (!reflective.hostStopped) {
                reflective.channels.add(owned);
                return owned;
            }
        }
        AvfEngineException closed = reflective.closedFailure();
        closeChannelAfterFailure(owned, closed);
        throw closed;
    }

    private AvfVsockChannel adoptParcelFileDescriptor(Object raw) throws IOException {
        Object outputDescriptor = null;
        try {
            Class<?> pfd = classLoader.loadClass("android.os.ParcelFileDescriptor");
            var inputConstructor = classLoader.loadClass("android.os.ParcelFileDescriptor$AutoCloseInputStream")
                    .getConstructor(pfd);
            var outputConstructor = classLoader.loadClass("android.os.ParcelFileDescriptor$AutoCloseOutputStream")
                    .getConstructor(pfd);
            // Each AutoCloseStream owns exactly one descriptor, never two owners for the same FD.
            outputDescriptor = pfd.getMethod("dup").invoke(raw);
            if (!pfd.isInstance(outputDescriptor) || outputDescriptor == raw) {
                throw new IOException("PFD dup returned no independent descriptor");
            }
            InputStream input = (InputStream) inputConstructor.newInstance(raw);
            OutputStream output = (OutputStream) outputConstructor.newInstance(outputDescriptor);
            return new OwnedStreams(input, output);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            Throwable cause = failure instanceof InvocationTargetException wrapped ? wrapped.getCause() : failure;
            IOException error = new IOException("PFD AutoCloseStreams unavailable", cause);
            if (outputDescriptor != null && outputDescriptor != raw) {
                closeAfterFailure(outputDescriptor.getClass(), outputDescriptor, error);
            }
            // Original descriptor is still owned by connectVsock on this failure path.
            throw error;
        } catch (IOException failure) {
            if (outputDescriptor != null && outputDescriptor != raw) {
                closeAfterFailure(outputDescriptor.getClass(), outputDescriptor, failure);
            }
            throw failure;
        }
    }

    private static void closeChannelAfterFailure(AvfVsockChannel channel, Throwable primary) {
        try { channel.close(); }
        catch (IOException | RuntimeException cleanup) { primary.addSuppressed(cleanup); }
    }

    /** One published FD owner: caller close and handle.stop claim it once under a short lock. */
    private static final class HandleChannel implements AvfVsockChannel {
        private final ReflectiveHandle handle;
        private final AvfVsockChannel raw;
        private final InputStream input;
        private final OutputStream output;
        private boolean closed;
        HandleChannel(ReflectiveHandle handle, AvfVsockChannel raw, InputStream inputStream, OutputStream outputStream) {
            this.handle = handle;
            this.raw = raw;
            input = new java.io.FilterInputStream(inputStream) {
                @Override public void close() throws IOException { HandleChannel.this.close(); }
            };
            output = new java.io.FilterOutputStream(outputStream) {
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    out.write(bytes, offset, length);
                }
                @Override public void close() throws IOException { HandleChannel.this.close(); }
            };
        }
        public InputStream inputStream() { return input; }
        public OutputStream outputStream() { return output; }
        public void close() throws IOException {
            synchronized (handle) {
                if (closed) return;
                closed = true;
                handle.channels.remove(this);
            }
            raw.close();
        }
    }

    private static final class OwnedStreams implements AvfVsockChannel {
        private final InputStream input;
        private final OutputStream output;
        private final InputStream inputView;
        private final OutputStream outputView;
        private volatile boolean closed;
        OwnedStreams(InputStream input, OutputStream output) {
            this.input = input;
            this.output = output;
            inputView = new java.io.FilterInputStream(input) {
                @Override public void close() throws IOException { OwnedStreams.this.close(); }
            };
            outputView = new java.io.FilterOutputStream(output) {
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    out.write(bytes, offset, length);
                }
                @Override public void close() throws IOException { OwnedStreams.this.close(); }
            };
        }
        public InputStream inputStream() { return inputView; }
        public OutputStream outputStream() { return outputView; }
        public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            IOException failure = closeStream(output, null);
            failure = closeStream(input, failure);
            if (failure != null) throw failure;
        }
        private static IOException closeStream(AutoCloseable stream, IOException primary) {
            try { stream.close(); }
            catch (Exception failure) {
                IOException io = failure instanceof IOException value ? value : new IOException("FD stream close failed", failure);
                if (primary == null) return io;
                if (primary != io) primary.addSuppressed(io);
            }
            return primary;
        }
    }

    private Shape loadShape() throws ReflectiveOperationException {
        Class<?> ctx = classLoader.loadClass("android.content.Context");
        Class<?> manager = classLoader.loadClass(PACKAGE + "VirtualMachineManager");
        Class<?> config = classLoader.loadClass(PACKAGE + "VirtualMachineConfig");
        Class<?> configBuilder = classLoader.loadClass(PACKAGE + "VirtualMachineConfig$Builder");
        Class<?> image = classLoader.loadClass(PACKAGE + "VirtualMachineCustomImageConfig");
        Class<?> imageBuilder = classLoader.loadClass(PACKAGE + "VirtualMachineCustomImageConfig$Builder");
        Class<?> disk = classLoader.loadClass(PACKAGE + "VirtualMachineCustomImageConfig$Disk");
        Class<?> vm = classLoader.loadClass(PACKAGE + "VirtualMachine");
        ctx.getMethod("getSystemService", Class.class);
        ctx.getMethod("checkSelfPermission", String.class);
        configBuilder.getConstructor(ctx);
        imageBuilder.getConstructor();
        configBuilder.getMethod("setCustomImageConfig", image);
        configBuilder.getMethod("setProtectedVm", boolean.class);
        configBuilder.getMethod("setMemoryBytes", long.class);
        configBuilder.getMethod("setCpuTopology", int.class);
        configBuilder.getMethod("build");
        imageBuilder.getMethod("setName", String.class);
        imageBuilder.getMethod("setKernelPath", String.class);
        imageBuilder.getMethod("setInitrdPath", String.class);
        imageBuilder.getMethod("addParam", String.class);
        imageBuilder.getMethod("addDisk", disk);
        imageBuilder.getMethod("useNetwork", boolean.class);
        imageBuilder.getMethod("build");
        disk.getMethod("RWDisk", String.class);
        manager.getMethod("getCapabilities");
        manager.getMethod("create", String.class, config);
        manager.getMethod("get", String.class);
        manager.getMethod("delete", String.class);
        vm.getMethod("getConfig");
        manager.getField("CAPABILITY_NON_PROTECTED_VM");
        config.getField("CPU_TOPOLOGY_ONE_CPU");
        vm.getMethod("run");
        vm.getMethod("getStatus");
        vm.getMethod("stop");
        vm.getMethod("close");
        Class<?> pfd = classLoader.loadClass("android.os.ParcelFileDescriptor");
        if (vm.getMethod("connectVsock", long.class).getReturnType() != pfd
                || vm.getMethod("getStatus").getReturnType() != int.class
                || vm.getMethod("run").getReturnType() != void.class
                || vm.getMethod("stop").getReturnType() != void.class
                || vm.getMethod("close").getReturnType() != void.class
                || manager.getMethod("create", String.class, config).getReturnType() != vm
                || manager.getMethod("get", String.class).getReturnType() != vm
                || vm.getMethod("getConfig").getReturnType() != config
                || manager.getMethod("getCapabilities").getReturnType() != int.class
                || configBuilder.getMethod("build").getReturnType() != config
                || imageBuilder.getMethod("build").getReturnType() != image
                || disk.getMethod("RWDisk", String.class).getReturnType() != disk) {
            throw new NoSuchMethodException("official config/create/run/PFD signatures are required");
        }
        pfd.getMethod("close");
        return new Shape(ctx, manager, config, configBuilder, image, imageBuilder, disk, vm);
    }

    private record Shape(Class<?> context, Class<?> manager, Class<?> config, Class<?> configBuilder,
            Class<?> image, Class<?> imageBuilder, Class<?> disk, Class<?> vm) {}

    private synchronized void applyAccessHook(List<String> notes) {
        if (!exemptionApplied && hiddenApiExemption != null) {
            try {
                hiddenApiExemption.run();
                exemptionApplied = true;
            } catch (RuntimeException | LinkageError failure) {
                throw error(AvfEngineException.REFLECTION_UNAVAILABLE, "supplied access hook failed", failure);
            }
        }
        notes.add(hiddenApiExemption == null ? "access-hook: none supplied" : "access-hook: supplied");
    }

    private void requirePermission(Shape shape, String name, List<String> notes) {
        Object result = invoke(shape.context, context, "checkSelfPermission", new Class<?>[] {String.class},
                new Object[] {name}, AvfEngineException.PERMISSION_DENIED, notes);
        if (!(result instanceof Integer value) || value != 0) {
            throw error(AvfEngineException.PERMISSION_DENIED, "required permission missing: " + name, null);
        }
    }

    private static Object construct(Class<?> type, Class<?>[] params, Object[] args) {
        try {
            return type.getConstructor(params).newInstance(args);
        } catch (InvocationTargetException failure) {
            throw mapped("builder construction", AvfEngineException.VM_CREATE_FAILED, failure.getCause());
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw error(AvfEngineException.REFLECTION_UNAVAILABLE, "AVF builder is inaccessible", failure);
        }
    }

    private static int intField(Class<?> type, String name) {
        try { return type.getField(name).getInt(null); }
        catch (ReflectiveOperationException | IllegalArgumentException failure) {
            throw error(AvfEngineException.REFLECTION_UNAVAILABLE, "AVF constant is inaccessible: " + name, failure);
        }
    }

    private static void set(Class<?> type, Object target, String method, Class<?> param, Object value,
            List<String> notes) {
        invoke(type, target, method, new Class<?>[] {param}, new Object[] {value},
                AvfEngineException.VM_CREATE_FAILED, notes);
    }

    private static Object invoke(Class<?> type, Object target, String name, Class<?>[] params,
            Object[] args, String reason, List<String> notes) {
        try {
            Object value = type.getMethod(name, params).invoke(target, args);
            notes.add(name + ": applied");
            return value;
        } catch (InvocationTargetException failure) {
            throw mapped(name, reason, failure.getCause());
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw error(AvfEngineException.REFLECTION_UNAVAILABLE, "required AVF method inaccessible: " + name, failure);
        } catch (SecurityException | IllegalArgumentException failure) {
            throw error(AvfEngineException.REFLECTION_UNAVAILABLE, "required AVF method rejected: " + name, failure);
        }
    }

    private static AvfEngineException mapped(String stage, String reason, Throwable cause) {
        Throwable boundary = cause instanceof IOException && cause.getCause() != null ? cause.getCause() : cause;
        if (boundary instanceof SecurityException) reason = AvfEngineException.PERMISSION_DENIED;
        else if (boundary instanceof UnsupportedOperationException) reason = AvfEngineException.HARDWARE_UNSUPPORTED;
        else if (boundary instanceof ReflectiveOperationException || boundary instanceof LinkageError) {
            reason = AvfEngineException.REFLECTION_UNAVAILABLE;
        }
        return error(reason, "AVF operation failed: " + stage, cause);
    }

    private static AvfEngineException error(String reason, String message, Throwable cause) {
        return new AvfEngineException(reason, message, cause);
    }

    private static void closeAfterFailure(Class<?> type, Object object, Throwable primary) {
        try { type.getMethod("close").invoke(object); }
        catch (InvocationTargetException failure) { primary.addSuppressed(failure.getCause()); }
        catch (ReflectiveOperationException | RuntimeException failure) { primary.addSuppressed(failure); }
    }

    private ReflectiveHandle requireHandle(AvfVirtualMachineHandle handle) {
        if (!(handle instanceof ReflectiveHandle reflective) || reflective.owner != this) {
            throw error(AvfEngineException.VSOCK_CONNECT_FAILED, "VM handle belongs to another adapter", null);
        }
        return reflective;
    }

    private static final class ReflectiveHandle implements AvfVirtualMachineHandle {
        /** Status polling interval while waiting for a bounded stop. */
        private static final long STOP_POLL_MS = 25L;
        /** Extra probe window after the platform handle is force-released. */
        private static final long STOP_FORCE_PROBE_MS = 500L;

        private final ReflectiveAvfVirtualMachineApi owner;
        private final String instanceId;
        private final Class<?> vmClass;
        private final Object vm;
        private final List<String> notes;
        private final java.util.Set<HandleChannel> channels =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        private volatile boolean hostStopped;
        private volatile boolean stopRequested;
        private volatile AvfStopResult lastStopResult;

        ReflectiveHandle(ReflectiveAvfVirtualMachineApi owner, String id, Class<?> vmClass,
                Object vm, List<String> notes) {
            this.owner = owner;
            instanceId = id;
            this.vmClass = vmClass;
            this.vm = vm;
            this.notes = new ArrayList<>(notes);
        }
        public String instanceId() { return instanceId; }
        public synchronized List<String> reflectionNotes() { return List.copyOf(notes); }

        /** Numeric platform status without diagnostics or throwing on a closed VM. */
        public int status() {
            try {
                Object value = vmClass.getMethod("getStatus").invoke(vm);
                if (!(value instanceof Integer number)) return -1;
                int status = number;
                return status >= 0 && status <= 2 ? status : -1;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                return -1;
            }
        }

        /** Optional platform exit status; -1 when the platform does not expose one. */
        public int exitStatus() {
            try {
                Object value = vmClass.getMethod("getExitStatus").invoke(vm);
                if (value instanceof Integer number && number >= 0) return number;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unobservable) {
                // Older platforms have no exit-status surface.
            }
            return -1;
        }

        public int waitFor() throws InterruptedException {
            return waitForExit().exitCode();
        }

        /**
         * Blocks until STOPPED or DELETED. A requested stop is distinguished
         * from a guest-initiated stop; a DELETED VM never returns a zero exit;
         * an unconfirmable stop is a typed failure, not a normal exit.
         */
        public AvfExitInfo waitForExit() throws InterruptedException {
            long started = System.nanoTime();
            while (true) {
                int status = status();
                long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - started);
                if (status == 0) {
                    int observedExit = exitStatus();
                    if (observedExit > 0) {
                        return AvfExitInfo.crashed(observedExit, elapsedMs,
                                "platform exit status=" + observedExit);
                    }
                    return stopRequested
                            ? AvfExitInfo.stopRequested(elapsedMs)
                            : AvfExitInfo.normal(elapsedMs);
                }
                if (status == 2) return AvfExitInfo.deleted();
                if (status < 0) {
                    if (lastStopResult == AvfStopResult.UNCONFIRMED) throw unconfirmedStop();
                    return AvfExitInfo.observeFailed("platform status unavailable");
                }
                synchronized (this) {
                    if (lastStopResult == AvfStopResult.UNCONFIRMED) throw unconfirmedStop();
                    wait(100L);
                }
            }
        }

        private void ensureOpen() {
            if (hostStopped) throw closedFailure();
        }
        private AvfEngineException closedFailure() {
            return error(AvfEngineException.VSOCK_CONNECT_FAILED, "VM handle is closed", null);
        }
        private static AvfEngineException unconfirmedStop() {
            return error(AvfEngineException.VM_STOP_UNCONFIRMED,
                    "platform never reported STOPPED or DELETED", null);
        }

        /** Compatibility stop: bounded, but with no grace window. */
        public void stop() {
            stopRuntime(0L);
        }

        /**
         * Single-owner bounded stop: request, probe, force-release, re-probe.
         * The platform stop/close calls happen outside the wait loop; a
         * confirmed or already-stopped result is never requested twice.
         */
        public synchronized AvfStopResult stopRuntime(long graceMs) {
            if (lastStopResult == AvfStopResult.CONFIRMED
                    || lastStopResult == AvfStopResult.ALREADY_STOPPED) {
                return lastStopResult;
            }
            int initial = status();
            if (initial == 0 || initial == 2) {
                stopRequested = true;
                hostStopped = true;
                notifyAll();
                List<HandleChannel> closing = detachChannels();
                closeChannels(closing, new ArrayList<>());
                lastStopResult = AvfStopResult.ALREADY_STOPPED;
                return lastStopResult;
            }

            stopRequested = true;
            hostStopped = true;
            notifyAll();
            List<HandleChannel> closing = detachChannels();
            List<String> stoppedNotes = new ArrayList<>();
            try {
                invoke(vmClass, vm, "stop", new Class<?>[0], new Object[0],
                        AvfEngineException.VM_CREATE_FAILED, stoppedNotes);
            } catch (AvfEngineException failure) {
                // A throwing platform call is not evidence that the VM exited.
                stoppedNotes.add("stop: failed (" + failure.reason() + ")");
            }
            boolean stopped = awaitStatus(graceMs);
            if (!stopped) {
                // Force-release the platform handle, then probe once more.
                try {
                    invoke(vmClass, vm, "close", new Class<?>[0], new Object[0],
                            AvfEngineException.VM_CREATE_FAILED, stoppedNotes);
                } catch (AvfEngineException failure) {
                    stoppedNotes.add("close: failed (" + failure.reason() + ")");
                }
                stopped = awaitStatus(STOP_FORCE_PROBE_MS);
            } else {
                try {
                    invoke(vmClass, vm, "close", new Class<?>[0], new Object[0],
                            AvfEngineException.VM_CREATE_FAILED, stoppedNotes);
                } catch (AvfEngineException failure) {
                    stoppedNotes.add("close: failed (" + failure.reason() + ")");
                }
            }
            closeChannels(closing, stoppedNotes);
            lastStopResult = stopped ? AvfStopResult.CONFIRMED : AvfStopResult.UNCONFIRMED;
            stoppedNotes.add("stop-result: " + lastStopResult.name());
            synchronized (this) { notes.addAll(stoppedNotes); }
            return lastStopResult;
        }

        /** Must be called while holding this handle's monitor. */
        private List<HandleChannel> detachChannels() {
            List<HandleChannel> closing = new ArrayList<>(channels);
            channels.clear();
            return closing;
        }

        private static void closeChannels(List<HandleChannel> closing, List<String> stoppedNotes) {
            for (HandleChannel channel : closing) {
                try {
                    channel.close();
                } catch (IOException | RuntimeException failure) {
                    stoppedNotes.add("channel-close: failed");
                }
            }
        }

        private boolean awaitStatus(long graceMs) {
            long deadline = System.nanoTime()
                    + Math.max(0L, graceMs) * 1_000_000L;
            while (true) {
                int status = status();
                if (status == 0 || status == 2) return true;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) return false;
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
    }
}
