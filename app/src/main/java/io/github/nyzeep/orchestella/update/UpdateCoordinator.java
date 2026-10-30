package io.github.nyzeep.orchestella.update;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import io.github.nyzeep.orchestella.GitHubUpdateChecker;
import io.github.nyzeep.orchestella.SettingsManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the update check off the main thread, at most once at a time, and only when due.
 *
 * <p>The check is a plain GitHub Releases read: no download, no install, no identifiers. A
 * failure is silent by design — the badge simply keeps its previous answer and the next launch
 * tries again. Nothing here may block or crash a screen.
 */
public final class UpdateCoordinator {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "update-check");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    private UpdateCoordinator() {}

    public static UpdateCenter center(Context context) {
        return new UpdateCenter(PrefsUpdateStateStore.from(context));
    }

    /** Automatic check: no-op unless the interval elapsed. {@code onFinished} always runs. */
    public static void checkInBackground(Context context, String installedVersion, Runnable onFinished) {
        UpdateCenter center = center(context);
        if (!center.shouldCheck(System.currentTimeMillis())) {
            return;
        }
        run(context, installedVersion, false, onFinished);
    }

    /** Manual check from the About screen: ignores the interval but still respects the setting. */
    public static void checkNow(Context context, String installedVersion, Runnable onFinished) {
        run(context, installedVersion, true, onFinished);
    }

    private static void run(Context context, String installedVersion, boolean force, Runnable onFinished) {
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        Context app = context.getApplicationContext();
        UpdateCenter center = center(app);
        WORKER.execute(() -> {
            try {
                GitHubUpdateChecker checker = new GitHubUpdateChecker(new SettingsManager(app));
                center.record(checker.checkForUpdate(installedVersion, force), System.currentTimeMillis());
            } catch (RuntimeException ignored) {
                // A probe must never take a screen down; the next attempt retries.
            } finally {
                IN_FLIGHT.set(false);
                if (onFinished != null) {
                    MAIN.post(onFinished);
                }
            }
        });
    }
}
