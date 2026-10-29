package android.system.virtualmachine;

import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.util.concurrent.Executor;

/** Official app-payload VM shape with injectable lifecycle events. */
public class VirtualMachine implements AutoCloseable {
    public static final int STATUS_STOPPED = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_DELETED = 2;

    private final FakeVsockNetwork network;
    private VirtualMachineManager ownerManager;
    private VirtualMachineConfig config;
    private VirtualMachineCallback callback;
    private Executor callbackExecutor;
    private int status = STATUS_STOPPED;
    private int runCount;
    private int stopCount;
    private int closeCount;
    private int callbackSetCount;
    private int lastConnectPort = -1;
    private boolean callbackInstallThrows;
    private boolean statusThrows;
    private boolean stopThrows;
    private boolean closeThrows;
    private boolean connectThrows;
    private boolean stopConverges = true;
    private boolean closeConverges = true;
    private boolean errorStops = true;
    private VirtualMachineException runFailure;
    private RuntimeException runtimeRunFailure;
    private RuntimeException configReadFailure;
    public java.util.function.Consumer<ParcelFileDescriptor> pfdConfigurerForTest;

    public VirtualMachine(FakeVsockNetwork network) {
        this.network = network;
    }

    public static VirtualMachine over(FakeVsockNetwork network) {
        return new VirtualMachine(network);
    }

    public void run() throws VirtualMachineException {
        if (status != STATUS_STOPPED) {
            throw new VirtualMachineException("VM is not stopped");
        }
        if (runFailure != null) {
            throw runFailure;
        }
        if (runtimeRunFailure != null) {
            throw runtimeRunFailure;
        }
        runCount++;
        status = STATUS_RUNNING;
    }

    public int getStatus() {
        if (statusThrows) {
            throw new RuntimeException("platform status is unavailable");
        }
        return status;
    }

    public VirtualMachineConfig getConfig() {
        if (configReadFailure != null) {
            throw configReadFailure;
        }
        return config;
    }

    public void setCallback(Executor executor, VirtualMachineCallback value) {
        if (callbackInstallThrows) {
            throw new RuntimeException("callback installation failed");
        }
        callbackSetCount++;
        callbackExecutor = executor;
        callback = value;
    }

    public void clearCallback() {
        callback = null;
        callbackExecutor = null;
    }

    public ParcelFileDescriptor connectVsock(long port) throws IOException {
        if (status != STATUS_RUNNING) {
            throw new IllegalStateException("VM is not running");
        }
        if (connectThrows) {
            throw new IOException("fixture connect failed");
        }
        if (network == null) {
            throw new IOException("no fake vsock network");
        }
        lastConnectPort = Math.toIntExact(port);
        ParcelFileDescriptor descriptor =
                new ParcelFileDescriptor(network.connect(lastConnectPort));
        if (pfdConfigurerForTest != null) {
            pfdConfigurerForTest.accept(descriptor);
        }
        return descriptor;
    }

    public void stop() {
        stopCount++;
        if (stopThrows) {
            throw new RuntimeException("fixture stop failed");
        }
        if (stopConverges) {
            status = STATUS_STOPPED;
            deliverStopped(VirtualMachineCallback.STOP_REASON_SHUTDOWN);
        }
    }

    @Override
    public void close() {
        closeCount++;
        if (closeThrows) {
            throw new RuntimeException("fixture close failed");
        }
        if (closeConverges && status == STATUS_RUNNING) {
            status = STATUS_STOPPED;
            deliverStopped(VirtualMachineCallback.STOP_REASON_SHUTDOWN);
        }
    }

    public void recordConfigForTest(VirtualMachineConfig value) {
        config = value;
    }

    /** Attached by the manager on create, so delete() can evict like the platform does. */
    public void attachManagerForTest(VirtualMachineManager manager) {
        ownerManager = manager;
    }

    /**
     * Platform {@code VirtualMachine.delete()} removes the instance files and the
     * manager's record; a later getOrCreate builds a fresh VM.
     */
    public void delete() {
        status = STATUS_DELETED;
        if (ownerManager != null) {
            ownerManager.evictForTest(this);
        }
    }

    public void setStatusForTest(int value) {
        status = value;
    }

    public void setStatusThrowsForTest(boolean value) {
        statusThrows = value;
    }

    public void setStopThrowsForTest(boolean value) {
        stopThrows = value;
    }

    public void setCloseThrowsForTest(boolean value) {
        closeThrows = value;
    }

    public void setConnectThrowsForTest(boolean value) {
        connectThrows = value;
    }

    public void setStopConvergesForTest(boolean value) {
        stopConverges = value;
    }

    public void setCloseConvergesForTest(boolean value) {
        closeConverges = value;
    }

    public void setErrorStopsForTest(boolean value) {
        errorStops = value;
    }

    public void setCallbackInstallThrowsForTest(boolean value) {
        callbackInstallThrows = value;
    }

    public void setRunFailureForTest(VirtualMachineException value) {
        runFailure = value;
    }

    public void setRuntimeRunFailureForTest(RuntimeException value) {
        runtimeRunFailure = value;
    }

    public void setConfigReadFailureForTest(RuntimeException value) {
        configReadFailure = value;
    }

    public void deliverPayloadStarted() {
        VirtualMachineCallback current = callback;
        if (current != null) {
            execute(() -> current.onPayloadStarted(this));
        }
    }

    public void deliverPayloadReady() {
        VirtualMachineCallback current = callback;
        if (current != null) {
            execute(() -> current.onPayloadReady(this));
        }
    }

    public void deliverPayloadFinished(int exitCode) {
        VirtualMachineCallback current = callback;
        status = STATUS_STOPPED;
        if (current != null) {
            execute(() -> current.onPayloadFinished(this, exitCode));
            execute(() -> current.onStopped(this, VirtualMachineCallback.STOP_REASON_SHUTDOWN));
        }
    }

    public void deliverError(int errorCode, String message) {
        VirtualMachineCallback current = callback;
        if (current != null) {
            execute(() -> current.onError(this, errorCode, message));
        }
        if (errorStops) {
            status = STATUS_STOPPED;
            if (current != null) {
                execute(() -> current.onStopped(this, VirtualMachineCallback.STOP_REASON_UNKNOWN));
            }
        }
    }

    public void deliverStopped(int reason) {
        status = STATUS_STOPPED;
        VirtualMachineCallback current = callback;
        if (current != null) {
            execute(() -> current.onStopped(this, reason));
        }
    }

    public int runCountForTest() {
        return runCount;
    }

    public int stopCountForTest() {
        return stopCount;
    }

    public int closeCountForTest() {
        return closeCount;
    }

    public int callbackSetCountForTest() {
        return callbackSetCount;
    }

    public int lastConnectPortForTest() {
        return lastConnectPort;
    }

    public VirtualMachineCallback callbackForTest() {
        return callback;
    }

    public int statusForTest() {
        return status;
    }

    private void execute(Runnable action) {
        if (callbackExecutor == null) {
            action.run();
        } else {
            callbackExecutor.execute(action);
        }
    }
}
