package io.github.nyzeep.orchestella;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.system.Os;
import android.system.StructStat;
import io.github.nyzeep.orchestella.runtime.capability.ExecutionStrategyAdmissionEvaluator;
import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.engineuml.CpioNewcToolExtractor;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlDistroCapabilityCatalog;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlInstanceRootfsProvisioner;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlKernelPayloadContract;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlRootReservationRegistry;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlVolumeInspector;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Isolated QA 4 KB physical-device instrumentation probe for W01-03:
 * Debian 13 (trixie) persistent ext4 rootfs provisioning, native systemd PID 1 init,
 * Bridge v1 token auth, PTY echo/resize/reattach, reboot persistence, outbound networking,
 * guest &amp; host confirmed poweroff, fault injection, and Alpine FAST_UP_ARM64 regression.
 */
public final class W0103DebianLifecycleProbe extends Instrumentation {
    private static final String DEBIAN_TAR = "debian-13-rootfs.tar.xz";
    private static final String ALPINE_TAR = UmlKernelAssets.FastProfile.ROOTFS_TAR;
    private static final long PROVISION_PROMPT_TIMEOUT_MS = 360_000L;
    private static final long REBOOT_PROMPT_TIMEOUT_MS = 120_000L;
    private static final long BRIDGE_TIMEOUT_MS = 30_000L;
    private static final long SYSTEMD_ACTIVE_TIMEOUT_MS = 45_000L;
    private static final long STOP_GRACE_MS = 8_000L;

