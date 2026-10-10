package io.github.nyzeep.orchestella;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Persistent foreground service for running Linux instances (ADR-0011, AC-2).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Presents persistent notification with running state, performance badge, and battery policy</li>
 *   <li>Holds PARTIAL_WAKE_LOCK across screen locks for ongoing compilation/server tasks</li>
 *   <li>Stops immediately upon guest exit to preserve battery</li>
 * </ul>
 */
public class OrchestellaForegroundService extends Service {
    public static final String CHANNEL_ID = "orchestella_runtime_persistent";
    public static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_START = "io.github.nyzeep.orchestella.action.START_PERSISTENT";
    public static final String ACTION_STOP = "io.github.nyzeep.orchestella.action.STOP_PERSISTENT";

    public static final String EXTRA_INSTANCE_ID = "extra_instance_id";
    public static final String EXTRA_DISTRO_TITLE = "extra_distro_title";
    public static final String EXTRA_PERFORMANCE_BADGE = "extra_performance_badge";
    public static final String EXTRA_BATTERY_POLICY = "extra_battery_policy";

    private PowerManager.WakeLock wakeLock;
    private String currentInstanceId;

    public static void start(
            Context context,
            String instanceId,
            String distroTitle,
            String performanceBadge,
            BatteryPolicy policy) {
        Intent intent = new Intent(context, OrchestellaForegroundService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_INSTANCE_ID, instanceId);
        intent.putExtra(EXTRA_DISTRO_TITLE, distroTitle);
        intent.putExtra(EXTRA_PERFORMANCE_BADGE, performanceBadge);
        intent.putExtra(EXTRA_BATTERY_POLICY, policy != null ? policy.name() : BatteryPolicy.BALANCED.name());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, OrchestellaForegroundService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopRunning();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(intent.getAction())) {
            currentInstanceId = intent.getStringExtra(EXTRA_INSTANCE_ID);
            String distroTitle = intent.getStringExtra(EXTRA_DISTRO_TITLE);
            if (distroTitle == null || distroTitle.isBlank()) {
                distroTitle = "Linux";
            }
            String badge = intent.getStringExtra(EXTRA_PERFORMANCE_BADGE);
            if (badge == null || badge.isBlank()) {
                badge = "完整 Linux";
            }
            String policyName = intent.getStringExtra(EXTRA_BATTERY_POLICY);
            BatteryPolicy policy = BatteryPolicy.BALANCED;
            if (policyName != null) {
                try {
                    policy = BatteryPolicy.valueOf(policyName);
                } catch (IllegalArgumentException ignored) {
                    policy = BatteryPolicy.BALANCED;
                }
            }

            // Pin session pool in memory alongside the foreground service
            PersistentTerminalSessionPool.getInstance();

            acquireWakeLock();
            Notification notification = buildPersistentNotification(distroTitle, badge, policy, currentInstanceId);
            startForeground(NOTIFICATION_ID, notification);
            return START_STICKY;
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        currentInstanceId = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void stopRunning() {
        currentInstanceId = null;
        releaseWakeLock();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "orchestella:runtime_wakelock");
                wakeLock.setReferenceCounted(false);
            }
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(12 * 60 * 60 * 1000L); // 12-hour safe upper timeout
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Orchestella 运行中实例状态",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("持续展示当前运行态 Linux 实例、执行徽章与电池策略");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildPersistentNotification(
            String distroTitle, String performanceBadge, BatteryPolicy policy, String instanceId) {
        Intent notificationIntent;
        if (instanceId != null && !instanceId.isEmpty()) {
            notificationIntent = new Intent(this, TerminalActivity.class);
            notificationIntent.putExtra("instanceId", instanceId);
        } else {
            notificationIntent = new Intent(this, MainActivity.class);
        }
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags);

        String title = distroTitle + " 实例正在运行";
        String content = "状态: RUNNING · " + performanceBadge + " · 策略: " + policy.displayName();

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
}
