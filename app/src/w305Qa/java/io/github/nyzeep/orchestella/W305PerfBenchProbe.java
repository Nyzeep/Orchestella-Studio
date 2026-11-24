package io.github.nyzeep.orchestella;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlInstanceBudget;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlRootReservationRegistry;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlVolumeInspector;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig;
import io.github.nyzeep.orchestella.runtime.network.LoopbackEndpointRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Isolated QA paired UML vs QEMU launch benchmark probe for W3-05 (R-05).
 *
 * <p>Drives both {@link UmlInstanceLauncher} and {@link QemuInstanceLauncher} against the exact
 * same frozen workload (Alpine 3.22.2 minirootfs, 1 vCPU, 512 MB RAM, 2 GiB sparse raw volume,
 * GPU off, offline mode) and measures host {@code T0} ({@link LaunchTimeline.Stage#LAUNCH_START})
 * to first complete shell prompt ({@link LaunchTimeline.Stage#SHELL_READY}) plus
 * {@link LaunchTimeline.Stage#BRIDGE_READY}. Strictly separates {@code first_provision},
 * {@code cold_provisioned}, {@code warm_restart}, and {@code warm_reconnect}.
 */
public final class W305PerfBenchProbe extends Instrumentation {
    private static final String DISTRO_ID = "alpine";
    private static final String DISTRO_VERSION = "3.22.2";
    private static final String ROOTFS_TAR = UmlKernelAssets.FastProfile.ROOTFS_TAR;
    private static final String ALPINE_SHA256 =
            UmlKernelAssets.FastProfile.pins().get(ROOTFS_TAR);
    private static final long ALPINE_SIZE_BYTES =
            UmlKernelAssets.FastProfile.ROOTFS_TAR_SIZE_BYTES;
    private static final String ALPINE_URL =
            "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/" + ROOTFS_TAR;

    private static final DistroManifest ALPINE_MANIFEST = new DistroManifest(
            DISTRO_ID,
            DISTRO_VERSION,
            "aarch64",
            ALPINE_URL,
            ROOTFS_TAR,
            ALPINE_SHA256,
            ALPINE_SIZE_BYTES);

    private static final long PROMPT_TIMEOUT_MS = 90_000L;
    private static final long FIRST_PROVISION_PROMPT_TIMEOUT_MS = 180_000L;
    private static final long BRIDGE_TIMEOUT_MS = 30_000L;
    private static final long STOP_GRACE_MS = 4_000L;

    private String stage = "init";
    private String action = "prepare";
    private String engine = "uml";
    private int pairIndex = 1;
    private int startPair = 1;
    private int count = 1;
    private String order = "uml_first";
    private boolean warmup = true;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null) {
            action = arguments.getString("action", "prepare");
            engine = arguments.getString("engine", "uml");
            pairIndex = parsePositiveInt(arguments.getString("pair_index", "1"), 1);
            startPair = parsePositiveInt(arguments.getString("start_pair", "1"), 1);
            count = parsePositiveInt(arguments.getString("count", "1"), 1);
            order = arguments.getString("order", pairIndex % 2 == 1 ? "uml_first" : "qemu_first");
            warmup = !"false".equalsIgnoreCase(arguments.getString("warmup", "true"));
        }
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            Context target = getTargetContext();
            require(
                    BuildConfig.IS_ISOLATED_QA_BUILD && target.getPackageName().endsWith(".qa.w305"),
                    "w305-build-guard");
            UmlInstanceLauncher.configureProcessBudget(target);
            switch (action) {
                case "prepare" -> runPrepare(target, result);
                case "first_provision" -> runFirstProvision(target, result);
                case "cold_provisioned" -> runColdProvisioned(target, result);
                case "warm_restart_batch" -> runWarmRestartBatch(target, result);
                case "warm_restart_pair" -> runWarmRestartPair(target, result);
                case "warm_reconnect_pair" -> runWarmReconnectPair(target, result);
                default -> throw new IllegalArgumentException("unknown action: " + action);
            }
            result.putString("w305_result", "PASS");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("w305_result", "FAIL");
            result.putString("w305_stage", stage);
            result.putString("w305_error", failure.getClass().getSimpleName());
            String detail = sanitizeDetail(failure.getMessage());
            if (detail != null) {
                result.putString("w305_detail", detail);
            }
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void reportStage(String name) {
        stage = name;
        Bundle status = new Bundle();
        status.putString("w305_stage", name);
        sendStatus(0, status);
    }

    private void emitSampleJson(SampleResult sample) {
        Bundle status = new Bundle();
        status.putString("w305_sample_json", sample.toJson());
        sendStatus(0, status);
    }

    private static VmHardwareConfig frozenHardwareConfig() {
        return VmHardwareConfig.builder()
                .cpuCores(1)
                .ramMegabytes(512)
                .diskCapacityBytes(VmHardwareConfig.MIN_DISK_CAPACITY_BYTES)
                .gpuAccelerationEnabled(false)
                .build();
    }

    private static Path benchRoot(Context target) {
        return target.getFilesDir().toPath().resolve("w305-bench");
    }

    private void runPrepare(Context target, Bundle result) throws Exception {
        reportStage("prepare-clean");
        Path root = benchRoot(target);
        deleteTree(root);
        Path instancesDir = Files.createDirectories(root.resolve("instances"));
        Path rootsDir = Files.createDirectories(root.resolve("roots"));
        Path distroInputsDir = Files.createDirectories(root.resolve("distro-inputs"));
        Files.createDirectories(root.resolve("runtime"));

        reportStage("prepare-stage-alpine");
        Path stagedTar = distroInputsDir.resolve(ROOTFS_TAR);
        String actualSha = stageAlpineRootfs(target, stagedTar);
        require(ALPINE_SHA256.equals(actualSha), "alpine-sha256-mismatch");

        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(instancesDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());

        reportStage("prepare-provision-uml");
        InstanceLifecycle.Instance umlInst = createBenchInstance(
                controller, rootsDir, "uml", "w305-uml-warm");
        SampleResult umlPrep = runSingleBoot(
                target, store, controller, distroInputsDir, root.resolve("runtime"),
                umlInst, "uml", "first_provision", 0, FIRST_PROVISION_PROMPT_TIMEOUT_MS, true);
        require("PASS".equals(umlPrep.result), "prepare-uml-failed:" + umlPrep.error + ":" + umlPrep.detail);
        require(
                UmlVolumeInspector.inspect(umlInst.rootVolume()).isExt4Ready(),
                "prepare-uml-not-ext4");
        require(
                store.load(umlInst.id()).isProvisionedFor(ALPINE_SHA256),
                "prepare-uml-evidence-missing");

        reportStage("prepare-provision-qemu");
        InstanceLifecycle.Instance qemuInst = createBenchInstance(
                controller, rootsDir, "qemu", "w305-qemu-warm");
        SampleResult qemuPrep = runSingleBoot(
                target, store, controller, distroInputsDir, root.resolve("runtime"),
                qemuInst, "qemu", "first_provision", 0, FIRST_PROVISION_PROMPT_TIMEOUT_MS, true);
        require("PASS".equals(qemuPrep.result), "prepare-qemu-failed:" + qemuPrep.error + ":" + qemuPrep.detail);
        // Ensure QEMU provisioning evidence is recorded on disk before cold/warm runs.
        awaitProvisioningEvidence(store, controller, qemuInst.id(), 3_000L);
        require(
                store.load(qemuInst.id()).isProvisionedFor(ALPINE_SHA256),
                "prepare-qemu-evidence-missing");

        Properties props = new Properties();
        props.setProperty("umlWarmId", umlInst.id());
        props.setProperty("qemuWarmId", qemuInst.id());
        props.setProperty("alpineSha256", actualSha);
        try (OutputStream out = Files.newOutputStream(root.resolve("fixture.properties"))) {
            props.store(out, "W3-05 paired benchmark fixture");
        }

        assertZeroState(umlInst.id(), umlInst.rootVolume());
        assertZeroState(qemuInst.id(), null);
        result.putString("w305_alpine_sha256", actualSha);
        result.putString("w305_uml_prepare_ms", Long.toString(umlPrep.t0ToShellReadyMs));
        result.putString("w305_qemu_prepare_ms", Long.toString(qemuPrep.t0ToShellReadyMs));
        result.putString("w305_stage", "prepare-done");
    }

    private void runFirstProvision(Context target, Bundle result) throws Exception {
        Fixture fixture = loadFixture(target);
        reconcileStale(fixture.controller);
        Path disposableRoots = Files.createDirectories(fixture.root.resolve("disposable-roots"));
        InstanceLifecycle.Instance inst = createBenchInstance(
                fixture.controller,
                disposableRoots,
                engine,
                "w305-fp-" + engine + "-" + pairIndex);
        try {
            SampleResult sample = runSingleBoot(
                    target,
                    fixture.store,
                    fixture.controller,
                    fixture.distroInputsDir,
                    fixture.runtimeDir,
                    inst,
                    engine,
                    "first_provision",
                    pairIndex,
                    FIRST_PROVISION_PROMPT_TIMEOUT_MS,
                    true);
            sample.writeToBundle(result);
            require("PASS".equals(sample.result), "first-provision-failed:" + sample.error + ":" + sample.detail);
            require(
                    "FIRST_PROVISION".equals(sample.credentialsOnlyReason),
                    "expected-first-provision-got-" + sample.credentialsOnlyReason);
        } finally {
            cleanupDisposableInstance(fixture.controller, fixture.runtimeDir, inst);
            assertZeroState(inst.id(), "uml".equals(engine) ? inst.rootVolume() : null);
        }
    }

    private void runColdProvisioned(Context target, Bundle result) throws Exception {
        Fixture fixture = loadFixture(target);
        reconcileStale(fixture.controller);
        InstanceLifecycle.Instance inst = fixture.instanceFor(engine);
        SampleResult sample = runSingleBoot(
                target,
                fixture.store,
                fixture.controller,
                fixture.distroInputsDir,
                fixture.runtimeDir,
                inst,
                engine,
                "cold_provisioned",
                pairIndex,
                PROMPT_TIMEOUT_MS,
                false);
        sample.writeToBundle(result);
        assertZeroState(inst.id(), "uml".equals(engine) ? inst.rootVolume() : null);
        require("PASS".equals(sample.result), "cold-provisioned-failed:" + sample.error + ":" + sample.detail);
        require(
                "HIT".equals(sample.credentialsOnlyReason),
                "expected-credentials-only-hit-got-" + sample.credentialsOnlyReason);
    }

    private void runWarmRestartBatch(Context target, Bundle result) throws Exception {
        Fixture fixture = loadFixture(target);
        reconcileStale(fixture.controller);
        if (warmup) {
            reportStage("warm-batch-warmup-uml");
            SampleResult wu = runSingleBoot(
                    target, fixture.store, fixture.controller, fixture.distroInputsDir, fixture.runtimeDir,
                    fixture.instanceFor("uml"), "uml", "warm_restart", 0, PROMPT_TIMEOUT_MS, false);
            require("PASS".equals(wu.result), "warmup-uml-failed:" + wu.error);
            reportStage("warm-batch-warmup-qemu");
            SampleResult wq = runSingleBoot(
                    target, fixture.store, fixture.controller, fixture.distroInputsDir, fixture.runtimeDir,
                    fixture.instanceFor("qemu"), "qemu", "warm_restart", 0, PROMPT_TIMEOUT_MS, false);
            require("PASS".equals(wq.result), "warmup-qemu-failed:" + wq.error);
        }
        int passed = 0;
        for (int i = 0; i < count; i++) {
            int pIdx = startPair + i;
            String[] pairOrder = (pIdx % 2 == 1) ? new String[] {"uml", "qemu"} : new String[] {"qemu", "uml"};
            for (String eng : pairOrder) {
                reportStage("warm-batch-pair-" + pIdx + "-" + eng);
                InstanceLifecycle.Instance inst = fixture.instanceFor(eng);
                SampleResult sample = runSingleBoot(
                        target,
                        fixture.store,
                        fixture.controller,
                        fixture.distroInputsDir,
                        fixture.runtimeDir,
                        inst,
                        eng,
                        "warm_restart",
                        pIdx,
                        PROMPT_TIMEOUT_MS,
                        false);
                if ("PASS".equals(sample.result) && !"HIT".equals(sample.credentialsOnlyReason)) {
                    sample = sample.withFailure("unexpected_credentials_only",
                            "expected HIT, got " + sample.credentialsOnlyReason);
                }
                emitSampleJson(sample);
                assertZeroState(inst.id(), "uml".equals(eng) ? inst.rootVolume() : null);
                if ("PASS".equals(sample.result)) {
                    passed++;
                }
            }
        }
        result.putInt("w305_batch_passed", passed);
        result.putInt("w305_batch_expected", count * 2);
        require(passed == count * 2, "warm-batch-partial-failure passed=" + passed);
    }

    private void runWarmRestartPair(Context target, Bundle result) throws Exception {
        Fixture fixture = loadFixture(target);
        reconcileStale(fixture.controller);
        String[] pairOrder = "qemu_first".equals(order)
                ? new String[] {"qemu", "uml"}
                : new String[] {"uml", "qemu"};
        if (warmup) {
            for (String eng : pairOrder) {
                SampleResult w = runSingleBoot(
                        target, fixture.store, fixture.controller, fixture.distroInputsDir, fixture.runtimeDir,
                        fixture.instanceFor(eng), eng, "warm_restart", 0, PROMPT_TIMEOUT_MS, false);
                require("PASS".equals(w.result), "warmup-" + eng + "-failed:" + w.error);
            }
        }
        for (String eng : pairOrder) {
            InstanceLifecycle.Instance inst = fixture.instanceFor(eng);
            SampleResult sample = runSingleBoot(
                    target, fixture.store, fixture.controller, fixture.distroInputsDir, fixture.runtimeDir,
                    inst, eng, "warm_restart", pairIndex, PROMPT_TIMEOUT_MS, false);
            if ("PASS".equals(sample.result) && !"HIT".equals(sample.credentialsOnlyReason)) {
                sample = sample.withFailure("unexpected_credentials_only",
                        "expected HIT, got " + sample.credentialsOnlyReason);
            }
            emitSampleJson(sample);
            assertZeroState(inst.id(), "uml".equals(eng) ? inst.rootVolume() : null);
            require("PASS".equals(sample.result), "warm-pair-" + eng + "-failed:" + sample.error);
        }
    }

    private void runWarmReconnectPair(Context target, Bundle result) throws Exception {
        Fixture fixture = loadFixture(target);
        reconcileStale(fixture.controller);
        SampleResult[] umlSamples = measureReconnectSeries(target, fixture, "uml", count);
        SampleResult[] qemuSamples = measureReconnectSeries(target, fixture, "qemu", count);
        int passed = 0;
        for (int i = 0; i < count; i++) {
            int pIdx = i + 1;
            SampleResult first = (pIdx % 2 == 1) ? umlSamples[i] : qemuSamples[i];
            SampleResult second = (pIdx % 2 == 1) ? qemuSamples[i] : umlSamples[i];
            emitSampleJson(first);
            emitSampleJson(second);
            if ("PASS".equals(first.result)) passed++;
            if ("PASS".equals(second.result)) passed++;
        }
        result.putInt("w305_reconnect_passed", passed);
        result.putInt("w305_reconnect_expected", count * 2);
        require(passed == count * 2, "warm-reconnect-partial-failure passed=" + passed);
    }

    private SampleResult[] measureReconnectSeries(
            Context target, Fixture fixture, String eng, int seriesCount) throws Exception {
        reportStage("warm-reconnect-boot-" + eng);
        InstanceLifecycle.Instance inst = fixture.instanceFor(eng);
        String gen = InstanceLaunchGuard.begin(inst.id());
        require(gen != null, "reconnect-launch-guard-busy");
        InstanceLaunchSequence.beforeLaunch(
                fixture.controller, inst, InstanceLifecycle.ConcurrencyPolicy.SINGLE_RUNNING_INSTANCE);
        ExecutionSession session = null;
        SampleResult[] samples = new SampleResult[seriesCount];
        try {
            session = launchEngine(
                    target, fixture.store, fixture.root.resolve("instances"),
                    fixture.distroInputsDir, fixture.runtimeDir, inst, eng);
            ExecutionSessionRegistry.LiveSession live = ExecutionSessionRegistry.getLiveSession(inst.id());
            require(live != null, "reconnect-missing-live-session");

            final ExecutionSession activeSession = session;
            AtomicReference<Throwable> bridgeError = new AtomicReference<>();
            CountDownLatch bridgeDone = new CountDownLatch(1);
            Thread bridgeThread = new Thread(() -> {
                try {
                    authenticateBridge(activeSession, inst.bridgeToken());
                } catch (Throwable err) {
                    bridgeError.set(err);
                } finally {
                    bridgeDone.countDown();
                }
            }, "w305-reconnect-bridge-" + eng);
            bridgeThread.setDaemon(true);
            bridgeThread.start();

            awaitPrompt(live, PROMPT_TIMEOUT_MS);
            verifyShellEcho(live, "W305_RECONNECT_WARMUP_" + eng, 15_000L);
            if (!bridgeDone.await(BRIDGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new IOException("reconnect-bridge-auth-timeout");
            }
            if (bridgeError.get() != null) {
                Throwable err = bridgeError.get();
                throw (err instanceof Exception e) ? e : new IOException("reconnect-bridge-auth-error", err);
            }

            for (int i = 0; i < seriesCount; i++) {
                int pIdx = i + 1;
                live.setOutputListener(null);
                long t0 = SystemClock.elapsedRealtimeNanos();
                CountDownLatch sawMarker = new CountDownLatch(1);
                String marker = "__W305_REATTACH_" + eng + "_" + pIdx + "__";
                StringBuilder seen = new StringBuilder();
                live.setOutputListener(chunk -> {
                    if (chunk == null) return;
                    synchronized (seen) {
                        seen.append(chunk);
                        if (seen.indexOf(marker) >= 0) {
                            sawMarker.countDown();
                        }
                    }
                });
                long reattachMs = Math.max(0L, (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L);
                live.sendBytes(("printf '%s%s\\n' '__W305_REATTACH_' '" + eng + "_" + pIdx + "__'\n")
                        .getBytes(StandardCharsets.UTF_8));
                boolean ok = sawMarker.await(15_000L, TimeUnit.MILLISECONDS);
                long roundtripMs = Math.max(0L, (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L);
                live.setOutputListener(null);
                if (!ok) {
                    samples[i] = SampleResult.failure(
                            "warm_reconnect", eng, pIdx, "reconnect_echo_timeout", "marker=" + marker);
                } else {
                    samples[i] = SampleResult.reconnectSuccess(eng, pIdx, reattachMs, roundtripMs);
                }
            }
        } finally {
            stopSessionCleanly(fixture.store, fixture.controller, inst, gen, session);
            assertZeroState(inst.id(), "uml".equals(eng) ? inst.rootVolume() : null);
        }
        return samples;
    }

    private SampleResult runSingleBoot(
            Context target,
            InstanceLifecycle.FileStore store,
            InstanceLifecycle.Controller controller,
            Path distroInputsDir,
            Path runtimeRoot,
            InstanceLifecycle.Instance inst,
            String eng,
            String bucketName,
            int pIdx,
            long promptTimeoutMs,
            boolean isFirstProvision) throws Exception {
        String gen = InstanceLaunchGuard.begin(inst.id());
        require(gen != null, "launch-guard-busy:" + inst.id());
        InstanceLaunchSequence.beforeLaunch(
                controller, inst, InstanceLifecycle.ConcurrencyPolicy.SINGLE_RUNNING_INSTANCE);
        ExecutionSession session = null;
        RuntimeProcessSupervisor.StopResult stopResult = null;
        try {
            reportStage(bucketName + "-" + eng + "-launch");
            session = launchEngine(
                    target, store, benchRoot(target).resolve("instances"),
                    distroInputsDir, runtimeRoot, inst, eng);
            ExecutionSessionRegistry.LiveSession live = ExecutionSessionRegistry.getLiveSession(inst.id());
            require(live != null, "missing-live-session");

            // Authenticate Bridge concurrently with prompt observation (matches TerminalActivity)
            // so SHELL_READY and BRIDGE_READY both record their true arrival times.
            final ExecutionSession activeSession = session;
            AtomicReference<Throwable> bridgeError = new AtomicReference<>();
            CountDownLatch bridgeDone = new CountDownLatch(1);
            Thread bridgeThread = new Thread(() -> {
                try {
                    authenticateBridge(activeSession, inst.bridgeToken());
                } catch (Throwable err) {
                    bridgeError.set(err);
                } finally {
                    bridgeDone.countDown();
                }
            }, "w305-bridge-" + eng + "-" + pIdx);
            bridgeThread.setDaemon(true);
            bridgeThread.start();

            reportStage(bucketName + "-" + eng + "-await-prompt");
            awaitPrompt(live, promptTimeoutMs);

            reportStage(bucketName + "-" + eng + "-echo");
            verifyShellEcho(live, "W305_OK_" + eng + "_" + pIdx, 20_000L);

            if (!bridgeDone.await(BRIDGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new IOException("bridge-auth-timeout");
            }
            if (bridgeError.get() != null) {
                Throwable err = bridgeError.get();
                throw (err instanceof Exception e) ? e : new IOException("bridge-auth-error", err);
            }

            if (isFirstProvision && "qemu".equals(eng)) {
                verifyQemuProvisionedMarker(live);
            }

            LaunchTimeline timeline = extractTimeline(session);
            require(timeline != null, "timeline-null");
            long shellReadyMs = timeline.elapsedMs(LaunchTimeline.Stage.SHELL_READY);
            long bridgeReadyMs = timeline.elapsedMs(LaunchTimeline.Stage.BRIDGE_READY);
            long assetsMs = timeline.elapsedMs(LaunchTimeline.Stage.ASSETS_VERIFIED);
            long engineStartedMs = "uml".equals(eng)
                    ? timeline.elapsedMs(LaunchTimeline.Stage.UML_STARTED)
                    : timeline.elapsedMs(LaunchTimeline.Stage.QEMU_STARTED);
            long registeredMs = timeline.elapsedMs(LaunchTimeline.Stage.SESSION_REGISTERED);
            LaunchTimeline.CredentialsOnlyOutcome credOutcome = timeline.credentialsOnlyOutcome();
            String credReason = credOutcome != null ? credOutcome.name() : null;

            reportStage(bucketName + "-" + eng + "-stop");
            stopResult = stopSessionCleanly(store, controller, inst, gen, session);
            session = null;
            gen = null;

            if (stopResult != RuntimeProcessSupervisor.StopResult.CONFIRMED
                    && stopResult != RuntimeProcessSupervisor.StopResult.ALREADY_STOPPED) {
                return SampleResult.failure(
                        bucketName, eng, pIdx, "stop_unconfirmed", "stop_result=" + stopResult);
            }
            if (shellReadyMs <= 0L) {
                return SampleResult.failure(
                        bucketName, eng, pIdx, "missing_shell_ready_stage", "shellReadyMs=" + shellReadyMs);
            }
            return new SampleResult(
                    bucketName,
                    eng,
                    pIdx,
                    pIdx > 0 ? (pIdx % 2 == 1 ? "uml_first" : "qemu_first") : "prepare",
                    "PASS",
                    shellReadyMs,
                    bridgeReadyMs >= 0L ? bridgeReadyMs : null,
                    assetsMs >= 0L ? assetsMs : null,
                    engineStartedMs >= 0L ? engineStartedMs : null,
                    registeredMs >= 0L ? registeredMs : null,
                    null,
                    null,
                    credReason,
                    stopResult.name(),
                    null,
                    "ok");
        } catch (Throwable failure) {
            String errCode = failure.getClass().getSimpleName();
            String msg = sanitizeDetail(failure.getMessage());
            boolean timedOut = msg != null && msg.toLowerCase(Locale.ROOT).contains("timeout");
            return new SampleResult(
                    bucketName,
                    eng,
                    pIdx,
                    pIdx > 0 ? (pIdx % 2 == 1 ? "uml_first" : "qemu_first") : "prepare",
                    timedOut ? "TIMEOUT" : "FAIL",
                    null, null, null, null, null, null, null,
                    null,
                    stopResult != null ? stopResult.name() : null,
                    errCode,
                    msg);
        } finally {
            if (session != null || gen != null) {
                stopSessionCleanly(store, controller, inst, gen, session);
            }
        }
    }

    private ExecutionSession launchEngine(
            Context target,
            InstanceLifecycle.FileStore store,
            Path instancesDir,
            Path distroInputsDir,
            Path runtimeRoot,
            InstanceLifecycle.Instance inst,
            String eng) throws Exception {
        Path instanceRuntimeDir = Files.createDirectories(runtimeRoot.resolve(inst.id()));
        InstanceStrategyLauncher.LaunchParameters params = new InstanceStrategyLauncher.LaunchParameters(
                inst.id(),
                inst.bridgeToken(),
                ROOTFS_TAR,
                inst.rootVolume().toString(),
                instanceRuntimeDir.toString(),
                distroInputsDir.toString(),
                inst.guestPassword(),
                inst.hardwareConfig(),
                null,
                inst.hostname(),
                inst.defaultUser(),
                inst.hasSudo());
        if ("uml".equals(eng)) {
            UmlInstanceLauncher umlLauncher = new UmlInstanceLauncher(target, store);
            return umlLauncher.launch(params);
        }
        if ("qemu".equals(eng)) {
            QemuInstanceLauncher qemuLauncher = new QemuInstanceLauncher(
                    target, List.of(ALPINE_MANIFEST), instancesDir);
            return qemuLauncher.launch(params);
        }
        throw new IllegalArgumentException("unsupported engine: " + eng);
    }

    private static LaunchTimeline extractTimeline(ExecutionSession session) {
        if (session instanceof UmlInstanceLauncher.Session umlSession) {
            return umlSession.launchTimeline();
        }
        if (session instanceof QemuInstanceLauncher.Session qemuSession) {
            return qemuSession.launchTimeline();
        }
        return null;
    }

    private static RuntimeProcessSupervisor.StopResult stopSessionCleanly(
            InstanceLifecycle.FileStore store,
            InstanceLifecycle.Controller controller,
            InstanceLifecycle.Instance inst,
            String gen,
            ExecutionSession session) {
        RuntimeProcessSupervisor.StopResult stopResult = RuntimeProcessSupervisor.StopResult.ALREADY_STOPPED;
        try {
            if (session != null) {
                if (session instanceof RuntimeOwnedSession owned) {
                    stopResult = owned.stopRuntime(STOP_GRACE_MS);
                } else {
                    try {
                        session.terminate();
                    } catch (IOException ignored) {
                    }
                    session.close();
                    stopResult = RuntimeProcessSupervisor.StopResult.CONFIRMED;
                }
                ExecutionSessionRegistry.unregister(inst.id(), session);
            }
        } finally {
            if (gen != null) {
                InstanceLaunchGuard.end(inst.id(), gen);
            } else {
                InstanceLaunchGuard.end(inst.id());
            }
            try {
                InstanceLifecycle.Instance current = store.load(inst.id());
                if (current.state() == InstanceLifecycle.State.RUNNING
                        || current.state() == InstanceLifecycle.State.SUSPENDED) {
                    controller.recordNormalGuestStop(inst.id());
                } else if (current.state() == InstanceLifecycle.State.CRASHED
                        || current.state() == InstanceLifecycle.State.INTERRUPTED) {
                    controller.resetToStopped(inst.id());
                }
            } catch (IOException ignored) {
            }
        }
        return stopResult;
    }

    private static void awaitPrompt(ExecutionSessionRegistry.LiveSession live, long timeoutMs)
            throws IOException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (live.holder().getScreenModel().hasObservedShellPrompt()) {
                return;
            }
            if (!live.isAlive()) {
                throw new IOException("session-exited-before-prompt");
            }
            SystemClock.sleep(5L);
        }
        throw new IOException("shell-prompt-timeout-" + timeoutMs + "ms");
    }

    private static void verifyShellEcho(
            ExecutionSessionRegistry.LiveSession live, String token, long timeoutMs) throws IOException {
        CountDownLatch latch = new CountDownLatch(1);
        String expected = "__" + token + "__";
        StringBuilder seen = new StringBuilder();
        live.setOutputListener(chunk -> {
            if (chunk == null) return;
            synchronized (seen) {
                seen.append(chunk);
                if (seen.indexOf(expected) >= 0) {
                    latch.countDown();
                }
            }
        });
        try {
            live.sendBytes(("printf '\\n%s%s%s\\n' '__' '" + token + "' '__'\n")
                    .getBytes(StandardCharsets.UTF_8));
            boolean ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!ok) {
                String transcript = live.getTranscript();
                if (transcript == null || !transcript.contains(expected)) {
                    throw new IOException("shell-echo-timeout");
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("shell-echo-interrupted", interrupted);
        } finally {
            live.setOutputListener(null);
        }
    }

    private static void verifyQemuProvisionedMarker(ExecutionSessionRegistry.LiveSession live)
            throws IOException {
        CountDownLatch latch = new CountDownLatch(1);
        String expected = "__QEMU_PROV_OK__";
        StringBuilder seen = new StringBuilder();
        live.setOutputListener(chunk -> {
            if (chunk == null) return;
            synchronized (seen) {
                seen.append(chunk);
                if (seen.indexOf(expected) >= 0) {
                    latch.countDown();
                }
            }
        });
        try {
            live.sendBytes(
                    ("grep -Fqx 'provisioning_version=1' /var/lib/droidlayer/provisioned-v1 && sync && printf '\\n%s%s\\n' '__QEMU_' 'PROV_OK__'\n")
                            .getBytes(StandardCharsets.UTF_8));
            if (!latch.await(15_000L, TimeUnit.MILLISECONDS)) {
                String transcript = live.getTranscript();
                if (transcript == null || !transcript.contains(expected)) {
                    throw new IOException("qemu-provisioned-v1-missing");
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("qemu-marker-interrupted", interrupted);
        } finally {
            live.setOutputListener(null);
        }
    }

    private static BridgeClient authenticateBridge(ExecutionSession session, String token)
            throws IOException {
        BridgeClient bridge = session.bridgeClient();
        require(bridge != null, "bridge-null");
        bridge.startResponsePump(request -> "ORCHESTELLA/ERROR unsupported-command\n");
        boolean authed = bridge.handshake(token).accepted();
        BridgeConnector.notifyAuthenticated(session.launchStageObserver(), authed);
        require(authed, "bridge-handshake-rejected");
        require(bridge.ping().accepted(), "bridge-ping-failed");
        return bridge;
    }

    private static void awaitProvisioningEvidence(
            InstanceLifecycle.FileStore store,
            InstanceLifecycle.Controller controller,
            String instanceId,
            long waitMs) throws IOException {
        long deadline = SystemClock.elapsedRealtime() + waitMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (store.load(instanceId).isProvisionedFor(ALPINE_SHA256)) {
                return;
            }
            SystemClock.sleep(50L);
        }
        // If QEMU guest-side provisioned-v1 marker was already verified, ensure the host record matches.
        if (!store.load(instanceId).isProvisionedFor(ALPINE_SHA256)) {
            controller.recordProvisioning(
                    instanceId,
                    new InstanceLifecycle.ProvisioningEvidence(1, ALPINE_SHA256, System.currentTimeMillis()));
        }
    }

    private static InstanceLifecycle.Instance createBenchInstance(
            InstanceLifecycle.Controller controller,
            Path rootsDir,
            String eng,
            String hostname) throws IOException {
        Files.createDirectories(rootsDir);
        Path volume = rootsDir.resolve(hostname + "-" + UUID.randomUUID() + ".raw");
        if ("qemu".equals(eng)) {
            writeQemuEmptyRootVolume(volume, VmHardwareConfig.MIN_DISK_CAPACITY_BYTES);
        } else {
            Files.createFile(volume);
        }
        ExecutionPolicyContract.InstanceExecutionIdentity identity = "uml".equals(eng)
                ? ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                        FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                        StorageContract.RootVolumeFormat.RAW_SPARSE)
                : ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                        StorageContract.RootVolumeFormat.RAW_SPARSE);
        InstanceLifecycle.Instance created = controller.create(
                new InstanceLifecycle.DistroReference(DISTRO_ID, DISTRO_VERSION),
                volume,
                frozenHardwareConfig(),
                new InstanceUserConfig(InstanceUserConfig.DEFAULT_ROOT_USER, "orchestellaqa", false, hostname),
                identity);
        controller.beginProvisioning(created.id());
        return controller.completeProvisioning(created.id());
    }

    private static void writeQemuEmptyRootVolume(Path volume, long sizeBytes) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(
                volume, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] sig = GuestRuntimeProvisioner.ROOT_VOLUME_CREATION_SIGNATURE
                    .getBytes(StandardCharsets.US_ASCII);
            channel.write(ByteBuffer.wrap(sig));
            channel.position(sizeBytes - 1L);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }
    }

    private static String stageAlpineRootfs(Context target, Path destination) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0L;
        byte[] buffer = new byte[65_536];
        try (InputStream in = PackagedAssetStreams.openResource(target, ROOTFS_TAR);
                OutputStream out = Files.newOutputStream(
                        destination, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) continue;
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                total += read;
            }
        }
        require(total == ALPINE_SIZE_BYTES, "alpine-size-mismatch:" + total);
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        return hex.toString();
    }

    private static void cleanupDisposableInstance(
            InstanceLifecycle.Controller controller, Path runtimeRoot, InstanceLifecycle.Instance inst) {
        try {
            controller.delete(inst.id());
        } catch (Exception ignored) {
        }
        try {
            Files.deleteIfExists(inst.rootVolume());
        } catch (IOException ignored) {
        }
        try {
            deleteTree(runtimeRoot.resolve(inst.id()));
        } catch (IOException ignored) {
        }
    }

    private static void assertZeroState(String instanceId, Path umlRootVolume) {
        require(ExecutionSessionRegistry.get(instanceId) == null, "session-leaked:" + instanceId);
        require(!InstanceLaunchGuard.isInFlight(instanceId), "launch-guard-leaked:" + instanceId);
        if (umlRootVolume != null) {
            require(
                    !UmlRootReservationRegistry.defaultRegistry().isReserved(umlRootVolume),
                    "uml-root-reservation-leaked");
        }
        require(UmlInstanceBudget.defaultBudget().committedStandaloneMb() == 0, "uml-budget-leaked");
        require(
                LoopbackEndpointRegistry.defaultRegistry().activeEndpointCount() == 0,
                "loopback-endpoint-leaked");
    }

    private static void reconcileStale(InstanceLifecycle.Controller controller) throws IOException {
        controller.reconcileAfterHostDeath(InstanceLifecycle.State.STOPPED);
    }

    private static Fixture loadFixture(Context target) throws IOException {
        Path root = benchRoot(target);
        Path propsPath = root.resolve("fixture.properties");
        require(Files.isRegularFile(propsPath), "missing-w305-prepare-fixture");
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(propsPath)) {
            props.load(in);
        }
        Path instancesDir = root.resolve("instances");
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(instancesDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        return new Fixture(
                root,
                store,
                controller,
                root.resolve("distro-inputs"),
                root.resolve("runtime"),
                props.getProperty("umlWarmId"),
                props.getProperty("qemuWarmId"));
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private static String sanitizeDetail(String detail) {
        if (detail == null) return null;
        String clean = detail.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() > 160) {
            clean = clean.substring(0, 160);
        }
        return clean.isEmpty() ? null : clean;
    }

    private static void require(boolean condition, String code) {
        if (!condition) {
            throw new AssertionError(code);
        }
    }

    private static int parsePositiveInt(String raw, int fallback) {
        try {
            int val = Integer.parseInt(raw);
            return val > 0 ? val : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private record Fixture(
            Path root,
            InstanceLifecycle.FileStore store,
            InstanceLifecycle.Controller controller,
            Path distroInputsDir,
            Path runtimeDir,
            String umlWarmId,
            String qemuWarmId) {
        InstanceLifecycle.Instance instanceFor(String eng) throws IOException {
            String id = "uml".equals(eng) ? umlWarmId : qemuWarmId;
            require(id != null && !id.isBlank(), "missing-warm-instance-id:" + eng);
            return store.load(id);
        }
    }

    private record SampleResult(
            String bucket,
            String engine,
            int pairIndex,
            String order,
            String result,
            Long t0ToShellReadyMs,
            Long t0ToBridgeReadyMs,
            Long t0ToAssetsMs,
            Long t0ToEngineStartedMs,
            Long t0ToSessionRegisteredMs,
            Long reattachMs,
            Long roundtripMs,
            String credentialsOnlyReason,
            String stopResult,
            String error,
            String detail) {

        static SampleResult failure(
                String bucket, String engine, int pairIndex, String error, String detail) {
            return new SampleResult(
                    bucket,
                    engine,
                    pairIndex,
                    pairIndex > 0 ? (pairIndex % 2 == 1 ? "uml_first" : "qemu_first") : "prepare",
                    "FAIL",
                    null, null, null, null, null, null, null,
                    null, null, error, sanitizeDetail(detail));
        }

        static SampleResult reconnectSuccess(
                String engine, int pairIndex, long reattachMs, long roundtripMs) {
            return new SampleResult(
                    "warm_reconnect",
                    engine,
                    pairIndex,
                    pairIndex % 2 == 1 ? "uml_first" : "qemu_first",
                    "PASS",
                    roundtripMs,
                    null, null, null, null,
                    reattachMs,
                    roundtripMs,
                    "HIT",
                    "CONFIRMED",
                    null,
                    "ok");
        }

        SampleResult withFailure(String errCode, String errDetail) {
            return new SampleResult(
                    bucket, engine, pairIndex, order, "FAIL",
                    t0ToShellReadyMs, t0ToBridgeReadyMs, t0ToAssetsMs, t0ToEngineStartedMs,
                    t0ToSessionRegisteredMs, reattachMs, roundtripMs, credentialsOnlyReason,
                    stopResult, errCode, sanitizeDetail(errDetail));
        }

        void writeToBundle(Bundle out) {
            out.putString("w305_bucket", bucket);
            out.putString("w305_engine", engine);
            out.putInt("w305_pair_index", pairIndex);
            out.putString("w305_order", order);
            out.putString("w305_result", result);
            if (t0ToShellReadyMs != null) out.putLong("w305_t0_to_shell_ready_ms", t0ToShellReadyMs);
            if (t0ToBridgeReadyMs != null) out.putLong("w305_t0_to_bridge_ready_ms", t0ToBridgeReadyMs);
            if (t0ToAssetsMs != null) out.putLong("w305_t0_to_assets_ms", t0ToAssetsMs);
            if (t0ToEngineStartedMs != null) out.putLong("w305_t0_to_engine_started_ms", t0ToEngineStartedMs);
            if (t0ToSessionRegisteredMs != null) {
                out.putLong("w305_t0_to_session_registered_ms", t0ToSessionRegisteredMs);
            }
            if (reattachMs != null) out.putLong("w305_reattach_ms", reattachMs);
            if (roundtripMs != null) out.putLong("w305_roundtrip_ms", roundtripMs);
            if (credentialsOnlyReason != null) out.putString("w305_credentials_only_reason", credentialsOnlyReason);
            if (stopResult != null) out.putString("w305_stop_result", stopResult);
            if (error != null) out.putString("w305_error", error);
            if (detail != null) out.putString("w305_detail", detail);
        }

        String toJson() {
            StringBuilder sb = new StringBuilder(256);
            sb.append('{');
            appendStr(sb, "bucket", bucket, true);
            appendStr(sb, "engine", engine, false);
            appendNum(sb, "pair_index", (long) pairIndex, false);
            appendStr(sb, "order", order, false);
            appendStr(sb, "result", result, false);
            appendNum(sb, "t0_to_shell_ready_ms", t0ToShellReadyMs, false);
            appendNum(sb, "t0_to_bridge_ready_ms", t0ToBridgeReadyMs, false);
            appendNum(sb, "t0_to_assets_ms", t0ToAssetsMs, false);
            appendNum(sb, "t0_to_engine_started_ms", t0ToEngineStartedMs, false);
            appendNum(sb, "t0_to_session_registered_ms", t0ToSessionRegisteredMs, false);
            appendNum(sb, "reattach_ms", reattachMs, false);
            appendNum(sb, "roundtrip_ms", roundtripMs, false);
            appendStr(sb, "credentials_only_reason", credentialsOnlyReason, false);
            appendStr(sb, "stop_result", stopResult, false);
            appendStr(sb, "error", error, false);
            appendStr(sb, "detail", detail, false);
            sb.append('}');
            return sb.toString();
        }

        private static void appendStr(StringBuilder sb, String key, String val, boolean first) {
            if (!first) sb.append(',');
            sb.append('"').append(key).append("\":");
            if (val == null) {
                sb.append("null");
            } else {
                sb.append('"').append(escapeJson(val)).append('"');
            }
        }

        private static void appendNum(StringBuilder sb, String key, Long val, boolean first) {
            if (!first) sb.append(',');
            sb.append('"').append(key).append("\":");
            sb.append(val == null ? "null" : val.toString());
        }

        private static String escapeJson(String text) {
            StringBuilder out = new StringBuilder(text.length() + 8);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            return out.toString();
        }
    }
}