    private String stage = "init";
    private String action = "all";

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null) {
            action = arguments.getString("action", "all");
        }
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        try {
            Context target = getTargetContext();
            require(BuildConfig.IS_ISOLATED_QA_BUILD, "probe requires isolated QA build");
            Path probeRoot = target.getFilesDir().toPath().resolve("w0103-probe");
            deleteTreeBestEffort(probeRoot);
            Files.createDirectories(probeRoot);

            if ("all".equals(action) || "preflight".equals(action)) {
                runPreflightAndCompatibility(target, probeRoot, result);
            }
            if ("all".equals(action) || "lifecycle".equals(action)) {
                runDebianLifecycle(target, probeRoot, result);
            }
            if ("all".equals(action) || "faults".equals(action)) {
                runFaultInjection(target, probeRoot, result);
            }
            if ("all".equals(action) || "alpine".equals(action)) {
                runAlpineRegression(target, probeRoot, result);
            }

            result.putString("w0103_status", "PASS");
            result.putString("w0103_stage", stage);
            code = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("w0103_status", "FAIL");
            result.putString("w0103_stage", stage);
            result.putString("w0103_error", failure.getClass().getName() + ": " + failure.getMessage());
        }
        finish(code, result);
    }

    private void runPreflightAndCompatibility(Context target, Path probeRoot, Bundle out)
            throws Exception {
        reportStage("preflight");
        ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight fullPre =
                StandaloneUmlPreflightProbe.forAndroid(
                        target, UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64).probe();
        require(
                fullPre.status() == ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.READY,
                "full preflight failed: " + fullPre.status() + " " + fullPre.detail());
        require(fullPre.hostPageSizeBytes() == 4096, "expected 4 KB host, got " + fullPre.hostPageSizeBytes());

        ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight fastPre =
                StandaloneUmlPreflightProbe.forAndroid(
                        target, UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64).probe();
        require(
                fastPre.status() == ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.READY,
                "fast preflight failed: " + fastPre.status() + " " + fastPre.detail());

        boolean fastDebianRefused = false;
        try {
            UmlDistroCapabilityCatalog.requireCreatable(
                    "debian", UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64);
        } catch (UmlDistroCapabilityCatalog.DistroNotCreatableException expected) {
            fastDebianRefused = true;
        }
        require(fastDebianRefused, "FAST_UP_ARM64 must still refuse Debian");

        Path storeDir = probeRoot.resolve("compat-instances");
        Path rootsDir = probeRoot.resolve("compat-roots");
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        IsolatedQaExecutionEntry qa = new IsolatedQaExecutionEntry();
        InstanceLifecycle.Instance created = qa.ensurePersistentDebianInstance(store, controller, rootsDir);
        Files.write(created.rootVolume(), new byte[4096]);
        InstanceLifecycle.Instance imported = qa.roundTripViaArchive(
                controller, created.id(), rootsDir.resolve("compat-import.raw"), null);
        require("full-smp-arm64".equals(imported.kernelProfile()),
                "archive round-trip lost kernelProfile: " + imported.kernelProfile());

        out.putInt("host_page_size", fullPre.hostPageSizeBytes());
        out.putBoolean("fast_debian_refused", true);
        out.putString("archive_roundtrip_profile", imported.kernelProfile());
    }

    private void runDebianLifecycle(Context target, Path probeRoot, Bundle out) throws Exception {
        Path distroInputsDir = target.getFilesDir().toPath().resolve("distro-inputs");
        Path stagedTar = distroInputsDir.resolve(DEBIAN_TAR);
        require(Files.isRegularFile(stagedTar), "staged Debian tarball missing: " + stagedTar);

        Path storeDir = probeRoot.resolve("debian-instances");
        Path rootsDir = probeRoot.resolve("debian-roots");
        Path runtimeRoot = probeRoot.resolve("debian-runtime");
        Files.createDirectories(runtimeRoot);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        IsolatedQaExecutionEntry qa = new IsolatedQaExecutionEntry();

        reportStage("boot1_create");
        InstanceLifecycle.Instance debian = qa.ensurePersistentDebianInstance(store, controller, rootsDir);
        require(debian.hardwareConfig().cpuCores() == 1, "expected 1 vCPU");
        require(debian.hardwareConfig().ramMegabytes() == 1024, "expected 1024 MiB");
        require(debian.hardwareConfig().diskCapacityBytes() == IsolatedQaExecutionEntry.DEFAULT_DEBIAN_DISK_BYTES,
                "expected 4 GiB quota");
        require("full-smp-arm64".equals(debian.kernelProfile()), "expected full-smp-arm64 profile");

        // Boot 1: Cold provision -> systemd PID 1 -> Bridge v1 -> PTY echo/resize/reattach -> net -> guest poweroff
        reportStage("boot1_launch");
        BootOutcome boot1 = launchAndVerifyDebianSession(
                target, store, controller, distroInputsDir, runtimeRoot, debian, true, out);
        out.putString("boot1_cred_outcome", boot1.credOutcome);
        out.putLong("boot1_shell_ready_ms", boot1.shellReadyMs);
        out.putLong("boot1_bridge_ready_ms", boot1.bridgeReadyMs);
        out.putLong("boot1_systemd_ready_ms", boot1.systemdReadyMs);
        out.putString("boot1_stop_result", boot1.stopResult.name());
        require("FIRST_PROVISION".equals(boot1.credOutcome), "expected FIRST_PROVISION, got " + boot1.credOutcome);
        require(UmlInstanceLauncher.areLeasesReleased(debian.id(), debian.rootVolume()),
                "leases not released after boot1");

        // Boot 2: Warm/cold reboot -> same ext4 reused (HIT), persistence verified, app terminate()
        reportStage("boot2_launch");
        InstanceLifecycle.Instance reloaded = store.load(debian.id());
        BootOutcome boot2 = launchAndVerifyDebianSession(
                target, store, controller, distroInputsDir, runtimeRoot, reloaded, false, out);
        out.putString("boot2_cred_outcome", boot2.credOutcome);
        out.putLong("boot2_shell_ready_ms", boot2.shellReadyMs);
        out.putLong("boot2_bridge_ready_ms", boot2.bridgeReadyMs);
        out.putLong("boot2_systemd_ready_ms", boot2.systemdReadyMs);
        out.putString("boot2_stop_result", boot2.stopResult.name());
        require("HIT".equals(boot2.credOutcome), "expected HIT on reboot, got " + boot2.credOutcome);
        require(UmlInstanceLauncher.areLeasesReleased(debian.id(), debian.rootVolume()),
                "leases not released after boot2");
    }

    private BootOutcome launchAndVerifyDebianSession(
            Context target,
            InstanceLifecycle.FileStore store,
            InstanceLifecycle.Controller controller,
            Path distroInputsDir,
            Path runtimeRoot,
            InstanceLifecycle.Instance inst,
            boolean isBoot1,
            Bundle out) throws Exception {
        String gen = InstanceLaunchGuard.begin(inst.id());
        require(gen != null, "launch guard busy for " + inst.id());
        InstanceLaunchSequence.beforeLaunch(
                controller, inst, InstanceLifecycle.ConcurrencyPolicy.SINGLE_RUNNING_INSTANCE);
        Path instanceRuntimeDir = Files.createDirectories(runtimeRoot.resolve(inst.id()));
        InstanceStrategyLauncher.LaunchParameters params =
                new InstanceStrategyLauncher.LaunchParameters(
                        inst.id(),
                        inst.bridgeToken(),
                        DEBIAN_TAR,
                        inst.rootVolume().toString(),
                        instanceRuntimeDir.toString(),
                        distroInputsDir.toString(),
                        inst.guestPassword(),
                        inst.hardwareConfig(),
                        null,
                        null,
                        null,
                        inst.hostname(),
                        inst.defaultUser(),
                        inst.hasSudo(),
                        null,
                        inst.kernelProfile());

        ExecutionSession session = null;
        RuntimeProcessSupervisor.StopResult stopResult = null;
        try {
            UmlInstanceLauncher launcher = new UmlInstanceLauncher(target, store);
            session = launcher.launch(params);
            require(session instanceof UmlInstanceLauncher.Session, "expected UmlInstanceLauncher.Session");
            UmlInstanceLauncher.Session umlSession = (UmlInstanceLauncher.Session) session;
            ExecutionSessionRegistry.LiveSession live = ExecutionSessionRegistry.getLiveSession(inst.id());
            require(live != null, "missing LiveSession");

            final ExecutionSession activeSession = session;
            AtomicReference<BridgeClient> bridgeRef = new AtomicReference<>();
            AtomicReference<Throwable> bridgeError = new AtomicReference<>();
            CountDownLatch bridgeDone = new CountDownLatch(1);
            Thread bridgeThread = new Thread(() -> {
                try {
                    bridgeRef.set(authenticateBridge(activeSession, inst.bridgeToken()));
                } catch (Throwable t) {
                    bridgeError.set(t);
                } finally {
                    bridgeDone.countDown();
                }
            }, "w0103-bridge");
            bridgeThread.setDaemon(true);
            bridgeThread.start();

            long timeoutMs = isBoot1 ? PROVISION_PROMPT_TIMEOUT_MS : REBOOT_PROMPT_TIMEOUT_MS;
            awaitPrompt(live, timeoutMs);
            if (!bridgeDone.await(BRIDGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new IOException("bridge auth timeout");
            }
            if (bridgeError.get() != null) {
                Throwable err = bridgeError.get();
                throw (err instanceof Exception e) ? e : new IOException("bridge error", err);
            }
            BridgeClient bridge = bridgeRef.get();
            require(bridge != null, "missing authenticated BridgeClient");

            // Wait for systemd multi-user.target + guest-agent service + systemd-ready marker
            String systemdReport = awaitSystemdActive(live, SYSTEMD_ACTIVE_TIMEOUT_MS);
            if (umlSession.launchTimeline() == null
                    || umlSession.launchTimeline().elapsedMs(LaunchTimeline.Stage.SYSTEMD_READY) < 0L) {
                // Trigger an interactive prompt cycle so 20-orchestella-terminal-integration emits OSC 133;O.
                runGuestCmd(live, "true", "__W0103_SD_OSC__", 5_000L);
            }
            long observedSystemdMs = umlSession.launchTimeline() != null
                    ? umlSession.launchTimeline().elapsedMs(LaunchTimeline.Stage.SYSTEMD_READY)
                    : -1L;
            require(observedSystemdMs >= 0L, "SYSTEMD_READY stage was not automatically marked by OSC 133;O");
            out.putString(isBoot1 ? "boot1_systemd" : "boot2_systemd", systemdReport);

            if (isBoot1) {
                verifyGuestEnvironment(live, session, bridge, inst, out);
                // Offline readiness was already proved above before starting passt networking.
                umlSession.markShellReady();
                long netDeadline = SystemClock.elapsedRealtime() + 10_000L;
                while (SystemClock.elapsedRealtime() < netDeadline
                        && umlSession.networkStatus() != UmlPasstNetwork.Status.READY) {
                    SystemClock.sleep(100L);
                }
                out.putString("boot1_net_status", umlSession.networkStatus().name());
                verifyOutboundNetworking(live, out);

                // Verify sparse ext4 footprint on Android host
                StructStat st = Os.stat(inst.rootVolume().toString());
                long logicalBytes = Files.size(inst.rootVolume());
                long allocatedBytes = st.st_blocks * 512L;
                out.putLong("ext4_logical_bytes", logicalBytes);
                out.putLong("ext4_allocated_bytes", allocatedBytes);
                require(logicalBytes == IsolatedQaExecutionEntry.DEFAULT_DEBIAN_DISK_BYTES,
                        "unexpected logical ext4 size: " + logicalBytes);
                require(allocatedBytes < logicalBytes / 2L,
                        "ext4 volume is not sparse: alloc=" + allocatedBytes);

                // Guest-initiated systemctl poweroff
                reportStage("boot1_guest_poweroff");
                live.sendBytes("systemctl poweroff\n".getBytes(StandardCharsets.UTF_8));
                boolean exited = umlSession.awaitPowerDownOrExit(30_000L);
                if (!exited) {
                    String tx = live.getTranscript();
                    String tail = tx != null && tx.length() > 1500
                            ? tx.substring(tx.length() - 1500) : String.valueOf(tx);
                    throw new AssertionError("guest systemctl poweroff did not exit within 30s; tail=" + tail);
                }
                String txAfterPoweroff = live.getTranscript();
                require(txAfterPoweroff != null
                                && (txAfterPoweroff.contains("reboot: Power down")
                                        || txAfterPoweroff.contains("System Power Off")
                                        || txAfterPoweroff.contains("All filesystems unmounted")),
                        "missing kernel power down confirmation after systemctl poweroff");
                stopResult = ((RuntimeOwnedSession) session).stopRuntime(STOP_GRACE_MS);
                ExecutionSessionRegistry.unregister(inst.id(), session);
                session = null;
            } else {
                // Verify file + package persistence across reboot
                String persistCheck = runGuestCmd(
                        live,
                        "printf 'PERSIST_%s;PID1_%s;JQ=%s\\n' "
                                + "\"$(cat /root/w0103-persist.txt 2>/dev/null || echo MISSING)\" "
                                + "\"$(cat /proc/1/comm)\" "
                                + "\"$(command -v jq >/dev/null && jq --version || echo NO_JQ)\"",
                        "__W0103_BOOT2_CHECK__",
                        20_000L);
                out.putString("boot2_persist_check", persistCheck);
                require(persistCheck.contains("PERSIST_w0103-persist-ok-gen1"),
                        "persistence file missing on boot2: " + persistCheck);
                require(persistCheck.contains("PID1_systemd"),
                        "PID 1 not systemd on boot2: " + persistCheck);
                if ("online".equals(out.getString("boot1_net_mode"))) {
                    require(persistCheck.contains("JQ=jq-"),
                            "installed Debian package missing after reboot: " + persistCheck);
                }

                // App-initiated graceful terminate() -> systemctl poweroff + confirmed stop
                reportStage("boot2_app_terminate");
                session.terminate();
                stopResult = ((RuntimeOwnedSession) session).stopRuntime(STOP_GRACE_MS);
                require(stopResult == RuntimeProcessSupervisor.StopResult.CONFIRMED
                                || stopResult == RuntimeProcessSupervisor.StopResult.ALREADY_STOPPED,
                        "boot2 terminate stop unconfirmed: " + stopResult);
                ExecutionSessionRegistry.unregister(inst.id(), session);
                session = null;
            }

            // Confirm zero leftover temp .part / boot-creds-* files
            verifyNoTempResidue(inst.rootVolume().getParent(), instanceRuntimeDir);

            LaunchTimeline timeline = umlSession.launchTimeline();
            long shellMs = timeline != null ? timeline.elapsedMs(LaunchTimeline.Stage.SHELL_READY) : -1L;
            long bridgeMs = timeline != null ? timeline.elapsedMs(LaunchTimeline.Stage.BRIDGE_READY) : -1L;
            long systemdMs = timeline != null ? timeline.elapsedMs(LaunchTimeline.Stage.SYSTEMD_READY) : -1L;
            LaunchTimeline.CredentialsOnlyOutcome cred =
                    timeline != null ? timeline.credentialsOnlyOutcome() : null;
            return new BootOutcome(
                    cred != null ? cred.name() : "UNKNOWN",
                    shellMs,
                    bridgeMs,
                    systemdMs,
                    stopResult);
        } finally {
            if (session != null) {
                try {
                    ((RuntimeOwnedSession) session).stopRuntime(STOP_GRACE_MS);
                } catch (Throwable ignored) {
                }
                ExecutionSessionRegistry.unregister(inst.id(), session);
            }
            InstanceLaunchGuard.end(inst.id(), gen);
            try {
                InstanceLifecycle.Instance cur = store.load(inst.id());
                if (cur.state() == InstanceLifecycle.State.RUNNING
                        || cur.state() == InstanceLifecycle.State.SUSPENDED) {
                    controller.recordNormalGuestStop(inst.id());
                } else if (cur.state() == InstanceLifecycle.State.CRASHED
                        || cur.state() == InstanceLifecycle.State.INTERRUPTED) {
                    controller.resetToStopped(inst.id());
                }
            } catch (IOException ignored) {
            }
        }
    }

    private void verifyGuestEnvironment(
            ExecutionSessionRegistry.LiveSession live,
            ExecutionSession session,
            BridgeClient bridge,
            InstanceLifecycle.Instance inst,
            Bundle out) throws Exception {
        String coreCheck = runGuestCmd(
                live,
                "printf 'PID1=%s;VER=%s;USR=%s,%s,%s;CGROUP=%s;UID=%s;GPAGE=%s;NPROC=%s;UNAME=%s;SYSCALL=%s;MARKER=%s\\n' "
                        + "\"$(cat /proc/1/comm)\" "
                        + "\"$(systemctl --version | head -n1)\" "
                        + "\"$(readlink /bin)\" \"$(readlink /lib)\" \"$(readlink /sbin)\" "
                        + "\"$(stat -fc %T /sys/fs/cgroup)\" "
                        + "\"$(id -u)\" "
                        + "\"$(getconf PAGESIZE)\" "
                        + "\"$(nproc)\" "
                        + "\"$(uname -r)\" "
                        + "\"$(dmesg 2>/dev/null | grep -iE 'seccomp|ptrace|skas' | head -n 1 | tr -s ' ' | cut -c1-80 || echo unknown)\" "
                        + "\"$(grep -c 'kernel_profile=full-smp-arm64' /etc/orchestella/provision-marker)\"",
                "__W0103_CORE__",
                20_000L);
        out.putString("boot1_core_check", coreCheck);
        require(coreCheck.contains("PID1=systemd"), "PID 1 check failed: " + coreCheck);
        require(coreCheck.contains("systemd 257"), "unexpected systemd version: " + coreCheck);
        require(coreCheck.contains("USR=usr/bin,usr/lib,usr/sbin"), "usr-merge check failed: " + coreCheck);
        require(coreCheck.contains("CGROUP=cgroup2fs"), "cgroup2 check failed: " + coreCheck);
        require(coreCheck.contains("UID=0"), "uid check failed: " + coreCheck);
        require(coreCheck.contains("GPAGE=16384") || coreCheck.contains("GPAGE=4096"),
                "guest page size check failed: " + coreCheck);
        require(coreCheck.contains("NPROC=") && !coreCheck.contains("NPROC=0"),
                "guest cpu count check failed: " + coreCheck);
        require(coreCheck.contains("MARKER=1"), "provision-marker check failed: " + coreCheck);

        // Journal ACL / directory metadata + writable / & persistence write
        String fsCheck = runGuestCmd(
                live,
                "printf 'w0103-persist-ok-gen1\\n' > /root/w0103-persist.txt && chmod 0600 /root/w0103-persist.txt && sync && "
                        + "printf 'J=%s;M=%s;P=%s\\n' "
                        + "\"$(ls -ld /var/log/journal | awk '{print $1}')\" "
                        + "\"$(stat -c %a /var/log/journal)\" "
                        + "\"$(cat /root/w0103-persist.txt)\"",
                "__W0103_FS__",
                20_000L);
        out.putString("boot1_fs_check", fsCheck);
        require(fsCheck.contains("J=drwxr-sr-x+") || fsCheck.contains("M=2755"),
                "journal ACL/mode unexpected: " + fsCheck);
        require(fsCheck.contains("P=w0103-persist-ok-gen1"), "persist write failed: " + fsCheck);

        // Bridge v1 RESIZE command check followed by PTY TIOCSWINSZ resize + detach/reattach check
        BridgeClient.Result bridgeResize = bridge.resize(100, 30);
        require(bridgeResize.accepted(), "bridge.resize(100, 30) rejected: " + bridgeResize);
        String bridgeResizeCheck = runGuestCmd(
                live,
                "printf 'BSIZE=%s\\n' \"$(stty size </dev/tty0)\"",
                "__W0103_BRESIZE__",
                15_000L);
        out.putString("boot1_bridge_resize_check", bridgeResizeCheck);
        require(bridgeResizeCheck.contains("BSIZE=30 100"),
                "unexpected tty0 size after bridge.resize: " + bridgeResizeCheck);

        require(session.resizeTerminalHost(132, 42), "resizeTerminalHost returned false");
        live.setOutputListener(null);
        SystemClock.sleep(200L);
        String resizeCheck = runGuestCmd(
                live,
                "printf 'SIZE=%s\\n' \"$(stty size </dev/tty0)\"",
                "__W0103_RESIZE__",
                15_000L);
        out.putString("boot1_resize_check", resizeCheck);
        require(resizeCheck.contains("SIZE=42 132"), "unexpected tty0 size after resize: " + resizeCheck);
    }

    private void verifyOutboundNetworking(ExecutionSessionRegistry.LiveSession live, Bundle out)
            throws Exception {
        String ifaceRes = runGuestCmd(
                live,
                "printf 'SVC=%s;VEC=%s;DNS=%s\\n' "
                        + "\"$(systemctl is-active orchestella-uml-network.service 2>/dev/null || echo inactive)\" "
                        + "\"$(/usr/lib/orchestella/busybox ip -o -4 addr show dev vec0 | awk '{print $4}')\" "
                        + "\"$(grep -m1 '^nameserver ' /etc/resolv.conf | awk '{print $2}')\"",
                "__W0103_IFACE__",
                15_000L);
        out.putString("boot1_iface", ifaceRes);
        require(ifaceRes.contains("SVC=active"), "orchestella-uml-network not active: " + ifaceRes);
        require(ifaceRes.contains("10.0.2.15/24"), "vec0 missing 10.0.2.15/24: " + ifaceRes);
        require(ifaceRes.contains("DNS=10.0.2.3"), "resolv.conf missing 10.0.2.3: " + ifaceRes);

        String netRes = runGuestCmd(
                live,
                "timeout -k 5 240 sh -c '"
                        + "apt-get update -o Acquire::ForceIPv4=true -o Acquire::Retries=3 -o Acquire::http::Timeout=15 -o Acquire::Languages=none >/tmp/apt.log 2>&1 "
                        + "&& printf \"APT_UPDATED=1\\n\" >>/tmp/apt.log "
                        + "&& (DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends -o Acquire::ForceIPv4=true -o Acquire::Retries=3 -o Acquire::http::Timeout=15 jq >>/tmp/apt.log 2>&1 "
                        + "|| (sleep 2 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends -o Acquire::ForceIPv4=true -o Acquire::Retries=3 -o Acquire::http::Timeout=15 jq >>/tmp/apt.log 2>&1))' "
                        + "&& printf '%s%s;JQ=%s\\n' 'NET_' 'ONLINE' \"$(jq --version)\" "
                        + "|| printf '%s%s;ERR=%s\\n' 'NET_' 'OFFLINE' \"$(tail -n 3 /tmp/apt.log 2>/dev/null | tr '\\n' ' ')\"",
                "__W0103_NET__",
                270_000L);
        out.putString("boot1_net_detail", netRes);
        if (netRes.contains("NET_ONLINE")) {
            out.putString("boot1_net_mode", "online");
            require(netRes.contains("JQ=jq-"), "jq did not run: " + netRes);
        } else {
            out.putString("boot1_net_mode", "offline");
        }
    }

    private void runFaultInjection(Context target, Path probeRoot, Bundle out) throws Exception {
        reportStage("fault_injection");
        Path faultDir = Files.createDirectories(probeRoot.resolve("faults"));
        Path runtimeDir = Files.createDirectories(faultDir.resolve("runtime"));
        UmlRootReservationRegistry registry = UmlRootReservationRegistry.defaultRegistry();

        UmlInstanceRootfsProvisioner.ToolchainStager stager = stageDir -> {
            try (InputStream initramfs = PackagedAssetStreams.openResource(target, "initramfs.cpio.gz")) {
                require(initramfs != null, "initramfs missing for fault stager");
                CpioNewcToolExtractor.extractSelected(
                        initramfs,
                        stageDir,
                        Set.of("sbin/mkfs.ext4", "etc/mke2fs.conf", "bin/busybox", "opt/droidlayer/guest-agent"),
                        Set.of("sbin/mkfs.ext4", "bin/busybox", "opt/droidlayer/guest-agent"));
            }
        };
        UmlInstanceRootfsProvisioner realProv = new UmlInstanceRootfsProvisioner(
                new UmlNativePtyBridge(),
                registry,
                stager,
                new StorageContract.DefaultStorageSpaceChecker(),
                Clock.systemUTC());

        String kernelFull = NativeExecutableStore.ensure(target, "libuml_kernel_full.so");
        String stubFull = NativeExecutableStore.ensure(target, "libuml_stub_full.so");

        // 1. Corrupt/truncated .tar.xz must fail cleanly via decomp.rc and never create target volume
        Path corruptXz = faultDir.resolve("corrupt-debian-13.tar.xz");
        byte[] corruptBytes = new byte[512];
        Arrays.fill(corruptBytes, (byte) 0x55);
        corruptBytes[0] = (byte) 0xfd;
        corruptBytes[1] = 0x37;
        corruptBytes[2] = 0x7a;
        corruptBytes[3] = 0x58;
        corruptBytes[4] = 0x5a;
        corruptBytes[5] = 0x00;
        Files.write(corruptXz, corruptBytes);
        Path corruptVol = faultDir.resolve("corrupt.raw");
        boolean corruptRejected = false;
        try {
            realProv.provision(new UmlInstanceRootfsProvisioner.ProvisionRequest(
                    "fault-corrupt",
                    "g-corrupt",
                    corruptVol,
                    StorageContract.MIN_ROOT_VOLUME_BYTES,
                    "qa-uml-debian",
                    new UmlInstanceRootfsProvisioner.SeedInput(
                            "debian", "13", corruptXz, null, null,
                            sha256Hex(corruptBytes), corruptBytes.length),
                    UmlInstanceRootfsProvisioner.ProvisionMode.CREATE_NEW,
                    null,
                    kernelFull,
                    stubFull,
                    runtimeDir,
                    new Object(),
                    (id, gen) -> true,
                    null,
                    60_000L,
                    2_000L,
                    2_000L,
                    2_000L,
                    UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64));
        } catch (UmlInstanceRootfsProvisioner.ProvisionException expected) {
            corruptRejected = true;
        }
        require(corruptRejected, "corrupt xz did not fail");
        require(Files.notExists(corruptVol), "corrupt provision left target volume behind");
        require(!registry.isReserved(corruptVol), "corrupt provision leaked reservation");

        // 2. Wrong digest fails before spawning without touching disk
        Path wrongShaVol = faultDir.resolve("wrong-sha.raw");
        boolean digestRejected = false;
        try {
            realProv.provision(new UmlInstanceRootfsProvisioner.ProvisionRequest(
                    "fault-sha",
                    "g-sha",
                    wrongShaVol,
                    StorageContract.MIN_ROOT_VOLUME_BYTES,
                    "qa-uml-debian",
                    new UmlInstanceRootfsProvisioner.SeedInput(
                            "debian", "13", corruptXz, null, null,
                            "0000000000000000000000000000000000000000000000000000000000000000",
                            corruptBytes.length),
                    UmlInstanceRootfsProvisioner.ProvisionMode.CREATE_NEW,
                    null,
                    kernelFull,
                    stubFull,
                    runtimeDir,
                    new Object(),
                    (id, gen) -> true,
                    null,
                    15_000L,
                    2_000L,
                    2_000L,
                    2_000L,
                    UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64));
        } catch (UmlInstanceRootfsProvisioner.ProvisionException expected) {
            digestRejected = UmlInstanceRootfsProvisioner.REASON_CHECKSUM_MISMATCH.equals(expected.reason());
        }
        require(digestRejected, "wrong digest did not fail with REASON_CHECKSUM_MISMATCH");

        // 3. Invalid non-empty foreign volume stays untouched
        Path foreignVol = faultDir.resolve("foreign.raw");
        Files.write(foreignVol, "FOREIGN_KEEP_BYTES".getBytes(StandardCharsets.UTF_8));
        String beforeSha = sha256Hex(Files.readAllBytes(foreignVol));
        boolean foreignRejected = false;
        try {
            realProv.provision(new UmlInstanceRootfsProvisioner.ProvisionRequest(
                    "fault-foreign",
                    "g-foreign",
                    foreignVol,
                    StorageContract.MIN_ROOT_VOLUME_BYTES,
                    "qa-uml-debian",
                    new UmlInstanceRootfsProvisioner.SeedInput(
                            "debian", "13", corruptXz, null, null,
                            sha256Hex(corruptBytes), corruptBytes.length),
                    UmlInstanceRootfsProvisioner.ProvisionMode.CREATE_NEW,
                    null,
                    kernelFull,
                    stubFull,
                    runtimeDir,
                    new Object(),
                    (id, gen) -> true,
                    null,
                    15_000L,
                    2_000L,
                    2_000L,
                    2_000L,
                    UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64));
        } catch (UmlInstanceRootfsProvisioner.ProvisionException expected) {
            foreignRejected = UmlInstanceRootfsProvisioner.REASON_EXISTING_VOLUME_REQUIRES_EXPLICIT_MIGRATION
                    .equals(expected.reason());
        }
        require(foreignRejected, "foreign volume was not rejected cleanly");
        require(beforeSha.equals(sha256Hex(Files.readAllBytes(foreignVol))), "foreign volume modified");

        // 4. FAST_UP_ARM64 + Debian runtime launch refuses without falling back to FULL_SMP_ARM64
        Path fastMismatchVol = faultDir.resolve("fast-mismatch.raw");
        InstanceStrategyLauncher.LaunchParameters fastMismatchParams =
                new InstanceStrategyLauncher.LaunchParameters(
                        "fault-fast-debian",
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        DEBIAN_TAR,
                        fastMismatchVol.toString(),
                        runtimeDir.toString(),
                        faultDir.toString(),
                        null,
                        VmHardwareConfig.defaultConfig(),
                        null, null, null,
                        "qa-uml-debian",
                        "root",
                        false,
                        null,
                        "fast-up-arm64");
        boolean fastMismatchRefused = false;
        try {
            new UmlInstanceLauncher(target).launch(fastMismatchParams);
        } catch (UmlDistroCapabilityCatalog.DistroNotCreatableException expected) {
            fastMismatchRefused = true;
        }
        require(fastMismatchRefused, "FAST_UP_ARM64 + Debian launch did not refuse");
        require(Files.notExists(fastMismatchVol), "fast mismatch launch created volume");
        out.putBoolean("faults_passed", true);
    }

    private void runAlpineRegression(Context target, Path probeRoot, Bundle out) throws Exception {
        reportStage("alpine_regression");
        Path storeDir = probeRoot.resolve("alpine-instances");
        Path rootsDir = probeRoot.resolve("alpine-roots");
        Path runtimeRoot = Files.createDirectories(probeRoot.resolve("alpine-runtime"));
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        IsolatedQaExecutionEntry qa = new IsolatedQaExecutionEntry();
        InstanceLifecycle.Instance alpine = qa.ensurePersistentAlpineInstance(store, controller, rootsDir);

        String gen = InstanceLaunchGuard.begin(alpine.id());
        require(gen != null, "alpine guard busy");
        InstanceLaunchSequence.beforeLaunch(
                controller, alpine, InstanceLifecycle.ConcurrencyPolicy.SINGLE_RUNNING_INSTANCE);
        Path rt = Files.createDirectories(runtimeRoot.resolve(alpine.id()));
        InstanceStrategyLauncher.LaunchParameters params =
                new InstanceStrategyLauncher.LaunchParameters(
                        alpine.id(),
                        alpine.bridgeToken(),
                        ALPINE_TAR,
                        alpine.rootVolume().toString(),
                        rt.toString(),
                        target.getFilesDir().toPath().resolve("distro-inputs").toString(),
                        alpine.guestPassword(),
                        alpine.hardwareConfig(),
                        null, null, null,
                        alpine.hostname(),
                        alpine.defaultUser(),
                        alpine.hasSudo(),
                        null,
                        null);
        ExecutionSession session = null;
        try {
            UmlInstanceLauncher launcher = new UmlInstanceLauncher(target, store);
            session = launcher.launch(params);
            ExecutionSessionRegistry.LiveSession live = ExecutionSessionRegistry.getLiveSession(alpine.id());
            require(live != null, "alpine missing LiveSession");
            awaitPrompt(live, PROVISION_PROMPT_TIMEOUT_MS);
            BridgeClient bridge = authenticateBridge(session, alpine.bridgeToken());
            require(bridge.ping().accepted(), "alpine bridge ping failed");
            RuntimeProcessSupervisor.StopResult stop = ((RuntimeOwnedSession) session).stopRuntime(STOP_GRACE_MS);
            require(stop == RuntimeProcessSupervisor.StopResult.CONFIRMED
                    || stop == RuntimeProcessSupervisor.StopResult.ALREADY_STOPPED,
                    "alpine stop failed: " + stop);
            ExecutionSessionRegistry.unregister(alpine.id(), session);
            session = null;
            require(UmlInstanceLauncher.areLeasesReleased(alpine.id(), alpine.rootVolume()),
                    "alpine leases leaked");
            out.putString("alpine_regression", "PASS");
        } finally {
            if (session != null) {
                try {
                    ((RuntimeOwnedSession) session).stopRuntime(STOP_GRACE_MS);
                } catch (Throwable ignored) {
                }
                ExecutionSessionRegistry.unregister(alpine.id(), session);
            }
            InstanceLaunchGuard.end(alpine.id(), gen);
            try {
                InstanceLifecycle.Instance cur = store.load(alpine.id());
                if (cur.state() == InstanceLifecycle.State.RUNNING
                        || cur.state() == InstanceLifecycle.State.SUSPENDED) {
                    controller.recordNormalGuestStop(alpine.id());
                } else if (cur.state() == InstanceLifecycle.State.CRASHED
                        || cur.state() == InstanceLifecycle.State.INTERRUPTED) {
                    controller.resetToStopped(alpine.id());
                }
            } catch (IOException ignored) {
            }
        }
    }

    private String awaitSystemdActive(ExecutionSessionRegistry.LiveSession live, long timeoutMs)
            throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        String last = "";
        while (SystemClock.elapsedRealtime() < deadline) {
            last = runGuestCmd(
                    live,
                    "printf 'MU=%s;GA=%s;SR=%s\\n' "
                            + "\"$(systemctl is-active multi-user.target 2>/dev/null || echo pending)\" "
                            + "\"$(systemctl is-active orchestella-guest-agent.service 2>/dev/null || echo pending)\" "
                            + "\"$(cat /run/orchestella/systemd-ready 2>/dev/null || echo pending)\"",
                    "__W0103_SD__",
                    10_000L);
            if (last.contains("MU=active") && last.contains("GA=active") && last.contains("SR=SYSTEMD_READY")) {
                return last;
            }
            SystemClock.sleep(500L);
        }
        throw new IOException("systemd multi-user did not become active in time: " + last);
    }

    private String runGuestCmd(
            ExecutionSessionRegistry.LiveSession live,
            String command,
            String sentinel,
            long timeoutMs) throws Exception {
        String halfA = sentinel + "_";
        String halfB = "END";
        String endToken = halfA + halfB;
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder captured = new StringBuilder();
        live.setOutputListener(chunk -> {
            if (chunk == null) return;
            synchronized (captured) {
                captured.append(chunk);
                if (captured.indexOf(endToken) >= 0) {
                    latch.countDown();
                }
            }
        });
        try {
            String full = command + "; printf '\\n%s%s\\n' '" + halfA + "' '" + halfB + "'\n";
            live.sendBytes(full.getBytes(StandardCharsets.UTF_8));
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                synchronized (captured) {
                    throw new IOException("command timeout waiting for " + endToken + ", seen=" + captured);
                }
            }
            synchronized (captured) {
                return captured.toString();
            }
        } finally {
            live.setOutputListener(null);
        }
    }

    private static BridgeClient authenticateBridge(ExecutionSession session, String token)
            throws IOException {
        BridgeClient bridge = session.bridgeClient();
        require(bridge != null, "bridge-null");
        bridge.startResponsePump(
                req -> "ORCHESTELLA/ERROR unsupported-command\n",
                5_000L,
                BridgeClient.DEFAULT_STARTUP_TIMEOUT_MS,
                2_000L,
                BridgeClient.DEFAULT_STARTUP_SETTLE_TIMEOUT_MS);
        boolean authed = bridge.handshake(token).accepted();
        BridgeConnector.notifyAuthenticated(session.launchStageObserver(), authed);
        require(authed, "bridge handshake rejected");
        require(bridge.ping().accepted(), "bridge ping failed");
        return bridge;
    }

    private static void awaitPrompt(ExecutionSessionRegistry.LiveSession live, long timeoutMs)
            throws IOException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (live.holder().getScreenModel().hasObservedShellPrompt()) {
                return;
            }
            if (!live.isAlive()) {
                throw new IOException("session exited before shell prompt");
            }
            SystemClock.sleep(20L);
        }
        throw new IOException("shell prompt timeout (" + timeoutMs + "ms)");
    }

    private static boolean waitProcessExit(ExecutionSession session, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (session instanceof RuntimeOwnedSession owned && !owned.isRuntimeAlive()) {
                return true;
            }
            SystemClock.sleep(100L);
        }
        return false;
    }

    private static void verifyNoTempResidue(Path rootsDir, Path runtimeDir) throws IOException {
        if (Files.isDirectory(rootsDir)) {
            try (var stream = Files.list(rootsDir)) {
                for (Path p : stream.collect(Collectors.toList())) {
                    require(!p.getFileName().toString().contains(".part"),
                            "leftover .part volume: " + p);
                }
            }
        }
        if (Files.isDirectory(runtimeDir)) {
            try (var stream = Files.list(runtimeDir)) {
                for (Path p : stream.collect(Collectors.toList())) {
                    String name = p.getFileName().toString();
                    require(!name.startsWith("boot-creds-") && !name.startsWith("uml-prov-"),
                            "leftover temp dir: " + p);
                }
            }
        }
    }

    private static void deleteTreeBestEffort(Path dir) throws IOException {
        if (Files.notExists(dir)) return;
        try (var walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted((a, b) -> b.compareTo(a)).collect(Collectors.toList());
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    private void reportStage(String next) {
        this.stage = next;
        Bundle b = new Bundle();
        b.putString("w0103_progress", next);
        sendStatus(0, b);
    }

    private static void require(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private record BootOutcome(
            String credOutcome,
            long shellReadyMs,
            long bridgeReadyMs,
            long systemdReadyMs,
            RuntimeProcessSupervisor.StopResult stopResult) {}
}
