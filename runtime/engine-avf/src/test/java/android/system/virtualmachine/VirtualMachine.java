package android.system.virtualmachine;

import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.util.function.IntSupplier;

/** Official run/status/connectVsock(long) shape over a JVM guest network. */
public class VirtualMachine implements AutoCloseable {
    public static final int STATUS_STOPPED = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_DELETED = 2;
    private final FakeVsockNetwork network;
    private volatile boolean running;
    private int runCount;
    private int stopCount;
    private int closeCount;
    private VirtualMachineConfig config;
    public RuntimeException configReadFailureForTest;
    public RuntimeException stopFailureForTest;
    /** Optional observed platform exit status; null keeps it unobservable. */
    public Integer exitStatusForTest;

    private VirtualMachine(FakeVsockNetwork network) { this.network = network; }
    public static VirtualMachine over(FakeVsockNetwork network) {
        return new VirtualMachine(network);
    }
    public void run() throws Exception {
        if (running) throw new IllegalStateException("already running");
        runCount++;
        running = true;
    }
    public int getStatus() { return running ? STATUS_RUNNING : STATUS_STOPPED; }
    /** Optional exit-status surface; production AOSP may not have this method. */
    public int getExitStatus() {
        return exitStatusForTest == null ? -1 : exitStatusForTest;
    }
    public VirtualMachineConfig getConfig() {
        if (configReadFailureForTest != null) throw configReadFailureForTest;
        return config;
    }
    /** Fixture persistence observation; not called by the production adapter. */
    public void recordConfigForTest(VirtualMachineConfig value) { config = value; }
    public ParcelFileDescriptor connectVsock(long port) throws IOException {
        if (!running) throw new IllegalStateException("VM is stopped");
        return new ParcelFileDescriptor(network.connect(Math.toIntExact(port)));
    }
    public void stop() {
        stopCount++;
        if (stopFailureForTest != null) throw stopFailureForTest;
        running = false;
        if (network != null) network.closeGuestSide();
    }
    public int runCountForTest() { return runCount; }
    public int stopCountForTest() { return stopCount; }
    public int closeCountForTest() { return closeCount; }
    @Override public void close() {
        closeCount++;
        if (running) stop();
    }
    @FunctionalInterface public interface RunAction { void run() throws Exception; }
    @FunctionalInterface public interface VsockConnector { ParcelFileDescriptor connect(long port) throws IOException; }
    public static VirtualMachine withBehavior(RunAction run, VsockConnector connector) {
        return new VirtualMachine(null) {
            @Override public void run() throws Exception {
                super.run();
                run.run();
            }
            @Override public ParcelFileDescriptor connectVsock(long port) throws IOException {
                if (getStatus() != STATUS_RUNNING) throw new IllegalStateException("VM is stopped");
                return connector.connect(port);
            }
        };
    }
    public static VirtualMachine withNumericStatus(IntSupplier source) {
        return new VirtualMachine(null) {
            @Override public int getStatus() { return source.getAsInt(); }
        };
    }
}
