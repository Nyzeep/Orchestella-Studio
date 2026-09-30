package io.github.nyzeep.droidlayer.runtime.engineqemu;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Launches the packaged QEMU executable with the fixed argument array built by
 * {@link QemuLaunchSpec}. The default process factory uses
 * {@code java.lang.ProcessBuilder} (direct exec, never a shell); tests inject
 * a factory seam. The caller owns waiting for the guest to exit.
 */
public final class QemuProcessLauncher {
    /** Live QEMU process view; the real implementation wraps {@code Process}. */
    public interface ProcessHandle {
        int waitFor() throws InterruptedException;

        void destroy();
    }

    /** Starts one process for an exact, pre-built argument array. */
    public interface ProcessFactory {
        ProcessHandle start(String[] spec) throws IOException;
    }

    /** One launched QEMU instance. */
    public static final class Launched {
        private final ProcessHandle handle;
        private final String[] spec;

        Launched(ProcessHandle handle, String[] spec) {
            this.handle = handle;
            this.spec = spec;
        }

        public ProcessHandle handle() {
            return handle;
        }

        public String[] spec() {
            return spec.clone();
        }
    }

    private static final class ProcessBuilderFactory implements ProcessFactory {
        @Override
        public ProcessHandle start(String[] spec) throws IOException {
            Process process = new ProcessBuilder(spec).start();
            return new ProcessHandle() {
                @Override
                public int waitFor() throws InterruptedException {
                    return process.waitFor();
                }

                @Override
                public void destroy() {
                    process.destroy();
                }
            };
        }
    }

    private final ProcessFactory factory;

    public QemuProcessLauncher(ProcessFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    public QemuProcessLauncher() {
        this(new ProcessBuilderFactory());
    }

    public Launched launch(String[] spec) throws IOException {
        Objects.requireNonNull(spec, "spec");
        if (spec.length == 0) {
            throw new IllegalArgumentException("empty launch spec");
        }
        return new Launched(factory.start(Arrays.copyOf(spec, spec.length)), spec);
    }
}
