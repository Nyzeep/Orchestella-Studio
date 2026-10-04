package io.github.nyzeep.droidlayer.runtime.engineavf;

import android.system.virtualmachine.VirtualMachineCustomImageConfig;
import java.net.URL;
import java.net.URLClassLoader;

/** Wiring helpers that let the reflective adapter run against the fake SDK. */
public final class AvfVirtualMachineApiFixture {
    private AvfVirtualMachineApiFixture() {}

    /** An api bound to the simulated guest through the test-visible fake SDK. */
    public static ReflectiveAvfVirtualMachineApi apiOver(SimulatedAvfGuest guest) {
        return new ReflectiveAvfVirtualMachineApi(
                guest.newContext(guest.newManager(null)),
                AvfVirtualMachineApiFixture.class.getClassLoader());
    }

    /** An api whose manager records observed configs before booting the guest. */
    public static ReflectiveAvfVirtualMachineApi recordingApiOver(SimulatedAvfGuest guest) {
        java.util.function.Consumer<VirtualMachineCustomImageConfig> recorder =
                guest.observedConfigs()::add;
        return new ReflectiveAvfVirtualMachineApi(
                guest.newContext(guest.newManager(recorder)),
                AvfVirtualMachineApiFixture.class.getClassLoader());
    }

    /** A classloader that cannot resolve the AVF surface (simulates old/absent SDK). */
    public static ClassLoader sdkAbsentClassLoader() {
        return new URLClassLoader(new URL[0], null);
    }

    /** An api over a context without a virtualization service. */
    public static ReflectiveAvfVirtualMachineApi apiWithoutService() {
        return new ReflectiveAvfVirtualMachineApi(
                new Object() {
                    public Object getSystemService(String name) {
                        return null;
                    }
                },
                AvfVirtualMachineApiFixture.class.getClassLoader());
    }
}
