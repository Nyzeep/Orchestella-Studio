package io.github.nyzeep.droidlayer.runtime.engineavf;

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

        /** Best-effort host-side stop of the VM. */
        void stop();
    }

    /** One bidirectional host-guest vsock channel. */
    interface AvfVsockChannel extends AutoCloseable {
        InputStream inputStream();

        OutputStream outputStream();

        @Override
        void close() throws IOException;
    }
}
