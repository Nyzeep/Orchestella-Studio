package android.system.virtualmachine;

import java.io.IOException;
import java.util.function.IntSupplier;

/**
 * Test-only stand-in for the hidden AVF VirtualMachine. vsock connects are
 * routed through the fixture network; a stopped VM refuses new connections.
 * getStatus() is declared to return Object so both the real-device surface
 * (a {@code @Status} int boxed to Integer) and the legacy named-enum stub can
 * be simulated through the same reflective call site.
 */
public class VirtualMachine {
    public enum Status {
        RUNNING,
        STOPPED
    }

    private final android.system.virtualmachine.FakeVsockNetwork network;
    private volatile boolean stopped;

    VirtualMachine(FakeVsockNetwork network) {
        this.network = network;
    }

    /** Fixture-visible construction for cross-package simulated guests. */
    public static VirtualMachine over(FakeVsockNetwork network) {
        return new VirtualMachine(network);
    }

    public Object getStatus() {
        return stopped ? Status.STOPPED : Status.RUNNING;
    }

    public VsockConnection connectVsock(int port) throws IOException {
        if (stopped) {
            throw new IllegalStateException("VM is stopped");
        }
        return network.connect(port);
    }

    public void stop() {
        stopped = true;
        network.closeGuestSide();
    }

    /**
     * Status-only stub for the real AOSP surface, where getStatus() returns
     * the {@code @Status} int (0=STOPPED, 1=RUNNING, 2=DELETED) boxed to an
     * Integer by reflection. The status source is externally driven so tests
     * can stage RUNNING → STOPPED transitions; no vsock fixture is attached.
     */
    public static VirtualMachine withNumericStatus(IntSupplier statusSource) {
        return new VirtualMachine(null) {
            @Override
            public Object getStatus() {
                return statusSource.getAsInt();
            }
        };
    }
}
