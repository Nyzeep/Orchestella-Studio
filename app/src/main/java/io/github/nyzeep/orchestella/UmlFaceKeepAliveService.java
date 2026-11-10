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
import android.util.Log;
import java.io.File;

/**
 * B2 follow-up ②: the Android glue for the UML compile-worker face — the
 * foreground keep-alive service plus the Context-bound seams
 * ({@link #keepAlive}, {@link #environment}) the assembly's
 * {@code umlFace(...)} wiring slot consumes. The grant lifecycle itself
 * (ticker, renewal budget, idempotence, honest stop) is
 * {@link UmlKeepAliveSession}; this service only owns the foreground state,
 * the channel and the seam adapters.
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
 * lock is managed by the {@link UmlKeepAliveSession} that
 * {@link #keepAlive(Context)} wires, over the kernel's lifetime, so the two
 * mechanisms stay decoupled (the research prototype's rationale: reclaiming
 * the service must not entangle the wake-lock semantics). {@code
 * START_NOT_STICKY} keeps the notification honest — it never claims a running
 * kernel after the process died.
 */
public class UmlFaceKeepAliveService extends Service {

    /** Diagnostics tag (the {@code Orchestella*} family: Bridge/Qemu/Accel/Uml). */
    private static final String TAG = "OrchestellaUml";

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
     * logic is {@link WakeLockPolicy} (pure, JVM-tested); the execution
     * order is {@link UmlKeepAliveSession} (JVM-tested against fakes); this
     * glue only adapts the Android surfaces both consume.
     */
    public static UmlFaceLauncher.KeepAlive keepAlive(Context context) {
        Context appContext = context.getApplicationContext();
        return new UmlKeepAliveSession(
                () -> SystemClock.elapsedRealtime(),
                wakeLocks(appContext),
                effects(appContext),
                new UmlKeepAliveSession.ScheduledTicker());
    }

    /**
     * The lock factory adapter: resolves {@code PowerManager} per creation
     * (answering {@code null} when it is unavailable — the session's
     * historical early-exit) and adapts the lock to the narrow
     * {@link UmlKeepAliveSession.UmlWakeLock} surface, reference counting off.
     */
    private static UmlKeepAliveSession.WakeLockFactory wakeLocks(Context appContext) {
        return () -> {
            PowerManager power =
                    (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            if (power == null) {
                return null;
            }
            PowerManager.WakeLock lock = power.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "Orchestella:uml-face");
            lock.setReferenceCounted(false);
            return new UmlKeepAliveSession.UmlWakeLock() {
                @Override
                public boolean isHeld() {
                    return lock.isHeld();
                }

                @Override
                public void acquire(long timeoutMs) {
                    lock.acquire(timeoutMs);
                }

                @Override
                public void release() {
                    lock.release();
                }
            };
        };
    }

    /**
     * The effects adapter: the raise/stop intents and the honest-stop notice.
     */
    private static UmlKeepAliveSession.Effects effects(Context appContext) {
        return new UmlKeepAliveSession.Effects() {
            @Override
            public void raiseForegroundService() {
                appContext.startForegroundService(
                        new Intent(appContext, UmlFaceKeepAliveService.class));
            }

            @Override
            public void postStopNotice() {
                NotificationManager manager =
                        (NotificationManager) appContext.getSystemService(
                                Context.NOTIFICATION_SERVICE);
                if (manager == null) {
                    return;
                }
                // The channel already exists by construction: acquire() raises
                // this service (onCreate creates the channel) before any
                // session tick can run, so the historical re-create here was
                // redundant. Same id, name and importance — no visible change.
                Notification notice = new Notification.Builder(appContext, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_notify_sync)
                        .setContentTitle("Orchestella")
                        .setContentText("UML compile worker stopped: residency ceiling reached")
                        .setAutoCancel(true)
                        .build();
                manager.notify(STOP_NOTICE_ID, notice);
            }

            @Override
            public void stopForegroundService() {
                appContext.stopService(new Intent(appContext, UmlFaceKeepAliveService.class));
            }

            @Override
            public void reportTickFailure(RuntimeException failure) {
                // One warning line, class name only (the TerminalActivity
                // ready-wait lesson: a silent catch leaves only the symptom).
                Log.w(TAG, "uml keep-alive tick failed: "
                        + failure.getClass().getSimpleName());
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
