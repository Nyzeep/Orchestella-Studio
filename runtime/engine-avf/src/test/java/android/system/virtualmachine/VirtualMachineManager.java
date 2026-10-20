package android.system.virtualmachine;

/** Official manager method names; fixture hook returns a stopped VM before run(). */
public final class VirtualMachineManager {
    public static final int CAPABILITY_PROTECTED_VM = 1;
    public static final int CAPABILITY_NON_PROTECTED_VM = 2;
    public interface VmStarter {
        VirtualMachine start(String name, VirtualMachineConfig config) throws Exception;
    }
    private final VmStarter starter;
    private final int capabilities;
    private final java.util.Map<String, VirtualMachine> persisted = new java.util.HashMap<>();
    private int creates;
    private int gets;
    private int deletes;
    private int getOrCreates;
    private VirtualMachineConfig lastConfig;
    public VirtualMachineManager(VmStarter starter) {
        this(starter, CAPABILITY_PROTECTED_VM | CAPABILITY_NON_PROTECTED_VM);
    }
    public VirtualMachineManager(VmStarter starter, int capabilities) {
        this.starter = starter;
        this.capabilities = capabilities;
    }
    public int getCapabilities() { return capabilities; }
    public synchronized VirtualMachine create(String name, VirtualMachineConfig config) throws Exception {
        creates++;
        lastConfig = config;
        if (persisted.containsKey(name)) throw new java.io.IOException("same-name persistent VM exists");
        VirtualMachine vm = starter.start(name, config);
        if (vm != null) {
            vm.recordConfigForTest(config);
            persisted.put(name, vm);
        }
        return vm;
    }
    public synchronized VirtualMachine get(String name) throws Exception {
        gets++;
        return persisted.get(name);
    }
    public synchronized VirtualMachine getOrCreate(String name, VirtualMachineConfig config) throws Exception {
        getOrCreates++;
        VirtualMachine vm = get(name);
        return vm == null ? create(name, config) : vm;
    }
    public synchronized void delete(String name) {
        deletes++;
        persisted.remove(name);
    }
    public int createCallsForTest() { return creates; }
    public int getCallsForTest() { return gets; }
    public int deleteCallsForTest() { return deletes; }
    public int getOrCreateCallsForTest() { return getOrCreates; }
    public synchronized VirtualMachineConfig lastConfigForTest() { return lastConfig; }
    /** Seeds a persistent VM without going through create(), for rebuild tests. */
    public synchronized void persistForTest(String name, VirtualMachine vm) { persisted.put(name, vm); }
}
