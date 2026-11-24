package io.github.nyzeep.orchestella;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import io.github.nyzeep.orchestella.runtime.capability.ExecutionStrategyAdmissionEvaluator;
import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * Bounded-timeout preflight probe for standalone UML launch readiness (W3-05 / R-05).
 *
 * <p>Deliberately decoupled from {@link UmlFaceStatusRegistry}: ride-along compiler face
 * state never substitutes for standalone UML launch capability.
 */
public final class StandaloneUmlPreflightProbe {
    public static final long DEFAULT_TIMEOUT_MS = 1_500L;
    private static final List<String> REQUIRED_LIBRARIES = List.of(
            "libuml_kernel.so",
            "libuml_stub.so",
            "libuml_net.so",
            "libuml_passt.so");

    @FunctionalInterface
    public interface Checks {
        ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight run() throws Exception;
    }

    private final Checks checks;
    private final long timeoutMs;
    private final LongSupplier monotonicNanos;

    public StandaloneUmlPreflightProbe(
            Checks checks, long timeoutMs, LongSupplier monotonicNanos) {
        this.checks = Objects.requireNonNull(checks, "checks");
        this.timeoutMs = Math.max(1L, timeoutMs);
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
    }

    /** Creates the production Android probe inspecting host page size, native libs, and rootfs seed. */
    public static StandaloneUmlPreflightProbe forAndroid(Context context) {
        Context appContext = Objects.requireNonNull(context, "context").getApplicationContext();
        return new StandaloneUmlPreflightProbe(
                () -> probeAndroid(appContext),
                DEFAULT_TIMEOUT_MS,
                android.os.SystemClock::elapsedRealtimeNanos);
    }

    /** Executes the preflight probe within {@code timeoutMs}; timeouts return {@code TIMED_OUT}. */
    public ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight probe() {
        long startNanos = monotonicNanos.getAsLong();
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "standalone-uml-preflight");
            thread.setDaemon(true);
            return thread;
        });
        Future<ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight> future =
                executor.submit((Callable<ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight>) checks::run);
        try {
            ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight result =
                    future.get(timeoutMs, TimeUnit.MILLISECONDS);
            long elapsedMs = elapsedMs(startNanos);
            if (result == null) {
                return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                        ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.INCOMPLETE,
                        -1,
                        elapsedMs,
                        "独立 UML 预检返回空结果");
            }
            return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                    result.status(),
                    result.hostPageSizeBytes(),
                    Math.max(elapsedMs, result.elapsedMs()),
                    result.detail());
        } catch (TimeoutException timeout) {
            future.cancel(true);
            return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                    ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.TIMED_OUT,
                    -1,
                    elapsedMs(startNanos),
                    "独立 UML 预检超时（>" + timeoutMs + "ms）");
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                    ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.INCOMPLETE,
                    -1,
                    elapsedMs(startNanos),
                    "独立 UML 预检被中断");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() != null ? failed.getCause() : failed;
            String msg = cause.getMessage() != null && !cause.getMessage().isBlank()
                    ? cause.getMessage()
                    : cause.getClass().getSimpleName();
            return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                    ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.INCOMPLETE,
                    -1,
                    elapsedMs(startNanos),
                    "独立 UML 预检失败：" + msg);
        } finally {
            executor.shutdownNow();
        }
    }

    private long elapsedMs(long startNanos) {
        return Math.max(0L, (monotonicNanos.getAsLong() - startNanos) / 1_000_000L);
    }

    private static ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight probeAndroid(
            Context appContext) throws java.io.IOException {
        int pageSize;
        try {
            long raw = Os.sysconf(OsConstants._SC_PAGESIZE);
            pageSize = raw > 0L && raw <= Integer.MAX_VALUE ? (int) raw : -1;
        } catch (Throwable failure) {
            return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                    ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.INCOMPLETE,
                    -1,
                    0L,
                    "无法读取宿主页大小：" + failure.getClass().getSimpleName());
        }
        if (pageSize != 4096 && pageSize != 16384) {
            return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                    ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.UNSUPPORTED_HOST_PAGE_SIZE,
                    pageSize,
                    0L,
                    "不支持的宿主页大小：" + pageSize + " B");
        }
        String nativeDir = appContext.getApplicationInfo().nativeLibraryDir;
        if (nativeDir == null || nativeDir.isBlank()) {
            return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                    ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.ASSETS_MISSING,
                    pageSize,
                    0L,
                    "缺少 nativeLibraryDir");
        }
        File libDir = new File(nativeDir);
        for (String lib : REQUIRED_LIBRARIES) {
            File target = new File(libDir, lib);
            if (!target.isFile() || target.length() <= 0L) {
                return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                        ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.ASSETS_MISSING,
                        pageSize,
                        0L,
                        "缺少独立 UML 原生载荷：" + lib);
            }
        }
        boolean hasEmbeddedRootfs = false;
        try (InputStream in = PackagedAssetStreams.openResource(
                appContext, UmlKernelAssets.FastProfile.ROOTFS_TAR)) {
            hasEmbeddedRootfs = in != null;
        } catch (Exception ignored) {
            hasEmbeddedRootfs = false;
        }
        if (!hasEmbeddedRootfs) {
            UmlRootfsProvisioner.ReadinessReport staged = UmlRootfsProvisioner.readiness(
                    UmlKernelAssets.RootfsDownload.Entry.alpineMinirootfsAarch64(),
                    appContext.getFilesDir().toPath(),
                    BuildConfig.UML_ROOTFS_DOWNLOAD_ENABLED);
            if (!staged.ready()) {
                return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                        ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.ASSETS_MISSING,
                        pageSize,
                        0L,
                        "缺少 Alpine 3.22.2 minirootfs 载荷");
            }
        }
        return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.READY,
                pageSize,
                0L,
                "独立 UML 载荷与 " + (pageSize / 1024) + " KB 宿主页预检通过");
    }
}
