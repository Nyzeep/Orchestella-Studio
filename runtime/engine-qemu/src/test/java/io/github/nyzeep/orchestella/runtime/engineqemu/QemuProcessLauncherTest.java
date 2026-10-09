package io.github.nyzeep.orchestella.runtime.engineqemu;

import java.util.ArrayList;
import java.util.List;

/** Contract tests for the QEMU process launcher seam. */
public final class QemuProcessLauncherTest {
    public static void main(String[] args) throws Exception {
        List<String[]> started = new ArrayList<>();
        List<QemuProcessLauncher.ProcessHandle> handles = new ArrayList<>();
        QemuProcessLauncher.ProcessFactory factory = spec -> {
            started.add(spec.clone());
            QemuProcessLauncher.ProcessHandle handle = new QemuProcessLauncher.ProcessHandle() {
                @Override
                public int waitFor() {
                    return 0;
                }

                @Override
                public void destroy() {
                    // no-op
                }
            };
            handles.add(handle);
            return handle;
        };

        QemuProcessLauncher launcher = new QemuProcessLauncher(factory);
        String[] spec = QemuLaunchSpec.build("/qemu", "/k", "/i", "/r", "c1", "t1", 512L);
        QemuProcessLauncher.Launched launched = launcher.launch(spec);

        // The exact fixed spec reaches the factory unchanged (no shell wrapper).
        if (started.size() != 1) {
            fail("factory invoked " + started.size() + " times");
        }
        if (!java.util.Arrays.equals(spec, started.get(0))) {
            fail("spec mutated in transit");
        }
        if (launched.handle() != handles.get(0)) {
            fail("handle mismatch");
        }

        // Null/empty spec rejected.
        try {
            launcher.launch(new String[0]);
            fail("empty spec accepted");
        } catch (IllegalArgumentException expected) {
            // required
        }
        try {
            launcher.launch(null);
            fail("null spec accepted");
        } catch (IllegalArgumentException expected) {
            // required
        }

        System.out.println("QEMU_PROCESS_LAUNCHER_TEST_OK passthrough=1 single_start=1 validation=2");
    }

    private static void fail(String reason) {
        System.out.println("QEMU_PROCESS_LAUNCHER_TEST_FAIL reason=" + reason);
        System.exit(1);
    }
}
