package io.github.nyzeep.orchestella;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import io.github.nyzeep.orchestella.runtime.capability.ExecutionStrategyAdmissionEvaluator;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlDistroCapabilityCatalog;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlKernelPayloadContract;
import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
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
    public static final long FULL_PROFILE_TIMEOUT_MS = 5_000L;
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
        return forAndroid(context, UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64);
    }

    /** Creates a profile-scoped Android preflight probe (fast-up-arm64 or full-smp-arm64). */
    public static StandaloneUmlPreflightProbe forAndroid(
            Context context, UmlDistroCapabilityCatalog.KernelProfile profile) {
        return forAndroid(context, profile, null);
    }

    /** Creates a profile- and distro-scoped Android preflight probe. */
    public static StandaloneUmlPreflightProbe forAndroid(
            Context context, UmlDistroCapabilityCatalog.KernelProfile profile, String distroId) {
        Context appContext = Objects.requireNonNull(context, "context").getApplicationContext();
        Objects.requireNonNull(profile, "profile");
        long timeoutMs = profile == UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64
                ? FULL_PROFILE_TIMEOUT_MS
                : DEFAULT_TIMEOUT_MS;
        return new StandaloneUmlPreflightProbe(
                () -> probeAndroid(appContext, profile, distroId),
                timeoutMs,
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
            Context appContext, UmlDistroCapabilityCatalog.KernelProfile profile, String distroId)
            throws java.io.IOException {
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
        if (profile == UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64) {
            UmlKernelPayloadContract.ProfileDescriptor desc =
                    UmlKernelPayloadContract.descriptorFor(profile);
            Map<String, String> manifestDigests = readAssetManifest(appContext, desc.manifestAssetName());
            String normalizedDistro = distroId != null
                    ? distroId.trim().toLowerCase(java.util.Locale.ROOT)
                    : "";
            boolean hasRootfsSeed;
            if ("debian".equals(normalizedDistro)) {
                hasRootfsSeed = hasStagedDebianRootfs(appContext);
            } else if ("alpine".equals(normalizedDistro)) {
                hasRootfsSeed = hasBundledOrStagedAlpineRootfs(appContext);
            } else {
                hasRootfsSeed =
                        hasBundledOrStagedAlpineRootfs(appContext) || hasStagedDebianRootfs(appContext);
            }
            ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight profileCheck =
                    evaluateProfilePreflight(
                            profile,
                            pageSize,
                            libDir,
                            manifestDigests,
                            UmlKernelAssets.FullProfile.packagedPins(),
                            hasRootfsSeed);
            if (profileCheck.status() != ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.READY) {
                return profileCheck;
            }
            for (String netLib : List.of("libuml_net.so", "libuml_passt.so")) {
                File target = new File(libDir, netLib);
                if (!target.isFile() || target.length() <= 0L || !target.canExecute()) {
                    return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                            ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.ASSETS_MISSING,
                            pageSize,
                            0L,
                            "[" + UmlKernelPayloadContract.FailureCode.MISSING_PAYLOAD + "] 缺少网络辅助载荷：" + netLib);
                }
            }
            return profileCheck;
        }
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
        if (!hasBundledOrStagedAlpineRootfs(appContext)) {
            return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                    ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.ASSETS_MISSING,
                    pageSize,
                    0L,
                    "缺少 Alpine 3.22.2 minirootfs 载荷");
        }
        return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.READY,
                pageSize,
                0L,
                "独立 UML 载荷与 " + (pageSize / 1024) + " KB 宿主页预检通过");
    }

    private static boolean hasBundledOrStagedAlpineRootfs(Context appContext)
            throws java.io.IOException {
        try (InputStream in = PackagedAssetStreams.openResource(
                appContext, UmlKernelAssets.FastProfile.ROOTFS_TAR)) {
            if (in != null) {
                return true;
            }
        } catch (Exception ignored) {
            // Fall through to staged rootfs cache check.
        }
        UmlRootfsProvisioner.ReadinessReport staged = UmlRootfsProvisioner.readiness(
                UmlKernelAssets.RootfsDownload.Entry.alpineMinirootfsAarch64(),
                appContext.getFilesDir().toPath(),
                BuildConfig.UML_ROOTFS_DOWNLOAD_ENABLED);
        return staged.ready();
    }

    static boolean hasStagedDebianRootfs(Context appContext) {
        UmlDistroCapabilityCatalog.DistroGateVerdict verdict =
                UmlDistroCapabilityCatalog.evaluate(
                        "debian", UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64);
        String fileName = verdict.expectedFileName();
        String expectedSha = verdict.expectedSha256();
        if (fileName == null || fileName.isBlank() || expectedSha == null || expectedSha.isBlank()) {
            return false;
        }
        try (InputStream in = PackagedAssetStreams.openResource(appContext, fileName)) {
            if (in != null) {
                return true;
            }
        } catch (Exception ignored) {
            // Fall through to staged rootfs directories check.
        }
        try {
            return UmlKernelAssets.probeStagedDistroSeed(
                            appContext.getFilesDir().toPath(), verdict)
                    .status()
                    == UmlKernelAssets.StagedSeedStatus.VERIFIED;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Pure-JVM distro + profile preflight check returning explicit failure codes without fallback. */
    public static ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight evaluateDistroProfilePreflight(
            UmlDistroCapabilityCatalog.KernelProfile profile,
            String distroId,
            int hostPageSize,
            File nativeLibDir,
            Map<String, String> manifestDigests,
            Map<String, String> expectedPins,
            boolean hasRootfsSeed) {
        Objects.requireNonNull(profile, "profile");
        try {
            UmlDistroCapabilityCatalog.requireCreatable(distroId, profile);
        } catch (UmlDistroCapabilityCatalog.DistroNotCreatableException refused) {
            return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                    ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.ASSETS_MISSING,
                    hostPageSize,
                    0L,
                    "[" + UmlKernelPayloadContract.FailureCode.PROFILE_MISMATCH + "] "
                            + refused.getMessage());
        }
        return evaluateProfilePreflight(
                profile, hostPageSize, nativeLibDir, manifestDigests, expectedPins, hasRootfsSeed);
    }

    /** Pure-JVM profile preflight check returning explicit failure codes without fallback. */
    public static ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight evaluateProfilePreflight(
            UmlDistroCapabilityCatalog.KernelProfile profile,
            int hostPageSize,
            File nativeLibDir,
            Map<String, String> manifestDigests,
            Map<String, String> expectedPins,
            boolean hasRootfsSeed) {
        Objects.requireNonNull(profile, "profile");
        if (hostPageSize != 4096 && hostPageSize != 16384) {
            return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                    ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.UNSUPPORTED_HOST_PAGE_SIZE,
                    hostPageSize,
                    0L,
                    "不支持的宿主页大小：" + hostPageSize + " B");
        }
        UmlKernelAssets.ProfileVerificationResult result = UmlKernelAssets.verifyProfilePayload(
                profile, nativeLibDir, manifestDigests, expectedPins);
        if (!result.ok()) {
            return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                    ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.ASSETS_MISSING,
                    hostPageSize,
                    0L,
                    "[" + result.failureCode() + "] " + result.detail());
        }
        if (!hasRootfsSeed) {
            return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                    ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.ASSETS_MISSING,
                    hostPageSize,
                    0L,
                    "[" + UmlKernelPayloadContract.FailureCode.MISSING_PAYLOAD + "] 缺少目标根盘种子");
        }
        UmlKernelPayloadContract.ProfileDescriptor desc =
                UmlKernelPayloadContract.descriptorFor(profile);
        return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.READY,
                hostPageSize,
                0L,
                "独立 UML " + desc.profileId() + " 载荷与 " + (hostPageSize / 1024) + " KB 宿主页预检通过");
    }

    private static Map<String, String> readAssetManifest(Context context, String assetName) {
        try (InputStream in = context.getAssets().open(assetName)) {
            return UmlKernelAssets.readManifest(in);
        } catch (Exception ignored) {
            return Map.of();
        }
    }
}
