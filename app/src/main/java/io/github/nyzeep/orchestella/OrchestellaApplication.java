package io.github.nyzeep.orchestella;

import android.app.Application;
import android.content.Context;

/**
 * Process entry point. The hidden-API exemption is installed in
 * {@code attachBaseContext}, before any Activity, Service or AVF class load.
 */
public final class OrchestellaApplication extends Application {
    @Override
    public void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        AvfHiddenApiExemption.installOnce();
    }
}
