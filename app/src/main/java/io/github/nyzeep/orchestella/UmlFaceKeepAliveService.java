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
import java.io.File;

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
     * Per-grant wake lock ceiling — the same project policy as the persistent
     * guest-execution service, not a platform limit: it bounds a leaked lock
     * if the process dies without releasing. A residency window that outlives
     * it needs the renewal path (see {@link WakeLockPolicy}) — a device-window
     * follow-up, recorded in the ticket report.
     */
    private static final long WAKE_LOCK_TIMEOUT_MS = 2L * 60 * 60 * 1000;

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
     */
    public static UmlFaceLauncher.KeepAlive keepAlive(Context context) {
        Context appContext = context.getApplicationContext();
        return new UmlFaceLauncher.KeepAlive() {
            private PowerManager.WakeLock wakeLock;

            @Override
            public void acquire() {
                appContext.startForegroundService(
                        new Intent(appContext, UmlFaceKeepAliveService.class));
                PowerManager power =
                        (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock lock = power.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "Orchestella:uml-face");
                lock.setReferenceCounted(false);
                // acquire(timeout): the ceiling bounds a leaked lock; the
                // face's release path is the normal exit.
                lock.acquire(WAKE_LOCK_TIMEOUT_MS);
                wakeLock = lock;
            }

            @Override
            public void release() {
                if (wakeLock != null) {
                    try {
                        wakeLock.release();
                    } catch (RuntimeException ignored) {
                        // Release must not mask the face lifecycle result.
                    }
                    wakeLock = null;
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
