package io.github.nyzeep.orchestella;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
    public static final String EXTRA_SESSION_GENERATION = "extra_session_generation";

    /**
     * Per-grant wake lock ceiling. This is a project policy, not a platform limit:
     * no Android version mandates 2h. The shortest observed device window that must
     * stay awake (a long provisioning transaction) is well under it, and the ceiling
     * bounds a leaked lock if the process dies without releasing.
     */
    private static final long WAKE_LOCK_TIMEOUT_MS = 2L * 60 * 60 * 1000;
    /** Below one hour left, extend the grant instead of waiting for it to lapse. */
    private static final long WAKE_LOCK_RENEW_THRESHOLD_MS = 60L * 60 * 1000;

    public record LeaseSnapshot(
            int activeCount,
            List<String> instanceIds,
            Map<String, String> generationByInstance,
            boolean wakeLockHeld,
            boolean serviceRunning) {
        static LeaseSnapshot empty() {
            return new LeaseSnapshot(
                    0, Collections.emptyList(), Collections.emptyMap(), false, false);
        }
    }

    private static volatile LeaseSnapshot lastSnapshot = LeaseSnapshot.empty();

    public static LeaseSnapshot leaseSnapshot() {
        return lastSnapshot;
    }

    private PowerManager.WakeLock wakeLock;
    private long wakeLockAcquiredAtMs = WakeLockPolicy.NOT_HELD;
    private String currentInstanceId;
    private final ForegroundSessionBinding binding = new ForegroundSessionBinding();

    public static void start(
            Context context,
            String instanceId,
            String distroTitle,
            String performanceBadge,
            BatteryPolicy policy) {
        start(context, instanceId, distroTitle, performanceBadge, policy, null);
    }

    public static void start(Context context, String instanceId, String distroTitle,
            String performanceBadge, BatteryPolicy policy, String generation) {
        Intent intent = new Intent(context, OrchestellaForegroundService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_SESSION_GENERATION, generation);
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
        stop(context, null, null);
    }

    public static void stop(Context context, String instanceId, String generation) {
        Intent intent = new Intent(context, OrchestellaForegroundService.class);
        intent.setAction(ACTION_STOP);
        intent.putExtra(EXTRA_INSTANCE_ID, instanceId);
        intent.putExtra(EXTRA_SESSION_GENERATION, generation);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            if (!binding.isActive()) stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            ForegroundSessionBinding.StopOutcome outcome = binding.stopLease(
                    intent.getStringExtra(EXTRA_INSTANCE_ID),
                    intent.getStringExtra(EXTRA_SESSION_GENERATION));
            if (outcome == ForegroundSessionBinding.StopOutcome.LAST_LEASE_RELEASED) {
                stopRunning();
            } else if (outcome == ForegroundSessionBinding.StopOutcome.LEASE_RELEASED_OTHERS_ACTIVE) {
                if (!binding.hasAnyNonNullGenerationLease()) {
                    releaseWakeLock();
                }
                ForegroundSessionBinding.Lease primary = binding.primaryLease();
                currentInstanceId = primary != null ? primary.instanceId() : null;
                Notification refreshed = buildLeaseNotification(binding, primary);
                startForegroundNotification(refreshed);
                publishSnapshot(true);
            } else if (!binding.isActive()) {
                // A cold or duplicate STOP must not create a service with no work.
                stopSelfResult(startId);
                publishSnapshot(false);
            }
            return binding.isActive() ? START_STICKY : START_NOT_STICKY;
        }

        if (ACTION_START.equals(intent.getAction())) {
            String requestedId = intent.getStringExtra(EXTRA_INSTANCE_ID);
            String generation = intent.getStringExtra(EXTRA_SESSION_GENERATION);
            if (generation != null && (!InstanceLaunchGuard.isLatest(requestedId, generation)
                    || (!generation.equals(InstanceLaunchGuard.generation(requestedId))
                        && !generation.equals(ExecutionSessionRegistry.generation(requestedId,
                                ExecutionSessionRegistry.get(requestedId)))))) {
                if (!binding.isActive()) {
                    stopSelfResult(startId);
                    publishSnapshot(false);
                }
                return binding.isActive() ? START_STICKY : START_NOT_STICKY;
            }
            if (generation == null && binding.isActive()) return START_STICKY;
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
            binding.start(requestedId, generation, distroTitle, badge, policy);
            ForegroundSessionBinding.Lease primary = binding.primaryLease();
            currentInstanceId = primary != null ? primary.instanceId() : requestedId;

            // Pin session pool in memory alongside the foreground service
            PersistentTerminalSessionPool.getInstance();

            // A start without a session generation is the boot cold-recovery path: the
            // notification is raised so the user sees the auto-start, but no guest runs
            // yet, so holding a wake lock would only drain the battery. The lock is
            // claimed by the first real launch (generation != null).
            boolean hadHeldWakeLock = wakeLock != null && wakeLock.isHeld();
            boolean holdWakeLock = binding.hasAnyNonNullGenerationLease();
            Notification notification = buildLeaseNotification(binding, primary);
            boolean foregroundRaised = false;
            try {
                if (holdWakeLock) acquireWakeLock();
                startForegroundNotification(notification);
                foregroundRaised = true;
                publishSnapshot(true);
            } finally {
                // Roll back only the failed start's lease; preserve active sessions' wake lock.
                if (!foregroundRaised) {
                    binding.stopLease(requestedId, generation);
                    ForegroundSessionBinding.Lease remainingPrimary = binding.primaryLease();
                    currentInstanceId = remainingPrimary != null ? remainingPrimary.instanceId() : null;
                    if (holdWakeLock && !hadHeldWakeLock && !binding.hasAnyNonNullGenerationLease()) {
                        releaseWakeLock();
                    }
                    publishSnapshot(binding.activeCount() > 0);
                }
            }
            return START_STICKY;
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        binding.clear();
        releaseWakeLock();
        currentInstanceId = null;
        publishSnapshot(false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void stopRunning() {
        currentInstanceId = null;
        releaseWakeLock();
        publishSnapshot(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    private void publishSnapshot(boolean running) {
        lastSnapshot = new LeaseSnapshot(
                binding.activeCount(),
                binding.activeInstanceIds(),
                binding.generationByInstance(),
                wakeLock != null && wakeLock.isHeld(),
                running);
    }

    private Notification buildLeaseNotification(
            ForegroundSessionBinding leases, ForegroundSessionBinding.Lease primary) {
        if (primary == null) {
            return buildPersistentNotification("Linux", "完整 Linux", BatteryPolicy.BALANCED, null);
        }
        int activeCount = leases.activeCount();
        if (activeCount <= 1) {
            return buildPersistentNotification(
                    primary.distroTitle(),
                    primary.performanceBadge(),
                    primary.batteryPolicy(),
                    primary.instanceId());
        }
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle(activeCount + " 个 Linux 实例正在运行")
                .setContentText("状态: RUNNING · " + primary.distroTitle() + " · " + primary.performanceBadge())
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void startForegroundNotification(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Explicit type: the manifest declaration alone is not a contract once the
            // manifest is merged or overridden; a mismatch throws
            // MissingForegroundServiceTypeException on API 34+.
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "orchestella:runtime_wakelock");
                wakeLock.setReferenceCounted(false);
            }
        }
        if (wakeLock == null) return;
        long nowMs = SystemClock.elapsedRealtime();
        long heldSinceMs = wakeLock.isHeld() ? wakeLockAcquiredAtMs : WakeLockPolicy.NOT_HELD;
        WakeLockPolicy.Decision decision = WakeLockPolicy.decide(
                nowMs, heldSinceMs, WAKE_LOCK_TIMEOUT_MS, WAKE_LOCK_RENEW_THRESHOLD_MS);
        if (decision == WakeLockPolicy.Decision.NOOP) return;
        if (wakeLock.isHeld()) {
            // Renewal and post-expiry re-acquire both start from a clean grant, so the
            // platform deadline tracks the latest acquire rather than the first one.
            wakeLock.release();
        }
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        wakeLockAcquiredAtMs = nowMs;
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLockAcquiredAtMs = WakeLockPolicy.NOT_HELD;
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
