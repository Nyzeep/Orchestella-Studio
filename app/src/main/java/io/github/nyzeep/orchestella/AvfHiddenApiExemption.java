package io.github.nyzeep.orchestella;

import android.os.Build;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

/**
 * One-shot, in-process hidden-API exemption. It must run before any AVF
 * platform class is loaded, so {@link OrchestellaApplication#attachBaseContext}
 * installs it at process start. The exemption grants no Android permission and
 * changes no system policy; a failure is recorded and never retried.
 */
public final class AvfHiddenApiExemption {
    public enum State {
        NOT_ATTEMPTED,
        INSTALLED,
        FAILED
    }

    private static State state = State.NOT_ATTEMPTED;

    private AvfHiddenApiExemption() {}

    /** Installs the real LSPosed exemption unless this process already tried. */
    public static synchronized State installOnce() {
        return installOnce(AvfHiddenApiExemption::installDefault);
    }

    /** Test seam: inject the installer without touching ART. */
    static synchronized State installOnce(Runnable installer) {
        if (state != State.NOT_ATTEMPTED) {
            return state;
        }
        try {
            installer.run();
            state = State.INSTALLED;
        } catch (RuntimeException | LinkageError failure) {
            state = State.FAILED;
        }
        return state;
    }

    public static synchronized State state() {
        return state;
    }

    static synchronized void resetForTest() {
        state = State.NOT_ATTEMPTED;
    }

    private static void installDefault() {
        if (Build.VERSION.SDK_INT < 28) {
            // AVF itself starts at API 33; on older releases the exemption is
            // irrelevant, so record an honest failure instead of calling an
            // API the library does not support there.
            throw new IllegalStateException("hidden API exemption requires API 28+");
        }
        boolean installed = HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/system/",
                "Landroid/os/",
                "Landroid/view/",
                "Landroid/content/",
                "Landroid/permission/",
                "Llibcore/io/");
        if (!installed) {
            throw new IllegalStateException("hidden API exemption was rejected");
        }
    }
}
