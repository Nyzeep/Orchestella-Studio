package io.github.nyzeep.orchestella.runtime.engineqemu;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Launches the packaged QEMU executable with the fixed argument array built by
 * {@link QemuLaunchSpec}. The default process factory uses
 * {@code java.lang.ProcessBuilder} (direct exec, never a shell); tests inject
 * a factory seam. The caller owns waiting for the guest to exit.
 */
public final class QemuProcessLauncher {
    /** Live QEMU process view; the real implementation wraps {@code Process}. */
    public interface ProcessHandle {
        /** Blocks until exit; callers use the bounded overload for stop paths. */
        int waitFor() throws InterruptedException;

        /** Waits at most {@code timeout}; false means the process is still alive. */
        boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException;

        /** Requests a normal termination. */
        void destroy();

        /** Requests an immediate termination when supported. */
        void destroyForcibly();

        /** True while the process has not exited. */
        boolean isAlive();

        /** OS process id, or -1 when the implementation cannot expose one. */
        default long pid() {
            return -1L;
        }
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
                public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
                    return process.waitFor(timeout, unit);
                }

                @Override
                public void destroy() {
                    process.destroy();
                }

                @Override
                public void destroyForcibly() {
                    process.destroyForcibly();
                }

                @Override
                public boolean isAlive() {
                    return process.isAlive();
                }

                @Override
                public long pid() {
                    return process.pid();
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
        if (spec == null) {
            throw new IllegalArgumentException("null launch spec");
        }
        if (spec.length == 0) {
            throw new IllegalArgumentException("empty launch spec");
        }
        return new Launched(factory.start(Arrays.copyOf(spec, spec.length)), spec);
    }
}
