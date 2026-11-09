package io.github.nyzeep.orchestella;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * B2 follow-up ②: the Android glue for the UML compile-worker face — the
 * foreground keep-alive service plus the Context-bound seams
 * ({@link #keepAlive}, {@link #environment}) the assembly's
 * {@code umlFace(...)} wiring slot consumes.
 *
 * <p><b>Why a foreground service is a hard prerequisite, not an optimization.</b>
 * The 2026-11-07 device round (§7.1.6) proved that with the screen dozing, the
 * app process and its UML child process are frozen together in the same cgroup
 * within minutes ({@code cgroup.freeze=1}, guest uptime stops) — a background
 * UML face cannot stay resident on these OEM skins. With the foreground service
 * and a wake lock held, the E18 window measured 60 minutes of zero-freeze
 * residency. The face therefore must not be wired without this keep-alive.
 *
 * <p>The service mirrors the research prototype's proven shape
 * ({@code research/uml-runner} {@code KeepAliveService}): it owns only the
 * foreground state and a minimal low-importance ongoing notification; the wake
 * lock is managed by the {@link #keepAlive(Context)} seam over the kernel's
 * lifetime, so the two mechanisms stay decoupled (the research prototype's
 * rationale: reclaiming the service must not entangle the wake-lock
 * semantics). {@code START_NOT_STICKY} keeps the notification honest — it
 * never claims a running kernel after the process died.
 */
public class UmlFaceKeepAliveService extends Service {

    public static final String CHANNEL_ID = "orchestella_uml_face_keepalive";
    public static final int NOTIFICATION_ID = 1002;

    /**
     * The honest-stop notice (C lane): posted when the wake lock session
     * exhausts its renewal budget — a separate id from the foreground
     * notification so the service's stop cannot retract it. The face is
     * stopped by that point; the notice says so instead of leaving a
     * residency notification that claims a running worker.
     */
    public static final int STOP_NOTICE_ID = 1003;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        // Minimal footprint: low importance, no sound, no banner — the face is
        // infrastructure, not a user interaction point.
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "UML compile face", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Orchestella")
                .setContentText("UML compile worker running")
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * The production {@link UmlFaceLauncher.KeepAlive}: raises the foreground
     * service and takes a PARTIAL_WAKE_LOCK for the kernel's lifetime, then
     * releases both. Required by the face wiring — a face without freeze
     * protection must not be wired (the device-round hard evidence above).
     *
     * <p><b>C lane: renewal.</b> The per-grant
     * {@link WakeLockPolicy#UML_GRANT_TIMEOUT_MS} ceiling bounds a leaked
     * lock; a compile session that outlives one grant is renewed by the
     * policy ({@link WakeLockPolicy#decideSession}: re-take below the
     * threshold remaining, at most {@link WakeLockPolicy#UML_MAX_RENEWALS}
     * renewals per session). Past the ceiling the session ends honestly —
     * lock released, service stopped, notice posted — never a lapsed lock
     * under a notification that still claims a running worker. The decision
     * logic is {@link WakeLockPolicy} (pure, JVM-tested); this glue only
     * executes the answer on the real lock.
     */
    public static UmlFaceLauncher.KeepAlive keepAlive(Context context) {
        Context appContext = context.getApplicationContext();
        return new UmlFaceLauncher.KeepAlive() {
            private final Object monitor = new Object();
            private PowerManager.WakeLock wakeLock;
            private ScheduledExecutorService renewalTicker;
            private long grantStartedMs = WakeLockPolicy.NOT_HELD;
            private int renewalsDone;
            private boolean stoppedHonestly;

            @Override
            public void acquire() {
                appContext.startForegroundService(
                        new Intent(appContext, UmlFaceKeepAliveService.class));
                synchronized (monitor) {
                    renewalsDone = 0;
                    stoppedHonestly = false;
                    reTakeGrantLocked(SystemClock.elapsedRealtime());
                    startRenewalTickerLocked();
                }
            }

            /** Caller holds {@code monitor}. */
            private void reTakeGrantLocked(long nowMs) {
                PowerManager power =
                        (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
                if (power == null) {
                    return;
                }
                if (wakeLock == null) {
                    wakeLock = power.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK, "Orchestella:uml-face");
                    wakeLock.setReferenceCounted(false);
                }
                if (wakeLock.isHeld()) {
                    // Renewal and post-expiry re-take both start from a clean
                    // grant (the guest-execution service's proven pattern), so
                    // the platform deadline tracks the latest acquire.
                    try {
                        wakeLock.release();
                    } catch (RuntimeException ignored) {
                        // The re-take must not be masked by a stale release.
                    }
                }
                wakeLock.acquire(WakeLockPolicy.UML_GRANT_TIMEOUT_MS);
                grantStartedMs = nowMs;
            }

            /** Caller holds {@code monitor}. */
            private void startRenewalTickerLocked() {
                if (renewalTicker != null) {
                    return;
                }
                renewalTicker = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "uml-face-wakelock-renewal");
                    thread.setDaemon(true);
                    return thread;
                });
                renewalTicker.scheduleWithFixedDelay(
                        this::tick,
                        WakeLockPolicy.UML_CHECK_INTERVAL_MS,
                        WakeLockPolicy.UML_CHECK_INTERVAL_MS,
                        TimeUnit.MILLISECONDS);
            }

            private void tick() {
                try {
                    WakeLockPolicy.UmlSessionDecision decision;
                    synchronized (monitor) {
                        if (renewalTicker == null) {
                            return;
                        }
                        long nowMs = SystemClock.elapsedRealtime();
                        long heldSinceMs =
                                wakeLock != null && wakeLock.isHeld()
                                        ? grantStartedMs
                                        : WakeLockPolicy.NOT_HELD;
                        decision = WakeLockPolicy.decideSession(
                                nowMs, heldSinceMs, renewalsDone);
                        if (decision == WakeLockPolicy.UmlSessionDecision.HOLD) {
                            return;
                        }
                        if (decision == WakeLockPolicy.UmlSessionDecision.RENEW) {
                            reTakeGrantLocked(nowMs);
                            renewalsDone++;
                            return;
                        }
                    }
                    // STOP: outside the monitor — the honest shutdown.
                    stopHonestly();
                } catch (RuntimeException tickFailure) {
                    // A failed tick must not kill the scheduled task: the
                    // schedule would silently stop renewing forever.
                }
            }

            private void stopHonestly() {
                synchronized (monitor) {
                    if (stoppedHonestly) {
                        return;
                    }
                    stoppedHonestly = true;
                    stopTickerLocked();
                    releaseGrantLocked();
                }
                postStopNotice();
                appContext.stopService(new Intent(appContext, UmlFaceKeepAliveService.class));
            }

            /** Caller holds {@code monitor}. */
            private void releaseGrantLocked() {
                grantStartedMs = WakeLockPolicy.NOT_HELD;
                if (wakeLock != null) {
                    try {
                        wakeLock.release();
                    } catch (RuntimeException ignored) {
                        // Release must not mask the face lifecycle result.
                    }
                    wakeLock = null;
                }
            }

            /** Caller holds {@code monitor}. */
            private void stopTickerLocked() {
                if (renewalTicker != null) {
                    renewalTicker.shutdownNow();
                    renewalTicker = null;
                }
            }

            private void postStopNotice() {
                NotificationManager manager =
                        (NotificationManager) appContext.getSystemService(
                                Context.NOTIFICATION_SERVICE);
                if (manager == null) {
                    return;
                }
                manager.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "UML compile face", NotificationManager.IMPORTANCE_LOW));
                Notification notice = new Notification.Builder(appContext, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_notify_sync)
                        .setContentTitle("Orchestella")
                        .setContentText("UML compile worker stopped: residency ceiling reached")
                        .setAutoCancel(true)
                        .build();
                manager.notify(STOP_NOTICE_ID, notice);
            }

            @Override
            public void release() {
                synchronized (monitor) {
                    stopTickerLocked();
                    releaseGrantLocked();
                }
                appContext.stopService(new Intent(appContext, UmlFaceKeepAliveService.class));
            }
        };
    }

    /**
     * The production {@link UmlFaceLauncher.Environment}: the app's own
     * native library dir (where the kernel images unpack) and files dir
     * (rootfs parent, TMPDIR, working dir).
     */
    public static UmlFaceLauncher.Environment environment(Context context) {
        Context appContext = context.getApplicationContext();
        return new UmlFaceLauncher.Environment() {
            @Override
            public File nativeLibraryDir() {
                return new File(appContext.getApplicationInfo().nativeLibraryDir);
            }

            @Override
            public File filesDir() {
                return appContext.getFilesDir();
            }
        };
    }
}
