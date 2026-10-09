package io.github.nyzeep.orchestella;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import io.github.nyzeep.orchestella.runtime.contract.BridgeControl;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Android adapter for guest-initiated Bridge notification commands.
 * Dispatches notifications to the system notification bar with an explicit
 * notification channel and click-to-open pending intent.
 */
public final class AndroidNotificationAdapter implements BridgeControl.Notification {
    public static final String CHANNEL_ID = "orchestella_guest_notifications";
    public static final String CHANNEL_NAME = "Orchestella Guest Notifications";
    public static final String CHANNEL_DESCRIPTION = "Notifications from Orchestella guest instances";

    /** Strategy-neutral seam for actual notification display. */
    public interface NotificationPoster {
        boolean post(int notificationId, String title, String message);
    }

    private final NotificationPoster poster;
    private final AtomicInteger notificationCounter = new AtomicInteger(1000);

    /** Constructs an adapter connected to the Android system NotificationManager. */
    public AndroidNotificationAdapter(Context context) {
        Objects.requireNonNull(context, "context");
        final Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        ensureNotificationChannel(appContext);
        this.poster = (id, title, message) -> postSystemNotification(appContext, id, title, message);
    }

    /** Constructor allowing a testable poster seam. */
    public AndroidNotificationAdapter(NotificationPoster poster) {
        this.poster = Objects.requireNonNull(poster, "poster");
    }

    @Override
    public boolean notify(String title, String message) {
        if (title == null || title.trim().isEmpty() || message == null || message.trim().isEmpty()) {
            return false;
        }
        int id = notificationCounter.incrementAndGet();
        try {
            return poster.post(id, title.trim(), message.trim());
        } catch (Exception e) {
            return false;
        }
    }

    private static void ensureNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription(CHANNEL_DESCRIPTION);
                manager.createNotificationChannel(channel);
            }
        }
    }

    private static boolean postSystemNotification(
            Context context, int notificationId, String title, String message) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return false;
        }
        Intent clickIntent = new Intent(context, MainActivity.class);
        clickIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                notificationId,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context);
        }

        builder.setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(contentIntent);

        try {
            manager.notify(notificationId, builder.build());
            return true;
        } catch (SecurityException securityException) {
            return false;
        }
    }
}
