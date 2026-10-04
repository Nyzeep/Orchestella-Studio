package android.system.virtualmachine;

import java.io.IOException;

/**
 * Test-only stand-in for the hidden AVF VirtualMachine. vsock connects are
 * routed through the fixture network; a stopped VM refuses new connections.
 */
public final class VirtualMachine {
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

    public Status getStatus() {
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
}
