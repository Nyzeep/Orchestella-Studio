package android.system.virtualmachine;

import java.util.HashMap;
import java.util.Map;

/** Official manager shape; the factory lets a test keep the created VM. */
public final class VirtualMachineManager {
    public static final int CAPABILITY_PROTECTED_VM = 1;
    public static final int CAPABILITY_NON_PROTECTED_VM = 2;

    @FunctionalInterface
    public interface VmFactory {
        VirtualMachine create(String name, VirtualMachineConfig config);
    }

    private final VmFactory factory;
    private final int capabilities;
    private final Map<String, VirtualMachine> persisted = new HashMap<>();
    private int creates;
    private int gets;
    private int deletes;
    private int getOrCreates;
    private VirtualMachineConfig lastConfig;
    private VirtualMachine lastVm;
    private boolean capabilityReadThrows;
    private boolean getThrows;
    private VirtualMachineException createFailure;

    public VirtualMachineManager(VmFactory factory) {
        this(factory, CAPABILITY_PROTECTED_VM | CAPABILITY_NON_PROTECTED_VM);
    }

    public VirtualMachineManager(VmFactory factory, int capabilities) {
        this.factory = factory;
        this.capabilities = capabilities;
    }

    public int getCapabilities() {
        if (capabilityReadThrows) {
            throw new RuntimeException("capability read failed");
        }
        return capabilities;
    }

    public synchronized VirtualMachine create(String name, VirtualMachineConfig config)
            throws VirtualMachineException {
        creates++;
        lastConfig = config;
        if (createFailure != null) {
            throw createFailure;
        }
        if (persisted.containsKey(name)) {
            throw new VirtualMachineException("same-name persistent VM exists");
        }
        VirtualMachine vm = factory.create(name, config);
        if (vm != null) {
            vm.recordConfigForTest(config);
            persisted.put(name, vm);
            lastVm = vm;
        }
        return vm;
    }

    public synchronized VirtualMachine get(String name) throws VirtualMachineException {
        gets++;
        if (getThrows) {
            throw new VirtualMachineException("fixture get failed");
        }
        return persisted.get(name);
    }

    public synchronized VirtualMachine getOrCreate(String name, VirtualMachineConfig config)
            throws VirtualMachineException {
        getOrCreates++;
        VirtualMachine vm = get(name);
        return vm == null ? create(name, config) : vm;
    }

    public synchronized void delete(String name) {
        deletes++;
        persisted.remove(name);
    }

    public void setCapabilityReadThrowsForTest(boolean value) {
        capabilityReadThrows = value;
    }

    public void setGetThrowsForTest(boolean value) {
        getThrows = value;
    }

    public void setCreateFailureForTest(VirtualMachineException value) {
        createFailure = value;
    }

    public synchronized void persistForTest(String name, VirtualMachine vm) {
        persisted.put(name, vm);
    }

    public int createCallsForTest() {
        return creates;
    }

    public int getCallsForTest() {
        return gets;
    }

    public int deleteCallsForTest() {
        return deletes;
    }

    public int getOrCreateCallsForTest() {
        return getOrCreates;
    }

    public synchronized VirtualMachineConfig lastConfigForTest() {
        return lastConfig;
    }

    public synchronized VirtualMachine lastVmForTest() {
        return lastVm;
    }
}
