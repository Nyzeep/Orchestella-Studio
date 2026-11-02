package io.github.nyzeep.orchestella;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Minimal {@code POST_NOTIFICATIONS} prompt for the persistent foreground service.
 *
 * <p>Without the grant the service still runs, but its notification is invisible, so
 * the user cannot see or stop a running instance from the shade. The prompt is
 * requested once at the existing pre-launch site; everything else stays unchanged.
 *
 * <p>Must be called from the main thread.
 */
public final class NotificationPermissionPrompt {
    private static final int REQUEST_CODE = 0x0F51;

    private NotificationPermissionPrompt() {
    }

    /** No-op on API < 33 and when the permission is already granted. */
    public static void requestIfNeeded(Activity activity) {
        if (activity == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        activity.requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_CODE);
    }
}
