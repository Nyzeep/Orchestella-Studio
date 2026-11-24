package io.github.nyzeep.orchestella;

import android.app.Application;
import android.content.Context;

/**
 * Process entry point. The hidden-API exemption is installed in
 * {@code attachBaseContext}, before any Activity, Service or AVF class load.
 *
 * <p>DW-02: the QEMU device-model probe (GL 真实链路因子) warms up here on a
 * background thread so the wizard / hardware-page GPU copy reads a real
 * verdict instead of a blocking exec — never on the main thread.
 */
public final class OrchestellaApplication extends Application {
    @Override
    public void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        AvfHiddenApiExemption.installOnce();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (BuildConfig.IS_ISOLATED_QA_BUILD) {
            UmlInstanceLauncher.configureProcessBudget(this);
        }
        GpuDeviceModelProbeOwner.warmUpAsync(this);
    }
}
