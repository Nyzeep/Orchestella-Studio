package android.system.virtualmachine;

/**
 * Test-only stand-in for the hidden AVF manager. The fixture wires a
 * {@link VmStarter} so tests decide what creation does (boot a simulated
 * guest, refuse with a hardware error, deny the permission, ...).
 */
public final class VirtualMachineManager {
    /** Fixture hook deciding what createVm does for one test. */
    public interface VmStarter {
        VirtualMachine start(String name, VirtualMachineConfig config) throws Exception;
    }

    private final VmStarter starter;

    public VirtualMachineManager(VmStarter starter) {
        this.starter = starter;
    }

    public VirtualMachine createVm(String name, VirtualMachineConfig config) throws Exception {
        return starter.start(name, config);
    }

    public VirtualMachine getOrCreateVm(String name, VirtualMachineConfig config) throws Exception {
        return starter.start(name, config);
    }
}
