package android.system.virtualmachine;

/**
 * Test-only stand-in for the hidden AVF config base class. Mirrors just enough
 * of the real surface for {@code ReflectiveAvfVirtualMachineApi} to be executed
 * as-is on the JVM (Ticket 34 AC-4: contract tests on a simulated AVF adapter).
 */
public class VirtualMachineConfig {
    /** Real signature carries protected flags; the fixture needs no state here. */
    protected VirtualMachineConfig() {}
}
