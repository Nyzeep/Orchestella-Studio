package io.github.nyzeep.orchestella.runtime.engineavf;

import android.system.virtualmachine.FakeVsockNetwork;
import android.system.virtualmachine.VirtualMachineCustomImageConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    /**
     * A {@link AvfVsockPortForwarder.VsockChannelFactory} over the fake vsock
     * network, adapting the duck-typed {@code VsockConnection} exactly like the
     * reflective adapter does against the real SDK surface.
     */
    public static AvfVsockPortForwarder.VsockChannelFactory channelFactoryOver(
            FakeVsockNetwork network) {
        return port -> {
            android.system.virtualmachine.VsockConnection raw = network.connect(port);
            return new AvfVirtualMachineApi.AvfVsockChannel() {
                @Override
                public InputStream inputStream() {
                    try {
                        return raw.getInputStream();
                    } catch (IOException failure) {
                        throw new IllegalStateException("vsock stream unavailable", failure);
                    }
                }

                @Override
                public OutputStream outputStream() {
                    try {
                        return raw.getOutputStream();
                    } catch (IOException failure) {
                        throw new IllegalStateException("vsock stream unavailable", failure);
                    }
                }

                @Override
                public void close() throws IOException {
                    raw.getInputStream().close();
                    raw.getOutputStream().close();
                }
            };
        };
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
