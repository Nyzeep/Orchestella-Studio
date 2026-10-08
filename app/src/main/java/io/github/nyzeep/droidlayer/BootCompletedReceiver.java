package io.github.nyzeep.droidlayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import io.github.nyzeep.droidlayer.runtime.instance.InstanceLifecycle;
import java.io.File;

/**
 * Handles device boot completion (ADR-0011 §4, US-35, AC-3).
 *
 * <p>Strictly disabled by default. If enabled by user, cold-restores the last
 * active instance.
 */
public class BootCompletedReceiver extends BroadcastReceiver {
    public static final String ACTION_BOOT_COMPLETED = Intent.ACTION_BOOT_COMPLETED;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        if (!ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        SettingsManager settings = new SettingsManager(context);
        if (!settings.isBootAutoStartEnabled()) {
            return; // strictly disabled by default; 0 background consumption
        }

        String lastId = settings.getLastActiveInstanceId();
        if (lastId == null || lastId.isBlank()) {
            return;
        }

        try {
            File instancesDir = new File(context.getFilesDir(), "instances");
            InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(instancesDir.toPath());
            InstanceLifecycle.Instance instance = store.load(lastId);
            if (instance != null) {
                // Instance found; launch foreground service notification notifying user of auto-start
                String distroName = instance.distro().id();
                String displayName = distroName.substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                        + distroName.substring(1) + " " + instance.distro().version();
                OrchestellaForegroundService.start(
                        context,
                        instance.id(),
                        displayName,
                        "开机自启动就绪",
                        settings.getBatteryPolicy());
            }
        } catch (Exception ignored) {
            // Cold restore failure upon reboot is non-fatal
        }
    }
}
