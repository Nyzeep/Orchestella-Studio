package io.github.nyzeep.orchestella;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import io.github.nyzeep.orchestella.runtime.network.download.DownloadEngine;
import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingEngine;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.contract.Protocol;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlInstanceBudget;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlRootReservationRegistry;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlVolumeInspector;
import io.github.nyzeep.orchestella.runtime.instance.GatewaySlotArbiter;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig;
import io.github.nyzeep.orchestella.runtime.network.LoopbackEndpointRegistry;
import io.github.nyzeep.orchestella.runtime.network.PortForwardingManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Isolated QA device instrumentation for W3-04: multi-instance UML isolation, foreground
 * lease tracking, host memory budget enforcement, fault injection, and QEMU/ride-along
 * coexistence and regression verification.
 */
public final class W304MatrixProbe extends Instrumentation {
    private static final int GUEST_HTTP_A = 18_180;
    private static final int GUEST_HTTP_B = 18_181;
    private static final long DEFAULT_BG_HOLD_MS = 1_500L;

    private String stage = "preflight";
    private String action;
    private String bucket;
    private int cycleIndex;
    private long bgHoldMs = DEFAULT_BG_HOLD_MS;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null) {
            action = arguments.getString("action", "cycle");
            bucket = arguments.getString("bucket", "C1_COLD_BOOT");
            String idxRaw = arguments.getString("cycle_index", "1");
            cycleIndex = parsePositiveInt(idxRaw, 1);
            String bgRaw = arguments.getString("bg_hold_ms", Long.toString(DEFAULT_BG_HOLD_MS));
            bgHoldMs = Math.max(500L, parsePositiveLong(bgRaw, DEFAULT_BG_HOLD_MS));
        } else {
            action = "cycle";
            bucket = "C1_COLD_BOOT";
            cycleIndex = 1;
        }
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            Context target = getTargetContext();
            require(
                    BuildConfig.IS_ISOLATED_QA_BUILD && target.getPackageName().endsWith(".qa.w304"),
                    "w304-build-guard");
            UmlInstanceLauncher.configureProcessBudget(target);
            switch (action) {
                case "prepare" -> runPrepare(target, result);
                case "cycle" -> runCycle(target, result);
                case "qemu_regression" -> runQemuRegression(target, result);
                default -> throw new IllegalArgumentException("unknown action: " + action);
            }
            result.putString("w304_result", "PASS");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("w304_result", "FAIL");
            result.putString("w304_stage", stage);
            result.putString("w304_error", failure.getClass().getSimpleName());
            String detail = failure.getMessage();
            if (detail != null && detail.length() > 160) {
                detail = detail.substring(0, 160);
            }
            if (detail != null) {
                result.putString("w304_detail", detail);
            }
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void reportStage(String name) {
        stage = name;
        Bundle status = new Bundle();
        status.putString("w304_stage", name);
        status.putString("w304_bucket", bucket);
        status.putInt("w304_cycle", cycleIndex);
        sendStatus(0, status);
    }

    private void runPrepare(Context target, Bundle result) throws Exception {
        reportStage("prepare-clean");
        Path matrixDir = target.getFilesDir().toPath().resolve("w304-matrix");
        deleteTree(matrixDir);
        Path instancesDir = Files.createDirectories(matrixDir.resolve("instances"));
        Path rootsDir = Files.createDirectories(matrixDir.resolve("roots"));
        Files.createDirectories(matrixDir.resolve("runtime"));

        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(instancesDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        IsolatedQaExecutionEntry qaEntry = new IsolatedQaExecutionEntry(() -> true);
        InstanceLifecycle.Instance instA = qaEntry.createStandaloneUmlInstance(
                store, controller, rootsDir, "w304-inst-a", 512);
        InstanceLifecycle.Instance instB = qaEntry.createStandaloneUmlInstance(
                store, controller, rootsDir, "w304-inst-b", 512);

        UmlInstanceLauncher launcher = new UmlInstanceLauncher(target, store);
        reportStage("prepare-provision-a");
        String volAId = provisionAndMarkInstance(target, store, controller, launcher, instA, "w304-inst-a");
        reportStage("prepare-provision-b");
        String volBId = provisionAndMarkInstance(target, store, controller, launcher, instB, "w304-inst-b");
        require(!volAId.equals(volBId), "distinct-volume-id");

        reportStage("prepare-ridealong-rootfs");
        Path ridePrepBase = Files.createDirectories(matrixDir.resolve("ridealong-base"));
        DownloadEngine offlineDenyEngine = (mirrors, dest, sha, cb) -> {
            throw new IOException("offline-only");
        };
        UmlRootfsProvisioner.ProvisionReceipt rideReceipt =
                UmlRootfsProvisioner.provision(offlineDenyEngine, ridePrepBase);
        Path rideRoot = rideReceipt.extractedDir();
        require(rideRoot != null && Files.isDirectory(rideRoot), "ride-root-missing");
        String residentCmd = "#!/bin/sh\n"
                + "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n"
                + "printf 'U1_INFO kernel=%s ncpus=1\\n' \"$(uname -r 2>/dev/null || printf unknown)\"\n"
                + "printf 'U1_T2_READY uptime=0.00\\n'\n"
                + "printf 'U1_READY uptime=0.00\\n'\n"
                + "while :; do sleep 3600; done\n";
        Path cmdPath = rideRoot.resolve(UmlRootfsProvisioner.LAUNCHER_FILE_NAME);
        Files.write(cmdPath, residentCmd.getBytes(StandardCharsets.UTF_8));
        require(cmdPath.toFile().setExecutable(true, false), "ride-cmd-chmod");

        Properties markers = new Properties();
        markers.setProperty("instAId", instA.id());
        markers.setProperty("instBId", instB.id());
        markers.setProperty("volAId", volAId);
        markers.setProperty("volBId", volBId);
        markers.setProperty("rideRootDir", rideRoot.toAbsolutePath().toString());
        try (var out = Files.newOutputStream(matrixDir.resolve("expected-markers.properties"))) {
            markers.store(out, "W3-04 matrix fixture identifiers");
        }
        assertAllZeroState(target, instA.id(), instB.id(), instA.rootVolume(), instB.rootVolume());
        result.putString("w304_vol_a_id", volAId);
        result.putString("w304_vol_b_id", volBId);
        result.putString("w304_stage", "prepare-done");
    }

    private String provisionAndMarkInstance(
            Context target,
            InstanceLifecycle.FileStore store,
            InstanceLifecycle.Controller controller,
            UmlInstanceLauncher launcher,
            InstanceLifecycle.Instance instance,
            String tag)
            throws Exception {
        String gen = InstanceLaunchGuard.begin(instance.id());
        require(gen != null, "launch-guard-begin");
        InstanceLaunchSequence.beforeLaunch(
                controller, instance, InstanceLifecycle.ConcurrencyPolicy.ISOLATED_MULTI_USER_MODE_KERNEL);
        OrchestellaForegroundService.start(
                target,
                instance.id(),
                instance.distro().id() + " " + instance.distro().version(),
                "UML 隔离矩阵",
                BatteryPolicy.BALANCED,
                gen);
        UmlInstanceLauncher.Session session = null;
        try {
            session = (UmlInstanceLauncher.Session) launcher.launch(paramsFor(target, instance));
            Shell shell = new Shell(instance.id(), session);
            shell.awaitPrompt();
            authenticateBridge(session, instance.bridgeToken());
            String volId = shell.captureLine(
                    "sed -n 's/^volume_id=//p' /etc/orchestella/provision-marker | head -n 1");
            require(!volId.isBlank(), "missing-volume-id");
            shell.succeeds(
                    "mkdir -p /etc/orchestella && printf '%s\\n' '"
                            + tag
                            + "|"
                            + volId
                            + "' >/etc/orchestella/w304-instance-marker && sync",
                    15);
            return volId;
        } finally {
            stopAndRecordNormalExit(target, store, controller, instance, gen, session);
        }
    }

    private void runCycle(Context target, Bundle result) throws Exception {
        Fixture fixture = loadFixture(target);
        reconcileStaleInstances(fixture.controller);
        clearPersistedRules(target, fixture.instAId, fixture.instBId);
        switch (bucket) {
            case "C1_COLD_BOOT" -> runColdBoot(target, fixture, result);
            case "C2_WARM_RECONNECT" -> runWarmReconnect(target, fixture, result);
            case "C3_BG_KEEPALIVE" -> runBackgroundKeepAlive(target, fixture, result);
            case "C4_FAULT_CONTENTION" -> runFaultContention(target, fixture, result);
            case "C5_DUAL_AND_RIDEALONG" -> runDualAndRideAlong(target, fixture, result);
            default -> throw new IllegalArgumentException("unknown bucket: " + bucket);
        }
        assertAllZeroState(
                target,
                fixture.instAId,
                fixture.instBId,
                fixture.store.load(fixture.instAId).rootVolume(),
                fixture.store.load(fixture.instBId).rootVolume());
        result.putString("w304_bucket", bucket);
        result.putInt("w304_cycle", cycleIndex);
        result.putString("w304_vol_a_id", fixture.volAId);
        result.putString("w304_vol_b_id", fixture.volBId);
    }

    private void runColdBoot(Context target, Fixture fixture, Bundle result) throws Exception {
        reportStage("c1-start");
        LiveInstance liveA = startLiveInstance(target, fixture, fixture.instAId, false);
        try {
            verifyMarkerAndSuperblock(liveA, "w304-inst-a", fixture.volAId);
            startHttpServer(liveA.shell, GUEST_HTTP_A, "w304-inst-a");
            PortForwardingManager mgr = forwardingManager(target, fixture.instAId);
            PortForwardingPresenter presenter = PortForwardingPresenter.forActiveSession(fixture.instAId, mgr);
            require(presenter.hasLiveEngine(), "c1-no-live-engine");
            int hostPort = pickFreeUserPort(fixture.instAId);
            presenter.addRule(Integer.toString(hostPort), Integer.toString(GUEST_HTTP_A), Protocol.TCP, "c1");
            try {
                require(readHttp(hostPort).endsWith("w304-inst-a"), "c1-http-mismatch");
            } finally {
                String ruleId = mgr.listRules().get(0).id();
                presenter.deleteRule(ruleId);
            }
            require(!isBound(hostPort), "c1-port-not-released");
        } finally {
            liveA.stopAndClose(target, fixture);
        }
        result.putString("w304_summary", "cold_boot_marker_ext4_bridge_forward_ok");
    }

    private void runWarmReconnect(Context target, Fixture fixture, Bundle result) throws Exception {
        reportStage("c2-boot-1");
        int persistedHostPort = pickFreeUserPort(fixture.instAId);
        PortForwardingManager mgr = forwardingManager(target, fixture.instAId);
        LiveInstance boot1 = startLiveInstance(target, fixture, fixture.instAId, true);
        try {
            verifyMarkerAndSuperblock(boot1, "w304-inst-a", fixture.volAId);
            ExecutionSessionRegistry.LiveSession livePty = ExecutionSessionRegistry.getLiveSession(fixture.instAId);
            require(livePty != null, "c2-missing-live-session");
            final StringBuilder chunkLog = new StringBuilder();
            java.util.function.Consumer<String> listener1 = chunk -> {
                synchronized (chunkLog) {
                    chunkLog.append(chunk);
                }
            };
            livePty.setOutputListener(listener1);
            boot1.shell.succeeds("echo W304_C2_BEFORE", 10);
            livePty.setOutputListener(null);
            boot1.shell.succeeds("echo W304_C2_BG", 10);
            livePty.setOutputListener(chunk -> {});
            String transcript = livePty.getTranscript();
            require(transcript.contains("W304_C2_BEFORE") && transcript.contains("W304_C2_BG"), "c2-reattach-lost");
            require(boot1.session.resizeTerminalHost(96, 30), "c2-resize-failed");
            String cols = boot1.shell.captureLine("stty size | awk '{print $2}'");
            require("96".equals(cols.trim()), "c2-pty-cols-mismatch");
            livePty.setOutputListener(null);

            boot1.session.portForwardingEngine().refresh();
            startHttpServer(boot1.shell, GUEST_HTTP_A, "w304-inst-a");
            PortForwardingPresenter presenter1 = PortForwardingPresenter.forActiveSession(fixture.instAId, mgr);
            presenter1.addRule(
                    Integer.toString(persistedHostPort), Integer.toString(GUEST_HTTP_A), Protocol.TCP, "c2-persist");
            require(readHttp(persistedHostPort).endsWith("w304-inst-a"), "c2-boot1-http");
        } finally {
            boot1.stopAndClose(target, fixture);
        }
        require(!isBound(persistedHostPort), "c2-between-boots-bound");

        reportStage("c2-boot-2");
        LiveInstance boot2 = startLiveInstance(target, fixture, fixture.instAId, true);
        try {
            verifyMarkerAndSuperblock(boot2, "w304-inst-a", fixture.volAId);
            startHttpServer(boot2.shell, GUEST_HTTP_A, "w304-inst-a");
            PortForwardingPresenter presenter2 = PortForwardingPresenter.forActiveSession(fixture.instAId, mgr);
            require(presenter2.hasLiveEngine() && mgr.listRules().size() == 1, "c2-rule-not-restored");
            require(readHttp(persistedHostPort).endsWith("w304-inst-a"), "c2-boot2-http");
            presenter2.deleteRule(mgr.listRules().get(0).id());
        } finally {
            boot2.stopAndClose(target, fixture);
        }
        require(!isBound(persistedHostPort), "c2-after-stop-bound");
        result.putString("w304_summary", "warm_reattach_resize_rule_restore_ok");
    }

    private void runBackgroundKeepAlive(Context target, Fixture fixture, Bundle result) throws Exception {
        ensureAppForeground(target);
        reportStage("c3-start");
        LiveInstance liveA = startLiveInstance(target, fixture, fixture.instAId, true);
        try {
            awaitForegroundLease(1, true, 4_000L);
            verifyMarkerAndSuperblock(liveA, "w304-inst-a", fixture.volAId);
            startHttpServer(liveA.shell, GUEST_HTTP_A, "w304-inst-a");
            PortForwardingManager mgr = forwardingManager(target, fixture.instAId);
            PortForwardingPresenter presenter = PortForwardingPresenter.forActiveSession(fixture.instAId, mgr);
            int hostPort = pickFreeUserPort(fixture.instAId);
            presenter.addRule(Integer.toString(hostPort), Integer.toString(GUEST_HTTP_A), Protocol.TCP, "c3");

            long uptimeBeforeMs = readGuestUptimeMs(liveA.shell);
            reportStage("bg_hold_ready");
            SystemClock.sleep(bgHoldMs);
            reportStage("c3-bg-verify");
            OrchestellaForegroundService.LeaseSnapshot snap = OrchestellaForegroundService.leaseSnapshot();
            require(snap.activeCount() == 1 && snap.wakeLockHeld(), "c3-lease-lost-in-bg");
            long uptimeAfterMs = readGuestUptimeMs(liveA.shell);
            require(uptimeAfterMs > uptimeBeforeMs, "c3-guest-frozen");
            require(liveA.bridge.ping().accepted(), "c3-bridge-ping");
            require(readHttp(hostPort).endsWith("w304-inst-a"), "c3-http-bg");
            presenter.deleteRule(mgr.listRules().get(0).id());
        } finally {
            liveA.stopAndClose(target, fixture);
        }
        awaitForegroundLease(0, false, 4_000L);
        result.putString("w304_summary", "bg_keepalive_wakelock_uptime_progressed_ok");
    }

    private void runFaultContention(Context target, Fixture fixture, Bundle result) throws Exception {
        reportStage("c4-start-a");
        LiveInstance liveA = startLiveInstance(target, fixture, fixture.instAId, true);
        int portA = pickFreeUserPort(fixture.instAId);
        PortForwardingManager mgrA = forwardingManager(target, fixture.instAId);
        PortForwardingPresenter presA = PortForwardingPresenter.forActiveSession(fixture.instAId, mgrA);
        boolean killed = false;
        try {
            verifyMarkerAndSuperblock(liveA, "w304-inst-a", fixture.volAId);
            startHttpServer(liveA.shell, GUEST_HTTP_A, "w304-inst-a");
            presA.addRule(Integer.toString(portA), Integer.toString(GUEST_HTTP_A), Protocol.TCP, "c4");
            require(readHttp(portA).endsWith("w304-inst-a"), "c4-initial-http");

            // 1. Same-instance duplicate launch rejected without damaging liveA.
            reportStage("c4-dup-reject");
            Path altRoot = target.getFilesDir().toPath().resolve("w304-matrix/dup-alt.raw");
            Files.createFile(altRoot);
            try {
                InstanceLifecycle.Instance dup = fixture.store.load(fixture.instAId);
                InstanceStrategyLauncher.LaunchParameters dupParams = new InstanceStrategyLauncher.LaunchParameters(
                        dup.id(), dup.bridgeToken(), UmlInstanceLauncher.ALPINE_MINIROOTFS_RESOURCE,
                        altRoot.toString(),
                        target.getFilesDir().toPath().resolve("w304-matrix/runtime/dup").toString(),
                        target.getFilesDir().getAbsolutePath(),
                        dup.guestPassword(), dup.hardwareConfig());
                boolean refused = false;
                try {
                    fixture.launcher.launch(dupParams);
                } catch (UmlInstanceBudget.BudgetRefusalException expected) {
                    refused = expected.reason() == UmlInstanceBudget.RefusalReason.INSTANCE_ALREADY_ACTIVE;
                }
                require(refused, "c4-dup-not-refused");
                require(!UmlRootReservationRegistry.defaultRegistry().isReserved(altRoot), "c4-alt-root-leaked");
            } finally {
                Files.deleteIfExists(altRoot);
            }

            // 2. Budget ceiling exceeded rejects instB while keeping instA intact.
            reportStage("c4-budget-reject");
            try {
                UmlInstanceBudget.configureDefault(
                        () -> {
                            HostHardwareProfile p = HostHardwareDetector.detect(target);
                            return new UmlInstanceBudget.HostMemorySnapshot(p.totalRamMb(), p.availRamMb(), p.isLowMemory());
                        },
                        UmlFaceStatusRegistry::runningMemMb,
                        new UmlInstanceBudget.BudgetPolicy(2048, 512, 512));
                boolean budgetRefused = false;
                try {
                    InstanceLifecycle.Instance instB = fixture.store.load(fixture.instBId);
                    fixture.launcher.launch(paramsFor(target, instB));
                } catch (UmlInstanceBudget.BudgetRefusalException expected) {
                    budgetRefused = expected.reason() == UmlInstanceBudget.RefusalReason.TOTAL_RAM_BUDGET_EXCEEDED;
                }
                require(budgetRefused, "c4-budget-not-refused");
                require(!UmlRootReservationRegistry.defaultRegistry().isReserved(
                        fixture.store.load(fixture.instBId).rootVolume()), "c4-instb-root-leaked");
            } finally {
                UmlInstanceLauncher.configureProcessBudget(target);
            }

            // 3. Port ownership collision + reserved port refusal keep portA intact.
            reportStage("c4-port-conflict");
            boolean portConflict = false;
            try {
                LoopbackEndpointRegistry.defaultRegistry().acquire(
                        "127.0.0.1", portA, fixture.instBId, "gen-fake", "rule-fake");
            } catch (IOException expected) {
                portConflict = true;
            }
            require(portConflict, "c4-port-conflict-not-rejected");
            require(readHttp(portA).endsWith("w304-inst-a"), "c4-http-after-conflict");

            // 4. Stale generation late exit / foreground stop ignored.
            reportStage("c4-stale-gen");
            require(!ExecutionSessionRegistry.unregister(fixture.instAId, new DummyExecutionSession()),
                    "c4-stale-unregister-accepted");
            OrchestellaForegroundService.stop(target, fixture.instAId, "stale-gen-0");
            SystemClock.sleep(150L);
            require(ExecutionSessionRegistry.get(fixture.instAId) == liveA.session, "c4-session-stolen");
            require(UmlInstanceBudget.defaultBudget().hasActiveLease(fixture.instAId, liveA.generation),
                    "c4-budget-stolen");

            // 5. Abnormal SIGKILL stops process group (stub_exe children included), reaps cleanly, and preserves ext4 integrity.
            reportStage("c4-sigkill");
            int pid = (int) liveA.session.guestPid();
            require(pid > 0, "c4-invalid-pid");
            try {
                Os.kill(-pid, OsConstants.SIGKILL);
            } catch (ErrnoException pgFailure) {
                Process.sendSignal(pid, 9);
            }
            liveA.session.waitFor();
            RuntimeProcessSupervisor.StopResult stopRes = liveA.session.stopRuntime(3_000L);
            require(stopRes != RuntimeProcessSupervisor.StopResult.UNCONFIRMED, "c4-sigkill-unconfirmed");
            killed = true;
            ExecutionSessionRegistry.unregister(fixture.instAId, liveA.session);
            InstanceLaunchGuard.end(fixture.instAId, liveA.generation);
            OrchestellaForegroundService.stop(target, fixture.instAId, liveA.generation);
            fixture.controller.recordCrash(fixture.instAId, 137, null, "w304 sigkill injection");
            fixture.controller.resetToStopped(fixture.instAId);
            if (!mgrA.listRules().isEmpty()) {
                mgrA.removeRule(mgrA.listRules().get(0).id());
            }
        } finally {
            if (!killed) {
                liveA.stopAndClose(target, fixture);
            }
        }

        reportStage("c4-post-kill-reboot");
        LiveInstance rebooted = startLiveInstance(target, fixture, fixture.instAId, false);
        try {
            verifyMarkerAndSuperblock(rebooted, "w304-inst-a", fixture.volAId);
        } finally {
            rebooted.stopAndClose(target, fixture);
        }
        result.putString("w304_summary", "fault_dup_budget_port_stale_sigkill_recovery_ok");
    }

    private void runDualAndRideAlong(Context target, Fixture fixture, Bundle result) throws Exception {
        ensureAppForeground(target);
        reportStage("c5-arm-ridealong");
        AtomicReference<String> rideLogError = new AtomicReference<>();
        UmlFaceRideAlong rideAlong = buildRideAlong(target, fixture.rideRootDir, 30_000L, rideLogError);

        boolean rideArmed = false;
        LiveInstance liveA = null;
        LiveInstance liveB = null;
        int portA = pickFreeUserPort(fixture.instAId);
        int portB = pickFreeUserPort(fixture.instBId, portA);
        PortForwardingManager mgrA = forwardingManager(target, fixture.instAId);
        PortForwardingManager mgrB = forwardingManager(target, fixture.instBId);
        try {
            UmlFaceLauncher face = rideAlong.arm("qemu-host", true);
            require(face != null && UmlFaceStatusRegistry.runningWidth() > 0, "c5-ridealong-arm-failed");
            rideArmed = true;
            require(UmlFaceStatusRegistry.runningMemMb() == 512, "c5-ridealong-mem-mismatch");

            reportStage("c5-start-dual");
            liveA = startLiveInstance(target, fixture, fixture.instAId, true);
            liveB = startLiveInstance(target, fixture, fixture.instBId, true);
            awaitForegroundLease(2, true, 4_000L);

            require(UmlInstanceBudget.defaultBudget().committedStandaloneMb() == 1024, "c5-budget-not-1024");
            require(UmlInstanceBudget.defaultBudget().snapshot().rideAlongRamMb() == 512, "c5-ride-mem-0");

            verifyMarkerAndSuperblock(liveA, "w304-inst-a", fixture.volAId);
            verifyMarkerAndSuperblock(liveB, "w304-inst-b", fixture.volBId);

            startHttpServer(liveA.shell, GUEST_HTTP_A, "w304-inst-a");
            startHttpServer(liveB.shell, GUEST_HTTP_B, "w304-inst-b");
            PortForwardingPresenter presA = PortForwardingPresenter.forActiveSession(fixture.instAId, mgrA);
            PortForwardingPresenter presB = PortForwardingPresenter.forActiveSession(fixture.instBId, mgrB);
            presA.addRule(Integer.toString(portA), Integer.toString(GUEST_HTTP_A), Protocol.TCP, "c5-a");
            presB.addRule(Integer.toString(portB), Integer.toString(GUEST_HTTP_B), Protocol.TCP, "c5-b");
            require(readHttp(portA).endsWith("w304-inst-a"), "c5-http-a");
            require(readHttp(portB).endsWith("w304-inst-b"), "c5-http-b");

            // Background window with all 3 active.
            reportStage("bg_hold_ready");
            SystemClock.sleep(Math.min(bgHoldMs, 1_500L));
            require(OrchestellaForegroundService.leaseSnapshot().activeCount() == 2, "c5-bg-lease-count");
            require(UmlFaceStatusRegistry.runningWidth() > 0, "c5-bg-ridealong-died");
            require(liveA.bridge.ping().accepted() && liveB.bridge.ping().accepted(), "c5-bg-bridge");
            require(readHttp(portA).endsWith("w304-inst-a"), "c5-bg-http-a");
            require(readHttp(portB).endsWith("w304-inst-b"), "c5-bg-http-b");

            // Alternate stop/restart of instA while instB and ride-along remain live and unaffected.
            reportStage("c5-stop-restart-a");
            presA.deleteRule(mgrA.listRules().get(0).id());
            liveA.stopAndClose(target, fixture);
            liveA = null;
            awaitForegroundLease(1, true, 4_000L);
            require(UmlInstanceBudget.defaultBudget().committedStandaloneMb() == 512, "c5-budget-after-stop-a");
            require(readHttp(portB).endsWith("w304-inst-b"), "c5-b-http-during-a-stop");
            require(liveB.bridge.ping().accepted(), "c5-b-bridge-during-a-stop");
            require(UmlFaceStatusRegistry.runningWidth() > 0, "c5-ridealong-during-a-stop");

            liveA = startLiveInstance(target, fixture, fixture.instAId, true);
            awaitForegroundLease(2, true, 4_000L);
            verifyMarkerAndSuperblock(liveA, "w304-inst-a", fixture.volAId);
            startHttpServer(liveA.shell, GUEST_HTTP_A, "w304-inst-a");
            PortForwardingPresenter presA2 = PortForwardingPresenter.forActiveSession(fixture.instAId, mgrA);
            presA2.addRule(Integer.toString(portA), Integer.toString(GUEST_HTTP_A), Protocol.TCP, "c5-a2");
            require(readHttp(portA).endsWith("w304-inst-a"), "c5-http-a-restarted");
            require(readHttp(portB).endsWith("w304-inst-b"), "c5-http-b-still-ok");

            // Disarm ride-along independently while both standalone UML instances stay alive.
            rideAlong.disarm("qemu-host");
            rideArmed = false;
            require(UmlFaceStatusRegistry.current() == null, "c5-ridealong-not-cleared");
            require(UmlFaceStatusRegistry.runningMemMb() == 0, "c5-ridealong-mem-not-zero");
            require(readHttp(portA).endsWith("w304-inst-a") && readHttp(portB).endsWith("w304-inst-b"),
                    "c5-standalone-affected-by-ridealong-disarm");
            require(rideLogError.get() == null, "c5-ridealong-init-error");

            presA2.deleteRule(mgrA.listRules().get(0).id());
            presB.deleteRule(mgrB.listRules().get(0).id());
        } finally {
            if (liveA != null) {
                liveA.stopAndClose(target, fixture);
            }
            if (liveB != null) {
                liveB.stopAndClose(target, fixture);
            }
            if (rideArmed) {
                rideAlong.disarm("qemu-host");
            }
        }
        awaitForegroundLease(0, false, 4_000L);
        result.putString("w304_summary", "dual_standalone_plus_ridealong_coexistence_ok");
    }

    private void runQemuRegression(Context target, Bundle result) throws Exception {
        reportStage("qemu-gateway-regression");
        File workDir = new File(target.getFilesDir(), "w304-qemu-reg");
        NetworkGatewayDaemon.Running gateway = NetworkGatewayDaemon.start(target, "w304-qemu-reg", workDir);
        try {
            int hostPort = pickFreeUserPort("w304-qemu-reg");
            PortForwardingRule rule = PortForwardingRule.defaultRule("w304-q", Protocol.TCP, hostPort, 18090);
            PortForwardingEngine.Live engine = gateway.forwardingEngine();
            require(engine != null && engine.isRunning(), "qemu-gw-not-running");
            engine.addRule(rule);
            try {
                require(engine.isApplied(rule) && isBound(hostPort), "qemu-rule-not-bound");
            } finally {
                engine.removeRule(rule);
            }
            require(!isBound(hostPort), "qemu-rule-not-unbound");
        } finally {
            require(gateway.stop(2_000L), "qemu-gw-stop-failed");
        }

        reportStage("qemu-single-instance-policy-regression");
        Path qemuRegStore = Files.createDirectories(new File(target.getFilesDir(), "w304-qemu-reg-store").toPath());
        deleteTree(qemuRegStore);
        Files.createDirectories(qemuRegStore);
        try {
            InstanceLifecycle.FileStore regStore = new InstanceLifecycle.FileStore(qemuRegStore);
            InstanceLifecycle.Controller regCtrl = new InstanceLifecycle.Controller(regStore, Clock.systemUTC());
            InstanceLifecycle.Instance q1 = createQemuPolicyStub(regStore, regCtrl, qemuRegStore, "w304-qemu-1");
            InstanceLifecycle.Instance q2 = createQemuPolicyStub(regStore, regCtrl, qemuRegStore, "w304-qemu-2");
            regCtrl.start(q1.id());
            boolean q2Rejected = false;
            try {
                regCtrl.start(q2.id());
            } catch (InstanceLifecycle.SingleRunningInstanceException expected) {
                q2Rejected = true;
            }
            require(q2Rejected, "qemu-single-running-policy-broken");
            regCtrl.recordNormalGuestStop(q1.id());
        } finally {
            deleteTree(qemuRegStore);
        }

        reportStage("ridealong-regression");
        UmlFaceRideAlong rideAlong = buildRideAlong(target, null, 2_000L, null);
        boolean armed = false;
        try {
            UmlFaceLauncher face = rideAlong.arm("qemu-reg", true);
            require(face != null && UmlFaceStatusRegistry.runningWidth() > 0, "ridealong-reg-arm");
            armed = true;
        } finally {
            if (armed) {
                rideAlong.disarm("qemu-reg");
            }
        }
        require(UmlFaceStatusRegistry.current() == null && UmlFaceStatusRegistry.runningMemMb() == 0,
                "ridealong-reg-disarm");
        result.putString("w304_summary", "qemu_gateway_and_ridealong_regression_ok");
    }

    private static InstanceLifecycle.Instance createQemuPolicyStub(
            InstanceLifecycle.FileStore store,
            InstanceLifecycle.Controller controller,
            Path storeRoot,
            String hostname)
            throws IOException {
        Path rootVolume = storeRoot.resolve(hostname + ".raw");
        Files.createDirectories(rootVolume.getParent());
        Files.createFile(rootVolume);
        ExecutionPolicyContract.InstanceExecutionIdentity identity =
                ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                        StorageContract.RootVolumeFormat.RAW_SPARSE);
        InstanceLifecycle.Instance created = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.22.2"),
                rootVolume,
                VmHardwareConfig.defaultConfig(),
                InstanceUserConfig.defaultRoot(),
                identity);
        controller.beginProvisioning(created.id());
        controller.completeProvisioning(created.id());
        return store.load(created.id());
    }

    private UmlFaceRideAlong buildRideAlong(
            Context target, Path rideRoot, long holdMs, AtomicReference<String> errorSink) {
        UmlFaceConsoleSink sink = new UmlFaceConsoleSink(
                () -> {
                    UmlFaceStatusRegistry.BoundFace current = UmlFaceStatusRegistry.current();
                    return current != null ? current.getFace() : null;
                },
                line -> {
                    if (errorSink != null && line.contains("UM_INIT_ERROR")) {
                        errorSink.compareAndSet(null, line);
                    }
                    return kotlin.Unit.INSTANCE;
                },
                task -> {
                    Thread t = new Thread(task, "w304-face-reprobe");
                    t.setDaemon(true);
                    t.start();
                    return kotlin.Unit.INSTANCE;
                });
        UmlNetSupervisorSpawner rideSpawner = new UmlNetSupervisorSpawner(
                new ConsoleDrainingUmlSpawner(sink),
                BuildConfig.UML_COMPILE_BRIDGE,
                () -> new File(target.getApplicationInfo().nativeLibraryDir));
        UmlCompileBridgeWiring compileBridge = new UmlCompileBridgeWiring(
                BuildConfig.UML_COMPILE_BRIDGE,
                line -> kotlin.Unit.INSTANCE,
                PortForwardingRule.COMPILE_WORKER_HOST_PORT,
                PortForwardingRule.COMPILE_SUBMITTER_HOST_PORT);
        List<String> extraArgs = (rideRoot != null && Files.isDirectory(rideRoot))
                ? List.of("umroot=" + rideRoot.toAbsolutePath(), "umrootfstype=hostfs", "um.net=1")
                : List.of("um.hold_ms=" + holdMs);
        return new UmlFaceRideAlong(
                (id, gate, channel) -> {
                    UmlFaceLauncher.LaunchParams lp = new UmlFaceLauncher.LaunchParams(512, 0, extraArgs);
                    return channel != null
                            ? AcceleratorLaneAssembly.umlFace(
                                    gate,
                                    UmlFaceKeepAliveService.environment(target),
                                    lp,
                                    UmlFaceKeepAliveService.keepAlive(target),
                                    rideSpawner,
                                    channel)
                            : AcceleratorLaneAssembly.umlFace(
                                    gate,
                                    UmlFaceKeepAliveService.environment(target),
                                    lp,
                                    UmlFaceKeepAliveService.keepAlive(target),
                                    rideSpawner);
                },
                compileBridge,
                line -> kotlin.Unit.INSTANCE);
    }

    private LiveInstance startLiveInstance(
            Context target, Fixture fixture, String instanceId, boolean expectAlreadyProvisioned)
            throws Exception {
        InstanceLifecycle.Instance instance = fixture.store.load(instanceId);
        if (expectAlreadyProvisioned) {
            require(UmlVolumeInspector.inspect(instance.rootVolume()).isExt4Ready(), "not-ext4-ready");
        }
        String gen = InstanceLaunchGuard.begin(instanceId);
        require(gen != null, "guard-busy");
        InstanceLaunchSequence.beforeLaunch(
                fixture.controller, instance, InstanceLifecycle.ConcurrencyPolicy.ISOLATED_MULTI_USER_MODE_KERNEL);
        OrchestellaForegroundService.start(
                target,
                instanceId,
                instance.distro().id() + " " + instance.distro().version(),
                "UML 隔离矩阵",
                BatteryPolicy.BALANCED,
                gen);
        UmlInstanceLauncher.Session session = null;
        try {
            session = (UmlInstanceLauncher.Session) fixture.launcher.launch(paramsFor(target, instance));
            Shell shell = new Shell(instanceId, session);
            shell.awaitPrompt();
            BridgeClient bridge = authenticateBridge(session, instance.bridgeToken());
            return new LiveInstance(instance, gen, session, shell, bridge);
        } catch (Throwable failure) {
            stopAndRecordNormalExit(target, fixture.store, fixture.controller, instance, gen, session);
            throw failure;
        }
    }

    private static void verifyMarkerAndSuperblock(LiveInstance live, String expectedTag, String expectedVolId)
            throws Exception {
        require(UmlVolumeInspector.inspect(live.instance.rootVolume()).isExt4Ready(), "ext4-superblock-corrupt");
        String actualVolId = live.shell.captureLine(
                "sed -n 's/^volume_id=//p' /etc/orchestella/provision-marker | head -n 1");
        require(expectedVolId.equals(actualVolId.trim()), "volume-id-mismatch");
        String marker = live.shell.captureLine("cat /etc/orchestella/w304-instance-marker");
        require((expectedTag + "|" + expectedVolId).equals(marker.trim()), "instance-marker-mismatch");
    }

    private static void stopAndRecordNormalExit(
            Context target,
            InstanceLifecycle.FileStore store,
            InstanceLifecycle.Controller controller,
            InstanceLifecycle.Instance instance,
            String gen,
            UmlInstanceLauncher.Session session)
            throws IOException {
        try {
            if (session != null) {
                try {
                    session.terminate();
                } catch (IOException ignored) {
                    session.stopRuntime(3_000L);
                }
                ExecutionSessionRegistry.unregister(instance.id(), session);
            }
        } finally {
            InstanceLaunchGuard.end(instance.id(), gen);
            OrchestellaForegroundService.stop(target, instance.id(), gen);
            try {
                InstanceLifecycle.Instance current = store.load(instance.id());
                if (current.state() == InstanceLifecycle.State.RUNNING
                        || current.state() == InstanceLifecycle.State.SUSPENDED) {
                    controller.recordNormalGuestStop(instance.id());
                } else if (current.state() == InstanceLifecycle.State.CRASHED
                        || current.state() == InstanceLifecycle.State.INTERRUPTED) {
                    controller.resetToStopped(instance.id());
                }
            } catch (IOException ignored) {
            }
        }
    }

    private static void assertAllZeroState(
            Context target, String instAId, String instBId, Path volA, Path volB)
            throws IOException {
        require(ExecutionSessionRegistry.get(instAId) == null, "session-a-leaked");
        require(ExecutionSessionRegistry.get(instBId) == null, "session-b-leaked");
        require(!UmlRootReservationRegistry.defaultRegistry().isReserved(volA), "root-a-reserved");
        require(!UmlRootReservationRegistry.defaultRegistry().isReserved(volB), "root-b-reserved");
        require(UmlVolumeInspector.inspect(volA).isExt4Ready(), "root-a-bad-ext4");
        require(UmlVolumeInspector.inspect(volB).isExt4Ready(), "root-b-bad-ext4");
        require(UmlInstanceBudget.defaultBudget().committedStandaloneMb() == 0, "budget-leaked");
        require(LoopbackEndpointRegistry.defaultRegistry().activeEndpointCount() == 0, "endpoint-leaked");
        require(UmlFaceStatusRegistry.current() == null, "ridealong-leaked");
        awaitForegroundLease(0, false, 4_000L);
    }

    private static void awaitForegroundLease(int expectedCount, boolean expectedWakeLock, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            OrchestellaForegroundService.LeaseSnapshot snap = OrchestellaForegroundService.leaseSnapshot();
            if (snap.activeCount() == expectedCount && snap.wakeLockHeld() == expectedWakeLock) {
                return;
            }
            SystemClock.sleep(40L);
        }
        OrchestellaForegroundService.LeaseSnapshot snap = OrchestellaForegroundService.leaseSnapshot();
        throw new AssertionError(
                "fg-lease-mismatch expectedCount=" + expectedCount
                        + " actualCount=" + snap.activeCount()
                        + " expectedWake=" + expectedWakeLock
                        + " actualWake=" + snap.wakeLockHeld());
    }

    private void ensureAppForeground(Context target) {
        Intent launch = new Intent(target, MainActivity.class);
        launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        target.startActivity(launch);
        SystemClock.sleep(350L);
    }

    private static InstanceStrategyLauncher.LaunchParameters paramsFor(
            Context target, InstanceLifecycle.Instance instance) {
        Path runtimeDir = target.getFilesDir().toPath().resolve("w304-matrix/runtime").resolve(instance.id());
        return new InstanceStrategyLauncher.LaunchParameters(
                instance.id(),
                instance.bridgeToken(),
                UmlInstanceLauncher.ALPINE_MINIROOTFS_RESOURCE,
                instance.rootVolume().toString(),
                runtimeDir.toString(),
                target.getFilesDir().getAbsolutePath(),
                instance.guestPassword(),
                instance.hardwareConfig(),
                null,
                instance.hostname(),
                instance.defaultUser(),
                instance.hasSudo());
    }

    private static Fixture loadFixture(Context target) throws IOException {
        Path matrixDir = target.getFilesDir().toPath().resolve("w304-matrix");
        Path propsPath = matrixDir.resolve("expected-markers.properties");
        require(Files.isRegularFile(propsPath), "missing-prepare-fixture");
        Properties props = new Properties();
        try (var in = Files.newInputStream(propsPath)) {
            props.load(in);
        }
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(matrixDir.resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        UmlInstanceLauncher launcher = new UmlInstanceLauncher(target, store);
        String rideRootRaw = props.getProperty("rideRootDir", "");
        return new Fixture(
                store,
                controller,
                launcher,
                props.getProperty("instAId"),
                props.getProperty("instBId"),
                props.getProperty("volAId"),
                props.getProperty("volBId"),
                rideRootRaw.isBlank() ? null : Path.of(rideRootRaw));
    }

    private static void reconcileStaleInstances(InstanceLifecycle.Controller controller) throws IOException {
        controller.reconcileAfterHostDeath(InstanceLifecycle.State.STOPPED);
    }

    private static void clearPersistedRules(Context target, String instAId, String instBId) throws IOException {
        for (String id : List.of(instAId, instBId)) {
            PortForwardingManager mgr = forwardingManager(target, id);
            for (PortForwardingRule rule : List.copyOf(mgr.listRules())) {
                mgr.removeRule(rule.id());
            }
        }
    }

    private static PortForwardingManager forwardingManager(Context target, String instanceId) throws IOException {
        return PortForwardingManager.forInstance(instanceId, target.getFilesDir().toPath().resolve("network"));
    }

    private static BridgeClient authenticateBridge(UmlInstanceLauncher.Session session, String token)
            throws IOException {
        BridgeClient bridge = session.bridgeClient();
        require(bridge != null, "bridge-null");
        bridge.startResponsePump(request -> "ORCHESTELLA/ERROR unsupported-command\n");
        boolean authenticated = bridge.handshake(token).accepted();
        BridgeConnector.notifyAuthenticated(session.launchStageObserver(), authenticated);
        require(authenticated && bridge.ping().accepted(), "bridge-auth-failed");
        return bridge;
    }

    private static void startHttpServer(Shell shell, int guestPort, String body) throws IOException {
        String script = "/tmp/w304-http-" + guestPort + ".sh";
        shell.succeeds(
                "printf '%s\\n' '#!/bin/sh' 'IFS= read -r _req || :' "
                        + "'printf \"HTTP/1.0 200 OK\\r\\nContent-Length: "
                        + body.length()
                        + "\\r\\n\\r\\n"
                        + body
                        + "\"' >"
                        + script
                        + " && chmod 0755 "
                        + script
                        + " && /bin/busybox nc -ll -p "
                        + guestPort
                        + " -e "
                        + script
                        + " </dev/null >/dev/null 2>&1 & sleep 0.2",
                10);
    }

    private static long readGuestUptimeMs(Shell shell) throws IOException {
        String raw = shell.captureLine("awk '{printf \"%d\", $1 * 1000}' /proc/uptime");
        return Long.parseLong(raw.trim());
    }

    private static int pickFreeUserPort(String instanceId, int... avoid) throws IOException {
        int derived = GatewaySlotArbiter.derivedPort(instanceId);
        for (int attempt = 0; attempt < 16; attempt++) {
            int candidate;
            try (ServerSocket socket = bind(0)) {
                candidate = socket.getLocalPort();
            }
            if (PortForwardingRule.isReservedHostPort(candidate) || candidate == derived) {
                continue;
            }
            boolean clash = false;
            for (int forbidden : avoid) {
                if (candidate == forbidden) {
                    clash = true;
                    break;
                }
            }
            if (!clash) {
                return candidate;
            }
        }
        throw new IOException("no free user port available");
    }

    private static ServerSocket bind(int port) throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return socket;
        } catch (IOException failure) {
            socket.close();
            throw failure;
        }
    }

    private static boolean isBound(int port) throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            socket.close();
            return false;
        } catch (BindException bound) {
            return true;
        }
    }

    private static String readHttp(int hostPort) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", hostPort), 2_000);
            socket.setSoTimeout(4_000);
            var output = socket.getOutputStream();
            output.write("GET / HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            InputStream input = socket.getInputStream();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] chunk = new byte[1_024];
            int contentLength = -1;
            int headerEnd = -1;
            while (raw.size() < 4_096) {
                int read = input.read(chunk);
                if (read == -1) break;
                raw.write(chunk, 0, read);
                String text = new String(raw.toByteArray(), StandardCharsets.US_ASCII);
                if (headerEnd < 0) {
                    int sep = text.indexOf("\r\n\r\n");
                    if (sep >= 0) {
                        headerEnd = sep + 4;
                        Matcher matcher = Pattern.compile("(?im)^Content-Length:[ \\t]*(\\d+)\\r?$")
                                .matcher(text.substring(0, sep));
                        if (matcher.find()) {
                            contentLength = Integer.parseInt(matcher.group(1));
                        }
                    }
                }
                if (headerEnd >= 0 && contentLength >= 0 && raw.size() >= headerEnd + contentLength) {
                    return text.substring(0, headerEnd + contentLength);
                }
            }
            return new String(raw.toByteArray(), StandardCharsets.US_ASCII);
        }
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

    private static long parsePositiveLong(String raw, long fallback) {
        try {
            long val = Long.parseLong(raw);
            return val > 0L ? val : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private record Fixture(
            InstanceLifecycle.FileStore store,
            InstanceLifecycle.Controller controller,
            UmlInstanceLauncher launcher,
            String instAId,
            String instBId,
            String volAId,
            String volBId,
            Path rideRootDir) {}

    private static final class LiveInstance {
        final InstanceLifecycle.Instance instance;
        final String generation;
        final UmlInstanceLauncher.Session session;
        final Shell shell;
        final BridgeClient bridge;

        LiveInstance(
                InstanceLifecycle.Instance instance,
                String generation,
                UmlInstanceLauncher.Session session,
                Shell shell,
                BridgeClient bridge) {
            this.instance = instance;
            this.generation = generation;
            this.session = session;
            this.shell = shell;
            this.bridge = bridge;
        }

        void stopAndClose(Context target, Fixture fixture) throws IOException {
            stopAndRecordNormalExit(target, fixture.store, fixture.controller, instance, generation, session);
        }
    }

    private static final class DummyExecutionSession implements ExecutionSession {
        @Override public int waitFor() { return 0; }
        @Override public java.io.OutputStream terminalInput() { return new ByteArrayOutputStream(); }
        @Override public InputStream terminalOutput() { return new java.io.ByteArrayInputStream(new byte[0]); }
        @Override public java.io.OutputStream controlInput() { return new ByteArrayOutputStream(); }
        @Override public InputStream controlOutput() { return new java.io.ByteArrayInputStream(new byte[0]); }
        @Override public void close() {}
    }

    private static final class Shell {
        private final ExecutionSessionRegistry.LiveSession terminal;
        private int nextCommand;

        Shell(String instanceId, UmlInstanceLauncher.Session session) {
            terminal = ExecutionSessionRegistry.getLiveSession(instanceId);
            require(terminal != null && terminal.rawSession() == session, "shell-missing-live-session");
        }

        void awaitPrompt() {
            long deadline = SystemClock.elapsedRealtime() + 20_000L;
            do {
                if (terminal.holder().getScreenModel().hasObservedShellPrompt()) return;
                SystemClock.sleep(40L);
            } while (terminal.isAlive() && SystemClock.elapsedRealtime() < deadline);
            throw new AssertionError("shell-prompt-timeout");
        }

        void succeeds(String script, long timeoutSeconds) throws IOException {
            int command = ++nextCommand;
            String frame = "( " + script + " ); printf '\\n__W304_RESULT_%d_%d__\\n' "
                    + command + " \"$?\"\n";
            Pattern result = Pattern.compile("__W304_RESULT_" + command + "_(\\d+)__");
            Matcher marker = awaitCommandOutput(frame, result, timeoutSeconds);
            String rc = marker.group(1);
            if (!"0".equals(rc)) {
                throw new AssertionError("uml-shell-rc=" + rc);
            }
        }

        String captureLine(String script) throws IOException {
            int command = ++nextCommand;
            String frame = "W304_OUT=\"$( " + script + " )\"; "
                    + "RC=$?; "
                    + "printf '\\n__W304_BEGIN_%d_%d__\\n%s\\n__W304_END_%d__\\n' "
                    + command + " \"$RC\" \"$W304_OUT\" " + command + "\n";
            Pattern result = Pattern.compile(
                    "__W304_BEGIN_" + command + "_(\\d+)__\\r?\\n([\\s\\S]*?)\\r?\\n__W304_END_" + command + "__");
            Matcher marker = awaitCommandOutput(frame, result, 20);
            String rc = marker.group(1);
            if (!"0".equals(rc)) {
                throw new AssertionError("uml-shell-rc=" + rc);
            }
            return marker.group(2);
        }

        private Matcher awaitCommandOutput(String frame, Pattern pattern, long timeoutSeconds)
                throws IOException {
            terminal.sendBytes(frame.getBytes(StandardCharsets.US_ASCII));
            long deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
            do {
                Matcher marker = pattern.matcher(terminal.getTranscript());
                if (marker.find()) {
                    return marker;
                }
                SystemClock.sleep(40L);
            } while (terminal.isAlive() && SystemClock.elapsedRealtime() < deadline);
            throw new AssertionError("uml-shell-timeout alive=" + terminal.isAlive());
        }
    }
}
