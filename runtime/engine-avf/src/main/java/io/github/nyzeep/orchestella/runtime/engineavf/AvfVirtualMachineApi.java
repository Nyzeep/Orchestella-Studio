package io.github.nyzeep.orchestella.runtime.engineavf;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * The seam between the strategy-neutral engine and the device AVF SDK. The
 * production implementation drives the hidden {@code android.system.virtualmachine.*}
 * surface reflectively; JVM tests inject a simulated implementation so the
 * same contract tests run against both.
 */
public interface AvfVirtualMachineApi {

    /** Whether the reflective SDK surface can be resolved at all on this host. */
    boolean isReflectionAvailable();

    /**
     * Creates and starts one non-protected custom VM from the spec (custom
     * kernel + initramfs + raw root disk). Throws {@link AvfEngineException}
     * with a precise reason on any failure.
     */
    AvfVirtualMachineHandle startCustomVm(AvfLaunchSpec spec) throws AvfEngineException;

    /**
     * Launch variant that records the create/run boundaries. Implementations
     * that cannot observe them keep the coarse behaviour; the engine still
     * records the stages around this call.
     */
    default AvfVirtualMachineHandle startCustomVm(AvfLaunchSpec spec, AvfLaunchTrace trace)
            throws AvfEngineException {
        AvfVirtualMachineHandle handle = startCustomVm(spec);
        trace.mark(AvfLaunchTrace.Stage.VM_CREATED);
        trace.mark(AvfLaunchTrace.Stage.RUN_RETURNED);
        return handle;
    }

    /**
     * Connects one vsock channel to the guest. Guests serve the control and
     * terminal ports only after boot, so implementations may need retries.
     */
    AvfVsockChannel connectVsock(AvfVirtualMachineHandle handle, int port)
            throws AvfEngineException;

    /** Opaque handle to one started custom VM. */
    interface AvfVirtualMachineHandle {
        String instanceId();

        /** Recorded reflection decisions; diagnostics only, never UI copy. */
        List<String> reflectionNotes();

        /**
         * Blocks until the guest is observed to exit. Implementations that
         * cannot observe guest status return once the host stops the VM.
         */
        int waitFor() throws InterruptedException;

        /**
         * Typed exit observation. The compatibility {@link #waitFor()} delegates
         * here and returns {@link AvfExitInfo#exitCode()}, so a crash or a
         * DELETED VM can never surface as a clean zero exit.
         */
        default AvfExitInfo waitForExit() throws InterruptedException {
            int code = waitFor();
            return code == 0 ? AvfExitInfo.normal(0L) : AvfExitInfo.crashed(code, "compat waitFor");
        }

        /** Platform status: 0 stopped, 1 running, 2 deleted, -1 unknown. */
        default int status() {
            return -1;
        }

        /** Best-effort host-side stop of the VM. */
        void stop();

        /**
         * Bounded stop protocol: request, probe status within the grace window,
         * force-release the platform handle when needed, then probe again.
         * Implementations that cannot observe status must return
         * {@link AvfStopResult#UNCONFIRMED} rather than assume success.
         */
        default AvfStopResult stopRuntime(long graceMs) {
            try {
                stop();
            } catch (RuntimeException failure) {
                return AvfStopResult.UNCONFIRMED;
            }
            return AvfStopResult.UNCONFIRMED;
        }
    }

    /**
     * Android-boundary adapter for a verified ParcelFileDescriptor returned by connectVsock(long).
     * Success transfers ownership to the returned channel. On failure the caller closes the original
     * descriptor; the adapter must first release any duplicates it created. No descriptor or stream
     * may be retained after failure. Kept Android-free so the App can inject its typed FD adapter.
     */
    @FunctionalInterface
    interface DescriptorAdapter {
        AvfVsockChannel adopt(Object parcelFileDescriptor) throws IOException;
    }

    /** One bidirectional host-guest vsock channel. Close must release both directions idempotently. */
    interface AvfVsockChannel extends AutoCloseable {
        InputStream inputStream();

        OutputStream outputStream();

        @Override
        void close() throws IOException;
    }
}
