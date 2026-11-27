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
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.contract.Protocol;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest;
import io.github.nyzeep.orchestella.runtime.distro.OfficialDebianCatalog;
import io.github.nyzeep.orchestella.runtime.engineuml.CpioNewcToolExtractor;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlDistroCapabilityCatalog;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlInstanceBudget;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlInstanceRootfsProvisioner;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlKernelPayloadContract;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlProcessHandle;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlRootReservationRegistry;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlRuntimeSupervisor;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlVolumeInspector;
import io.github.nyzeep.orchestella.runtime.instance.GatewaySlotArbiter;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import io.github.nyzeep.orchestella.runtime.instance.InstanceStorageManager;
import io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig;
import io.github.nyzeep.orchestella.runtime.network.LoopbackEndpointRegistry;
import io.github.nyzeep.orchestella.runtime.network.PortForwardingManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Isolated QA 4 KB physical-device instrumentation probe for W01-05:
 * re-verifies the frozen qa.w0105 candidate end-to-end across ordinary QA entry,
 * Debian 13 + Docker full workload, foreground keepalive/reattach/reboot, fault/backup/lease
 * regressions, Alpine/QEMU compatibility, multi-window Debian launch distributions, and paired
 * fast-vs-full Alpine benchmarks.
 */
public final class W0105FullValidationProbe extends Instrumentation {
    private static final String DEBIAN_TAR = "debian-13-rootfs.tar.xz";
    private static final String ALPINE_TAR = UmlKernelAssets.FastProfile.ROOTFS_TAR;
    private static final String ALPINE_SHA256 = UmlKernelAssets.FastProfile.pins().get(ALPINE_TAR);
    private static final long ALPINE_SIZE_BYTES = UmlKernelAssets.FastProfile.ROOTFS_TAR_SIZE_BYTES;
    private static final DistroManifest ALPINE_MANIFEST = new DistroManifest(
            "alpine",
            "3.22.2",
            "aarch64",
            "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/" + ALPINE_TAR,
            ALPINE_TAR,
            ALPINE_SHA256,
            ALPINE_SIZE_BYTES);

    private static final long DISK_QUOTA_8G_BYTES = 8L * 1024L * 1024L * 1024L;
    private static final String BASE_IMAGE_TAR_NAME = "base-image-arm64.tar";
    private static final String BASE_IMAGE_TAR_SHA256 =
            "fec83b8e84be131f34159e4cfdedfac3825c167a2dc42bd6a3e93d11d7368582";
    private static final String BASE_IMAGE_ID =
            "sha256:171e65262c8018f8f09a360006af2acbb2c0c494cb2be933025b466ea3d672dc";
    private static final int GUEST_CONTAINER_HOST_PORT = 18_088;
    private static final int GUEST_DEAD_PORT = 18_099;
    private static final long PROVISION_PROMPT_TIMEOUT_MS = 360_000L;
    private static final long REBOOT_PROMPT_TIMEOUT_MS = 120_000L;
    private static final long ALPINE_PROMPT_TIMEOUT_MS = 90_000L;
    private static final long SYSTEMD_ACTIVE_TIMEOUT_MS = 45_000L;
    private static final long BRIDGE_TIMEOUT_MS = 30_000L;
    private static final long STOP_GRACE_MS = 8_000L;
    private static final int MAX_TIMEOUT_TAIL_CHARS = 4_096;
    private static final Pattern ANSI_ESCAPE_PATTERN =
            Pattern.compile("\\u001B(?:\\[[0-9;?]*[A-Za-z]|\\][^\\u0007]*\\u0007)");

    private String stage = "init";
    private String action = "preflight";
    private String variant = "fast";
    private int iteration = 1;
    private int startIter = 1;
    private int pairIndex = 1;
    private int count = 30;
    private boolean warmup = true;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null) {
            action = arguments.getString("action", "preflight");
            variant = arguments.getString("variant", "fast");
            iteration = parsePositiveInt(arguments.getString("iteration", "1"), 1);
            startIter = parsePositiveInt(arguments.getString("start_iter", "1"), 1);
            pairIndex = parsePositiveInt(arguments.getString("pair_index", "1"), 1);
            count = parsePositiveInt(arguments.getString("count", "30"), 30);
            warmup = !"false".equalsIgnoreCase(arguments.getString("warmup", "true"));
        }
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        try {
            Context target = getTargetContext();
            require(
                    BuildConfig.IS_ISOLATED_QA_BUILD && target.getPackageName().endsWith(".qa.w0105"),
                    "w0105-build-guard");
            UmlInstanceLauncher.configureProcessBudget(target);
            switch (action) {
                case "preflight" -> runPreflight(target, result);
                case "normal_entry_check" -> runNormalEntryCheck(target, result);
                case "debian_full_chain" -> runDebianFullChain(target, result);
                case "fault_and_compat" -> runFaultAndCompat(target, result);
                case "debian_bench_prepare" -> runDebianBenchPrepare(target, result);
                case "debian_first_provision" -> runDebianFirstProvision(target);
                case "debian_cold_boot" -> runDebianBenchBoot(target, "cold_provisioned");
                case "debian_warm_restart" -> runDebianBenchBoot(target, "warm_restart");
                case "debian_warm_restart_batch" -> runDebianWarmRestartBatch(target, result);
                case "debian_warm_reconnect" -> runDebianWarmReconnect(target);
                case "alpine_paired_prepare" -> runAlpinePairedPrepare(target, result);
                case "alpine_paired_cold" -> runAlpinePairedCold(target);
                case "alpine_paired_warm_pair" -> runAlpinePairedWarmPair(target);
                case "alpine_paired_warm_batch" -> runAlpinePairedWarmBatch(target, result);
                case "alpine_paired_reconnect" -> runAlpinePairedReconnect(target);
                case "qemu_debian_ref" -> runQemuDebianRef(target, result);
                default -> throw new IllegalArgumentException("unknown action: " + action);
            }
            result.putString("w0105_status", "PASS");
            result.putString("w0105_stage", stage);
            code = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("w0105_status", "FAIL");
            result.putString("w0105_stage", stage);
            result.putString(
                    "w0105_error",
                    sanitizeDetail(failure.getClass().getSimpleName() + ": " + failure.getMessage()));
        }
        finish(code, result);
    }

    private void runPreflight(Context target, Bundle out) throws Exception {
        reportStage("preflight");
        ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight fullPre =
                StandaloneUmlPreflightProbe.forAndroid(
                        target, UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64).probe();
        require(
                fullPre.status() == ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.READY,
                "full preflight failed: " + fullPre.status() + " " + fullPre.detail());
        require(fullPre.hostPageSizeBytes() == 4096, "expected 4 KB host");

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
        require(fastDebianRefused, "FAST_UP_ARM64 must refuse Debian");

        Path filesDir = target.getFilesDir().toPath();
        Path debianTar = filesDir.resolve("distro-inputs").resolve(DEBIAN_TAR);
        require(Files.isRegularFile(debianTar), "missing Debian tarball");
        Path fixturesDir = filesDir.resolve("w0104-fixtures");
        Path baseImgTar = fixturesDir.resolve(BASE_IMAGE_TAR_NAME);
        require(Files.isRegularFile(fixturesDir.resolve("w0104-lock.json")), "missing lock");
        require(Files.isRegularFile(baseImgTar), "missing base image tar");
        require(Files.isRegularFile(fixturesDir.resolve("apt-repo/qa-w0104-archive.gpg")), "missing gpg");
        require(Files.isRegularFile(fixturesDir.resolve("apt-repo/dists/trixie/InRelease")), "missing InRelease");
        require(BASE_IMAGE_TAR_SHA256.equals(sha256File(baseImgTar)), "base image sha256 mismatch");

        out.putInt("host_page_size", fullPre.hostPageSizeBytes());
        out.putString("w0105_summary", "preflight_ok");
    }

    private void runNormalEntryCheck(Context target, Bundle out) throws Exception {
        reportStage("normal_entry_check");
        Path filesDir = target.getFilesDir().toPath();
        Path entryRoot = Files.createDirectories(filesDir.resolve("w0105-entry-check"));
        try {
            Path storeDir = entryRoot.resolve("instances");
            Path rootsDir = entryRoot.resolve("roots");
            InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
            InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
            IsolatedQaExecutionEntry qa = new IsolatedQaExecutionEntry();
            InstanceLifecycle.Instance inst = qa.ensurePersistentDebianInstance(store, controller, rootsDir);
            require("debian".equals(inst.distro().id()), "expected debian");
            require("13".equals(inst.distro().version()), "expected debian 13");
            require("full-smp-arm64".equals(inst.kernelProfile()), "expected full-smp-arm64");
            require(
                    inst.executionIdentity().strategy().strategy()
                            == FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                    "expected USER_MODE_KERNEL");

            // Confirm public release catalog remains FAIL-CLOSED.
            var catalog = UmlReleaseEvidenceCatalog.current();
            require(!catalog.phase1SignedOff(), "public catalog must remain fail-closed");
            require(!DesktopEntryPolicy.supportsDesktop(), "desktop entry must remain locked");
            out.putString("normal_entry_instance_id", inst.id());
            out.putString("w0105_summary", "normal_entry_qa_metadata_and_fail_closed_ok");
        } finally {
            deleteTreeBestEffort(entryRoot);
        }
    }

    private void runDebianFullChain(Context target, Bundle out) throws Exception {
        reportStage("full_chain_init");
        Path filesDir = target.getFilesDir().toPath();
        Path distroInputsDir = filesDir.resolve("distro-inputs");
        Path fixturesDir = filesDir.resolve("w0104-fixtures");
        Path probeRoot = filesDir.resolve("w0105-fullchain");
        deleteTreeBestEffort(probeRoot);
        Files.createDirectories(probeRoot);
        Path storeDir = probeRoot.resolve("instances");
        Path rootsDir = probeRoot.resolve("roots");
        Path runtimeRoot = Files.createDirectories(probeRoot.resolve("runtime"));

        try {
            InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
            InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
            IsolatedQaExecutionEntry qa = new IsolatedQaExecutionEntry();
            InstanceLifecycle.Instance debian = qa.ensurePersistentDebianInstance(
                    store,
                    controller,
                    rootsDir,
                    null,
                    IsolatedQaExecutionEntry.DEFAULT_DEBIAN_RAM_MB,
                    DISK_QUOTA_8G_BYTES,
                    IsolatedQaExecutionEntry.DEFAULT_DEBIAN_QA_HOSTNAME);

            int hostPort = pickFreeUserPort(debian.id());
            int deadHostPort = pickFreeUserPort(debian.id(), hostPort);
            PortForwardingManager fwdMgr =
                    PortForwardingManager.forInstance(debian.id(), filesDir.resolve("network"));
            clearRules(fwdMgr);
            try {
                String volSha = runFullChainBoot1(
                        target, store, controller, distroInputsDir, fixturesDir, runtimeRoot,
                        debian, fwdMgr, hostPort, deadHostPort, out);
                require(!isBound(hostPort), "hostPort bound after boot1");
                require(UmlInstanceLauncher.areLeasesReleased(debian.id(), debian.rootVolume()),
                        "leases leaked after boot1");

                InstanceLifecycle.Instance reloaded = store.load(debian.id());
                runFullChainBoot2(
                        target, store, controller, distroInputsDir, runtimeRoot,
                        reloaded, fwdMgr, hostPort, volSha, out);
                require(!isBound(hostPort), "hostPort bound after boot2");
                require(UmlInstanceLauncher.areLeasesReleased(debian.id(), debian.rootVolume()),
                        "leases leaked after boot2");
                assertZeroState(debian.id(), debian.rootVolume());
                out.putString("w0105_summary", "debian_docker_full_chain_and_persistence_ok");
            } finally {
                clearRules(fwdMgr);
            }
        } finally {
            deleteTreeBestEffort(probeRoot);
        }
    }

    private String runFullChainBoot1(
            Context target,
            InstanceLifecycle.FileStore store,
            InstanceLifecycle.Controller controller,
            Path distroInputsDir,
            Path fixturesDir,
            Path runtimeRoot,
            InstanceLifecycle.Instance inst,
            PortForwardingManager fwdMgr,
            int hostPort,
            int deadHostPort,
            Bundle out) throws Exception {
        String gen = InstanceLaunchGuard.begin(inst.id());
        require(gen != null, "boot1 launch guard busy");
        InstanceLaunchSequence.beforeLaunch(
                controller, inst, InstanceLifecycle.ConcurrencyPolicy.SINGLE_RUNNING_INSTANCE);
        OrchestellaForegroundService.start(
                target, inst.id(), "debian 13", "FULL_SMP_ARM64", BatteryPolicy.BALANCED, gen);
        Path instanceRuntimeDir = Files.createDirectories(runtimeRoot.resolve(inst.id()));
        ExecutionSession session = null;
        try {
            UmlInstanceLauncher launcher = new UmlInstanceLauncher(target, store);
            session = launcher.launch(paramsForDebian(inst, distroInputsDir, instanceRuntimeDir));
            UmlInstanceLauncher.Session umlSession = (UmlInstanceLauncher.Session) session;
            ExecutionSessionRegistry.LiveSession live = ExecutionSessionRegistry.getLiveSession(inst.id());
            require(live != null, "missing LiveSession");

            awaitPrompt(live, PROVISION_PROMPT_TIMEOUT_MS);
            BridgeClient bridge = authenticateBridge(session, inst.bridgeToken());
            awaitSystemdActive(live, SYSTEMD_ACTIVE_TIMEOUT_MS);
            umlSession.markShellReady();
            String firstEcho = runGuestCmd(live, "echo W0105_FIRST_ECHO", "__W0105_ECHO1__", 10_000L);
            require(firstEcho.contains("W0105_FIRST_ECHO"), "first cmd echo failed");

            long netDeadline = SystemClock.elapsedRealtime() + 10_000L;
            while (SystemClock.elapsedRealtime() < netDeadline
                    && umlSession.networkStatus() != UmlPasstNetwork.Status.READY) {
                SystemClock.sleep(100L);
            }
            out.putString("boot1_net_status", umlSession.networkStatus().name());
            out.putString("boot1_cred_outcome", umlSession.launchTimeline().credentialsOnlyOutcome().name());
            require(umlSession.launchTimeline().credentialsOnlyOutcome()
                    == LaunchTimeline.CredentialsOnlyOutcome.FIRST_PROVISION, "expected FIRST_PROVISION");

            // Check guest page size, nproc, kernel, and foreground lease.
            String sysCheck = runGuestCmd(
                    live,
                    "printf 'GPAGE=%s;NPROC=%s;UNAME=%s;PID1=%s\\n' "
                            + "\"$(getconf PAGESIZE)\" \"$(nproc)\" \"$(uname -r)\" \"$(cat /proc/1/comm)\"",
                    "__W0105_SYS__",
                    15_000L);
            out.putString("boot1_guest_sys", extractResultLine(sysCheck, "GPAGE="));
            require(sysCheck.contains("GPAGE=16384") && sysCheck.contains("PID1=systemd"),
                    "guest sys check failed: " + sysCheck);

            awaitForegroundLease(1, true, 5_000L);

            // Install 25 signed packages offline & test online apt-get update
            reportStage("full_chain_apt");
            String hostFixturesPath = fixturesDir.toAbsolutePath().toString();
            String mountRes = runGuestCmd(
                    live,
                    "mkdir -p /mnt/w0104-fixtures && (mount -t hostfs -o "
                            + shellQuote("hostfs=" + hostFixturesPath)
                            + " none /mnt/w0104-fixtures || LIBMOUNT_FORCE_MOUNT2=always mount -t hostfs -o "
                            + shellQuote(hostFixturesPath)
                            + " none /mnt/w0104-fixtures) && test -r /mnt/w0104-fixtures/w0104-lock.json "
                            + "&& printf '%s%s\\n' 'HOSTFS_' 'OK'",
                    "__W0105_MNT__",
                    15_000L);
            require(mountRes.contains("HOSTFS_OK"), "hostfs fixtures mount failed");

            String onlineAptRes = runGuestCmd(
                    live,
                    "timeout -k 5 120 apt-get update -o Acquire::ForceIPv4=true -o Acquire::Retries=2 -o Acquire::http::Timeout=15 -o Acquire::Languages=none </dev/null >/tmp/online-apt.log 2>&1 "
                            + "&& printf '%s%s\\n' 'ONLINE_APT_' 'OK' "
                            + "|| printf '%s%s=%s\\n' 'ONLINE_APT_' 'ERR' \"$(tail -n 2 /tmp/online-apt.log | tr '\\n' ' ')\"",
                    "__W0105_OAPT__",
                    135_000L);
            out.putString("boot1_online_apt",
                    onlineAptRes.contains("ONLINE_APT_OK")
                            ? "ONLINE_APT_OK"
                            : extractResultLine(onlineAptRes, "ONLINE_APT_ERR="));

            String offlineInstRes = runGuestCmd(
                    live,
                    "sh -ec '"
                            + "printf \"deb [signed-by=/mnt/w0104-fixtures/apt-repo/qa-w0104-archive.gpg] file:///mnt/w0104-fixtures/apt-repo trixie main\\n\" >/etc/apt/sources.list.d/w0104-offline.list; "
                            + "trap \"rm -f /etc/apt/sources.list.d/w0104-offline.list\" EXIT; "
                            + "apt-get update -o Dir::Etc::sourcelist=/etc/apt/sources.list.d/w0104-offline.list -o Dir::Etc::sourceparts=- "
                            + "-o Acquire::AllowInsecureRepositories=false -o Acquire::AllowDowngradeToInsecureRepositories=false -o APT::Get::AllowUnauthenticated=false </dev/null >/tmp/offline-apt.log 2>&1; "
                            + "DEBIAN_FRONTEND=noninteractive timeout -k 10 660 apt-get install -y --no-install-recommends "
                            + "-o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold "
                            + "-o Dir::Etc::sourcelist=/etc/apt/sources.list.d/w0104-offline.list -o Dir::Etc::sourceparts=- "
                            + "-o APT::Get::AllowUnauthenticated=false "
                            + "docker.io docker-cli containerd runc iptables nftables ca-certificates python3 </dev/null >>/tmp/offline-apt.log 2>&1"
                            + "' && printf '%s%s\\n' 'OFFLINE_INST_' 'OK' "
                            + "|| { printf '%s%s\\n' 'OFFLINE_INST_' 'FAIL'; tail -n 40 /tmp/offline-apt.log 2>/dev/null || :; }",
                    "__W0105_IAPT__",
                    700_000L);
            require(offlineInstRes.contains("OFFLINE_INST_OK"), "offline apt install failed: " + offlineInstRes);

            String closureRes = runGuestCmd(
                    live,
                    "python3 - <<'PY'\n"
                            + "import hashlib, json, pathlib, subprocess\n"
                            + "lock = json.loads(pathlib.Path('/mnt/w0104-fixtures/w0104-lock.json').read_text())\n"
                            + "for p in lock['packages']:\n"
                            + "    deb = pathlib.Path('/mnt/w0104-fixtures/apt-repo') / p['filename']\n"
                            + "    assert hashlib.sha256(deb.read_bytes()).hexdigest() == p['sha256'], p['name']\n"
                            + "    inst = subprocess.check_output(['dpkg-query', '-W', '-f=${Status}\\t${Version}', p['name']], text=True).strip()\n"
                            + "    assert inst == 'install ok installed\\t' + p['version'], (p['name'], inst)\n"
                            + "print('CLOSURE_' + 'VERIFIED=' + str(len(lock['packages'])))\n"
                            + "PY",
                    "__W0105_CLOSURE__",
                    30_000L);
            require(closureRes.contains("CLOSURE_VERIFIED=25"), "closure failed: " + closureRes);
            out.putString("boot1_offline_apt", "CLOSURE_VERIFIED=25");

            // CLI separation + runc + SysV IPC + docker info + build + run + DNS/TLS + bridge + port fwd
            reportStage("full_chain_docker");
            String dockerPrep = runGuestCmd(
                    live,
                    "sh -ec '"
                            + "systemctl stop docker.service docker.socket 2>/dev/null || :; "
                            + "docker --version && ! docker info >/dev/null 2>&1; "
                            + "systemctl start containerd.service docker.service; "
                            + "for i in $(seq 1 30); do docker info >/dev/null 2>&1 && break; sleep 1; done; "
                            + "docker info -f \"VER={{.ServerVersion}};DRV={{.Driver}};CG={{.CgroupVersion}}:{{.CgroupDriver}};RT={{.DefaultRuntime}};SEC={{join .SecurityOptions \\\"|\\\"}}" + "\"'"
                            + " | tee /tmp/dinfo.txt && grep -q 'DRV=overlay2' /tmp/dinfo.txt && grep -q 'CG=2:systemd' /tmp/dinfo.txt",
                    "__W0105_DINFO__",
                    90_000L);
            require(dockerPrep.contains("DRV=overlay2"), "docker info failed: " + dockerPrep);
            out.putString("boot1_docker_info", extractResultLine(dockerPrep, "VER="));

            String ociIpc = runGuestCmd(
                    live,
                    "python3 - <<'PY'\n"
                            + "import json, pathlib, shutil, subprocess, tarfile\n"
                            + "base = pathlib.Path('/tmp/w0105-oci')\n"
                            + "shutil.rmtree(base, ignore_errors=True)\n"
                            + "(base / 'rootfs').mkdir(parents=True)\n"
                            + "with tarfile.open('/mnt/w0104-fixtures/base-image-arm64.tar', 'r') as tf:\n"
                            + "    m = json.loads(tf.extractfile('manifest.json').read())\n"
                            + "    with tarfile.open(fileobj=tf.extractfile(m[0]['Layers'][0]), mode='r:*') as lf:\n"
                            + "        lf.extractall(base / 'rootfs')\n"
                            + "subprocess.check_call(['runc', 'spec'], cwd=base)\n"
                            + "cfg = json.loads((base / 'config.json').read_text())\n"
                            + "cfg['process']['terminal'] = False\n"
                            + "cfg['process']['args'] = ['/bin/sh', '-ec', 'printf \"OCI_OK;PID=%s;CG=%s;SEC=%s\\n\" \"$$\" \"$(stat -fc %t /sys/fs/cgroup)\" \"$(grep ^Seccomp: /proc/self/status | tr -dc 0-9)\"']\n"
                            + "cfg['linux']['seccomp'] = {'defaultAction': 'SCMP_ACT_ALLOW', 'architectures': ['SCMP_ARCH_AARCH64'], 'syscalls': [{'names': ['kcmp'], 'action': 'SCMP_ACT_ERRNO'}]}\n"
                            + "(base / 'config.json').write_text(json.dumps(cfg))\n"
                            + "out = subprocess.check_output(['runc', 'run', 'w0105-oci-min'], cwd=base, text=True).strip()\n"
                            + "assert 'OCI_OK;PID=1;CG=63677270;SEC=2' in out, out\n"
                            + "shutil.rmtree(base, ignore_errors=True)\n"
                            + "print('OCI_IPC_' + 'PASS')\n"
                            + "PY",
                    "__W0105_OCI__",
                    60_000L);
            require(ociIpc.contains("OCI_IPC_PASS"), "runc minimal OCI failed: " + ociIpc);

            String buildAndNet = runGuestCmd(
                    live,
                    "sh -ec '"
                            + "docker load -i /mnt/w0104-fixtures/base-image-arm64.tar >/tmp/dload.log; "
                            + "BASE_ID=$(docker image inspect alpine:3.22.2 -f \"{{.Id}}\"); "
                            + "test \"$BASE_ID\" = \"" + BASE_IMAGE_ID + "\"; "
                            + "mkdir -p /tmp/w0105-build && cd /tmp/w0105-build; "
                            + "cat >handle.sh <<\"SH\"\n"
                            + "#!/bin/sh\n"
                            + "read _req || true\n"
                            + "VOL=$(cat /data/persist.txt 2>/dev/null || echo missing)\n"
                            + "BODY=\"APP=w0105;ARCH=$(uname -m);VOL=${VOL}\"\n"
                            + "printf \"HTTP/1.0 200 OK\\r\\nContent-Length: %d\\r\\n\\r\\n%s\" \"${#BODY}\" \"$BODY\"\n"
                            + "SH\n"
                            + "chmod +x handle.sh; "
                            + "cat >Dockerfile <<\"DF\"\n"
                            + "FROM alpine:3.22.2\n"
                            + "RUN mkdir -p /app && printf \"BUILD_ARM64_RUN_OK\\n\" >/app/build.txt\n"
                            + "COPY handle.sh /app/handle.sh\n"
                            + "CMD [\"nc\", \"-ll\", \"-p\", \"8080\", \"-e\", \"/app/handle.sh\"]\n"
                            + "DF\n"
                            + "docker build --pull=false --platform linux/arm64 -t orchestella-w0105-app:v1 . >/tmp/dbuild.log; "
                            + "docker network create w0105-net >/dev/null 2>&1 || :; "
                            + "docker volume create w0105-data >/dev/null 2>&1 || :; "
                            + "docker rm -f w0105-srv >/dev/null 2>&1 || :; "
                            + "docker run -d --name w0105-srv --network w0105-net --memory=128m --pids-limit=64 "
                            + "-v w0105-data:/data -p 127.0.0.1:" + GUEST_CONTAINER_HOST_PORT + ":8080 orchestella-w0105-app:v1 >/dev/null; "
                            + "docker exec w0105-srv sh -ec \"printf 'w0105-volume-payload-gen1' >/data/persist.txt && sync\"; "
                            + "VSHA=$(docker exec w0105-srv sha256sum /data/persist.txt | cut -d\" \" -f1); "
                            + "BRIDGE_OUT=$(docker run --rm --network w0105-net alpine:3.22.2 wget -qO- http://w0105-srv:8080); "
                            + "echo \"$BRIDGE_OUT\" | grep -q \"APP=w0105\"; "
                            + "TLS_RES=$(docker run --rm alpine:3.22.2 sh -ec \"nslookup deb.debian.org >/dev/null && wget -q --spider https://deb.debian.org/debian/dists/trixie/Release && echo ONLINE || echo OFFLINE\"); "
                            + "printf \"%s%s;VSHA=%s;TLS=%s\\n\" \"WORKLOAD_\" \"OK\" \"$VSHA\" \"$TLS_RES\"'",
                    "__W0105_WORKLOAD__",
                    150_000L);
            String wlLine = extractResultLine(buildAndNet, "WORKLOAD_OK;");
            require(!wlLine.isEmpty(), "workload failed: " + buildAndNet);
            String volSha = extractField(wlLine, "VSHA=");
            require(!volSha.isEmpty(), "missing VSHA");
            out.putString("boot1_vol_sha256", volSha);
            out.putString("container_dns_tls", extractField(wlLine, "TLS="));

            String ctrFailRes = runGuestCmd(
                    live,
                    "sh -ec '"
                            + "RC=0; docker run --rm --name w0105-bad-ctr alpine:3.22.2 /nonexistent-w0105-cmd >/tmp/dbad.log 2>&1 || RC=$?; "
                            + "test \"$RC\" -ne 0; "
                            + "test -z \"$(docker ps -a -f name=w0105-bad-ctr -q)\"; "
                            + "ALIVE=$(docker inspect -f \"{{.State.Running}}\" w0105-srv); "
                            + "test \"$ALIVE\" = \"true\"; "
                            + "printf \"%s%s;RC=%s;SRV=%s\\n\" \"CTR_FAIL_\" \"EXPECTED\" \"$RC\" \"$ALIVE\"'",
                    "__W0105_CFAIL__",
                    45_000L);
            String ctrFailLine = extractResultLine(ctrFailRes, "CTR_FAIL_EXPECTED;");
            require(!ctrFailLine.isEmpty(), "container failure check failed: " + ctrFailRes);
            out.putString("boot1_ctr_fail_check", ctrFailLine);

            // Android explicit loopback port forwarding + conflict + dead port
            reportStage("full_chain_port_fwd");
            PortForwardingPresenter presenter = PortForwardingPresenter.forActiveSession(inst.id(), fwdMgr);
            presenter.addRule(Integer.toString(hostPort), Integer.toString(GUEST_CONTAINER_HOST_PORT), Protocol.TCP, "w0105-app");
            String http1 = readHttp(hostPort);
            require(http1.contains("APP=w0105") && http1.contains("VOL=w0105-volume-payload-gen1"),
                    "Android HTTP failed: " + http1);
            out.putString("boot1_android_http", http1.replace("\r\n", "|"));

            // Foreground/Background hold & live PTY detach/reattach/resize
            reportStage("full_chain_bg_reattach");
            long upBefore = readGuestUptimeMs(live);
            SystemClock.sleep(1_500L);
            OrchestellaForegroundService.LeaseSnapshot snap = OrchestellaForegroundService.leaseSnapshot();
            require(snap.activeCount() >= 1 && snap.wakeLockHeld(), "foreground lease/wakelock lost");
            long upAfter = readGuestUptimeMs(live);
            require(upAfter > upBefore, "guest uptime stalled");
            require(bridge.ping().accepted(), "bridge ping failed during keepalive");

            live.setOutputListener(null);
            String bgOut = runGuestCmd(live, "echo W0105_REATTACH_LIVE", "__W0105_REA__", 10_000L);
            require(bgOut.contains("W0105_REATTACH_LIVE"), "reattach live echo failed");
            require(bridge.resize(104, 32).accepted(), "bridge.resize failed");
            require(umlSession.resizeTerminalHost(120, 36), "resizeTerminalHost failed");
            String ttySize = runGuestCmd(live, "printf 'TTY=%s\\n' \"$(stty size </dev/tty0)\"", "__W0105_TTY__", 10_000L);
            require(ttySize.contains("TTY=36 120"), "tty resize mismatch: " + ttySize);

            // Dead port clean failure
            presenter.addRule(Integer.toString(deadHostPort), Integer.toString(GUEST_DEAD_PORT), Protocol.TCP, "w0105-dead");
            boolean deadFailed = false;
            try {
                String resp = readHttp(deadHostPort);
                deadFailed = !resp.contains("200 OK");
            } catch (IOException expected) {
                deadFailed = true;
            }
            require(deadFailed, "dead port unexpectedly succeeded");

            // Poweroff + confirmed stop + zero temp residue
            reportStage("full_chain_boot1_poweroff");
            live.sendBytes("systemctl poweroff\n".getBytes(StandardCharsets.UTF_8));
            require(umlSession.awaitPowerDownOrExit(90_000L), "boot1 poweroff timeout");
            RuntimeProcessSupervisor.StopResult stopRes = ((RuntimeOwnedSession) session).stopRuntime(STOP_GRACE_MS);
            require(stopRes == RuntimeProcessSupervisor.StopResult.CONFIRMED
                    || stopRes == RuntimeProcessSupervisor.StopResult.ALREADY_STOPPED, "boot1 stop unconfirmed");
            out.putString("boot1_stop_result", stopRes.name());
            ExecutionSessionRegistry.unregister(inst.id(), session);
            session = null;
            verifyNoTempResidue(inst.rootVolume().getParent(), instanceRuntimeDir);
            return volSha;
        } finally {
            cleanupSessionSafely(session, store, controller, inst.id(), gen, target);
        }
    }

    private void runFullChainBoot2(
            Context target,
            InstanceLifecycle.FileStore store,
            InstanceLifecycle.Controller controller,
            Path distroInputsDir,
            Path runtimeRoot,
            InstanceLifecycle.Instance inst,
            PortForwardingManager fwdMgr,
            int hostPort,
            String expectedVolSha,
            Bundle out) throws Exception {
        reportStage("full_chain_boot2");
        String gen = InstanceLaunchGuard.begin(inst.id());
        require(gen != null, "boot2 guard busy");
        InstanceLaunchSequence.beforeLaunch(
                controller, inst, InstanceLifecycle.ConcurrencyPolicy.SINGLE_RUNNING_INSTANCE);
        OrchestellaForegroundService.start(
                target, inst.id(), "debian 13", "FULL_SMP_ARM64", BatteryPolicy.BALANCED, gen);
        Path instanceRuntimeDir = Files.createDirectories(runtimeRoot.resolve(inst.id()));
        ExecutionSession session = null;
        try {
            UmlInstanceLauncher launcher = new UmlInstanceLauncher(target, store);
            session = launcher.launch(paramsForDebian(inst, distroInputsDir, instanceRuntimeDir));
            UmlInstanceLauncher.Session umlSession = (UmlInstanceLauncher.Session) session;
            ExecutionSessionRegistry.LiveSession live = ExecutionSessionRegistry.getLiveSession(inst.id());
            require(live != null, "boot2 missing LiveSession");

            awaitPrompt(live, REBOOT_PROMPT_TIMEOUT_MS);
            authenticateBridge(session, inst.bridgeToken());
            awaitSystemdActive(live, SYSTEMD_ACTIVE_TIMEOUT_MS);
            umlSession.markShellReady();
            require(umlSession.launchTimeline().credentialsOnlyOutcome()
                    == LaunchTimeline.CredentialsOnlyOutcome.HIT, "expected HIT on boot2");
            out.putString("boot2_cred_outcome", "HIT");

            String persistRes = runGuestCmd(
                    live,
                    "sh -ec '"
                            + "test \"$(cat /proc/1/comm)\" = systemd; "
                            + "test \"$(systemctl is-active docker.service)\" = active; "
                            + "docker rm -f w0105-srv >/dev/null 2>&1 || :; "
                            + "docker run -d --name w0105-srv2 --network w0105-net --memory=128m --pids-limit=64 "
                            + "-v w0105-data:/data -p 127.0.0.1:" + GUEST_CONTAINER_HOST_PORT + ":8080 orchestella-w0105-app:v1 >/dev/null; "
                            + "VSHA2=$(docker exec w0105-srv2 sha256sum /data/persist.txt | cut -d\" \" -f1); "
                            + "printf \"%s%s%s\\n\" \"BOOT2_\" \"VSHA=\" \"$VSHA2\"'",
                    "__W0105_BOOT2__",
                    90_000L);
            require(persistRes.contains("BOOT2_VSHA=" + expectedVolSha), "boot2 vol mismatch: " + persistRes);
            String http2 = readHttp(hostPort);
            require(http2.contains("VOL=w0105-volume-payload-gen1"), "boot2 android http failed: " + http2);
            out.putString("boot2_android_http", http2.replace("\r\n", "|"));

            PortForwardingPresenter presenter = PortForwardingPresenter.forActiveSession(inst.id(), fwdMgr);
            for (PortForwardingRule r : List.copyOf(fwdMgr.listRules())) {
                presenter.deleteRule(r.id());
            }
            require(!isBound(hostPort), "hostPort bound after delete");

            StructStat st = Os.stat(inst.rootVolume().toString());
            out.putLong("ext4_logical_bytes", Files.size(inst.rootVolume()));
            out.putLong("ext4_allocated_bytes", st.st_blocks * 512L);

            session.terminate();
            RuntimeProcessSupervisor.StopResult stopRes = ((RuntimeOwnedSession) session).stopRuntime(STOP_GRACE_MS);
            require(stopRes == RuntimeProcessSupervisor.StopResult.CONFIRMED
                    || stopRes == RuntimeProcessSupervisor.StopResult.ALREADY_STOPPED, "boot2 stop unconfirmed");
            out.putString("boot2_stop_result", stopRes.name());
            ExecutionSessionRegistry.unregister(inst.id(), session);
            session = null;
            verifyNoTempResidue(inst.rootVolume().getParent(), instanceRuntimeDir);
        } finally {
            cleanupSessionSafely(session, store, controller, inst.id(), gen, target);
        }
    }

    private void runFaultAndCompat(Context target, Bundle out) throws Exception {
        reportStage("fault_and_compat_init");
        Path filesDir = target.getFilesDir().toPath();
        Path probeRoot = filesDir.resolve("w0105-faults");
        deleteTreeBestEffort(probeRoot);
        Files.createDirectories(probeRoot);
        try {
            // 1. Legacy Schema 0 zero-write read & SAF read-only export/import copy
            reportStage("fault_legacy_schema_and_saf");
            Path schemaDir = Files.createDirectories(probeRoot.resolve("schema0-instances"));
            String legacyId = UUID.randomUUID().toString();
            Path legacyProps = schemaDir.resolve(legacyId + ".properties");
            Path legacyVol = probeRoot.resolve("legacy-schema0.raw");
            Files.write(legacyVol, "LEGACY_RAW_VOL_OK".getBytes(StandardCharsets.UTF_8));
            String legacyContent = "id=" + legacyId + "\n"
                    + "distroId=alpine\n"
                    + "distroVersion=3.22.2\n"
                    + "rootVolume=" + legacyVol.toAbsolutePath() + "\n"
                    + "bridgeToken=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n"
                    + "runtimeClass=FULL_LINUX\n"
                    + "state=STOPPED\n"
                    + "createdAtMillis=1700000000000\n"
                    + "updatedAtMillis=1700000000000\n"
                    + "hostname=legacy-alpine\n"
                    + "defaultUser=root\n"
                    + "hasSudo=false\n"
                    + "custom.futureKey=preserve-me\n";
            Files.write(legacyProps, legacyContent.getBytes(StandardCharsets.UTF_8));
            String beforeMetaSha = sha256File(legacyProps);
            InstanceLifecycle.FileStore schemaStore = new InstanceLifecycle.FileStore(schemaDir);
            InstanceLifecycle.Instance loadedLegacy = schemaStore.load(legacyId);
            require(
                    loadedLegacy.executionIdentity().strategy().isAbsent(),
                    "expected ABSENT_LEGACY");
            require(loadedLegacy.kernelProfile() == null, "expected null kernelProfile on schema 0");
            require(beforeMetaSha.equals(sha256File(legacyProps)), "schema 0 read mutated disk file");

            // SAF export/import round-trip to an isolated test copy
            Path safStoreDir = Files.createDirectories(probeRoot.resolve("saf-instances"));
            Path safRootsDir = Files.createDirectories(probeRoot.resolve("saf-roots"));
            InstanceLifecycle.FileStore safStore = new InstanceLifecycle.FileStore(safStoreDir);
            InstanceLifecycle.Controller safCtrl = new InstanceLifecycle.Controller(safStore, Clock.systemUTC());
            IsolatedQaExecutionEntry qa = new IsolatedQaExecutionEntry();
            InstanceLifecycle.Instance srcInst = qa.ensurePersistentDebianInstance(safStore, safCtrl, safRootsDir);
            Files.write(srcInst.rootVolume(), "DEBIAN_SAF_PAYLOAD_V1".getBytes(StandardCharsets.UTF_8));
            String srcVolSha = sha256File(srcInst.rootVolume());
            Path copyVol = safRootsDir.resolve("imported-copy.raw");
            InstanceLifecycle.Instance importedCopy =
                    qa.roundTripViaArchive(safCtrl, srcInst.id(), copyVol, null);
            require("full-smp-arm64".equals(importedCopy.kernelProfile()), "SAF lost kernelProfile");
            require(srcVolSha.equals(sha256File(copyVol)), "SAF imported root volume sha mismatch");
            require(srcVolSha.equals(sha256File(srcInst.rootVolume())), "SAF export mutated source");

            // 2. Provisioning & strategy rejections (corrupt xz, wrong sha, foreign volume, FAST_UP+Debian)
            reportStage("fault_provision_rejections");
            runProvisionRejectionChecks(target, probeRoot);

            // 3. Unconfirmed stop retains reservation & slot; confirmed retry releases all leases
            reportStage("fault_unconfirmed_stop_lease");
            runUnconfirmedStopLeaseCheck(probeRoot);

            // 4. Guest ENOSPC + bind-mount fail + stopped-instance reuse + duplicate-launch refusal + port conflict
            reportStage("fault_runtime_contention");
            runRuntimeFaultAndReuseChecks(target, probeRoot);

            // 5. Old Alpine FAST_UP_ARM64 & QEMU Alpine compatibility regression on same candidate APK
            reportStage("compat_alpine_uml");
            runAlpineFastRegression(target, probeRoot);

            reportStage("compat_qemu_alpine");
            runQemuAlpineRegression(target, probeRoot);

            out.putString("w0105_summary", "fault_saf_lease_and_compat_regressions_ok");
        } finally {
            deleteTreeBestEffort(probeRoot);
        }
    }

    private void runProvisionRejectionChecks(Context target, Path probeRoot) throws Exception {
        Path faultDir = Files.createDirectories(probeRoot.resolve("prov-reject"));
        Path runtimeDir = Files.createDirectories(faultDir.resolve("runtime"));
        UmlRootReservationRegistry registry = UmlRootReservationRegistry.defaultRegistry();
        UmlInstanceRootfsProvisioner.ToolchainStager stager = stageDir -> {
            try (InputStream initramfs = PackagedAssetStreams.openResource(target, "initramfs.cpio.gz")) {
                require(initramfs != null, "initramfs missing");
                CpioNewcToolExtractor.extractSelected(
                        initramfs,
                        stageDir,
                        Set.of("sbin/mkfs.ext4", "etc/mke2fs.conf", "bin/busybox", "opt/droidlayer/guest-agent"),
                        Set.of("sbin/mkfs.ext4", "bin/busybox", "opt/droidlayer/guest-agent"));
            }
        };
        UmlInstanceRootfsProvisioner prov = new UmlInstanceRootfsProvisioner(
                new UmlNativePtyBridge(), registry, stager,
                new StorageContract.DefaultStorageSpaceChecker(), Clock.systemUTC());
        String kernelFull = NativeExecutableStore.ensure(target, "libuml_kernel_full.so");
        String stubFull = NativeExecutableStore.ensure(target, "libuml_stub_full.so");

        Path corruptXz = faultDir.resolve("corrupt.tar.xz");
        byte[] corruptBytes = new byte[512];
        Arrays.fill(corruptBytes, (byte) 0x55);
        corruptBytes[0] = (byte) 0xfd;
        corruptBytes[1] = 0x37;
        corruptBytes[2] = 0x7a;
        corruptBytes[3] = 0x58;
        corruptBytes[4] = 0x5a;
        corruptBytes[5] = 0x00;
        Files.write(corruptXz, corruptBytes);
        String corruptSha = sha256Bytes(corruptBytes);

        Path corruptVol = faultDir.resolve("corrupt.raw");
        boolean corruptRejected = false;
        try {
            prov.provision(new UmlInstanceRootfsProvisioner.ProvisionRequest(
                    "w0105-corrupt", "g1", corruptVol, StorageContract.MIN_ROOT_VOLUME_BYTES, "qa-uml",
                    new UmlInstanceRootfsProvisioner.SeedInput("debian", "13", corruptXz, null, null, corruptSha, corruptBytes.length),
                    UmlInstanceRootfsProvisioner.ProvisionMode.CREATE_NEW, null, kernelFull, stubFull, runtimeDir,
                    new Object(), (id, gen) -> true, null, 60_000L, 2_000L, 2_000L, 2_000L,
                    UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64));
        } catch (UmlInstanceRootfsProvisioner.ProvisionException expected) {
            corruptRejected = true;
        }
        require(corruptRejected && Files.notExists(corruptVol) && !registry.isReserved(corruptVol),
                "corrupt xz check failed");

        Path foreignVol = faultDir.resolve("foreign.raw");
        Files.write(foreignVol, "KEEP_FOREIGN_VOLUME".getBytes(StandardCharsets.UTF_8));
        String foreignBefore = sha256File(foreignVol);
        boolean foreignRejected = false;
        try {
            prov.provision(new UmlInstanceRootfsProvisioner.ProvisionRequest(
                    "w0105-foreign", "g2", foreignVol, StorageContract.MIN_ROOT_VOLUME_BYTES, "qa-uml",
                    new UmlInstanceRootfsProvisioner.SeedInput("debian", "13", corruptXz, null, null, corruptSha, corruptBytes.length),
                    UmlInstanceRootfsProvisioner.ProvisionMode.CREATE_NEW, null, kernelFull, stubFull, runtimeDir,
                    new Object(), (id, gen) -> true, null, 15_000L, 2_000L, 2_000L, 2_000L,
                    UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64));
        } catch (UmlInstanceRootfsProvisioner.ProvisionException expected) {
            foreignRejected = UmlInstanceRootfsProvisioner.REASON_EXISTING_VOLUME_REQUIRES_EXPLICIT_MIGRATION
                    .equals(expected.reason());
        }
        require(foreignRejected && foreignBefore.equals(sha256File(foreignVol)), "foreign volume check failed");

        // Wrong SHA-256 must fail closed with REASON_CHECKSUM_MISMATCH
        Path wrongShaVol = faultDir.resolve("wrongsha.raw");
        boolean wrongShaRejected = false;
        try {
            prov.provision(new UmlInstanceRootfsProvisioner.ProvisionRequest(
                    "w0105-wrongsha", "g3", wrongShaVol, StorageContract.MIN_ROOT_VOLUME_BYTES, "qa-uml",
                    new UmlInstanceRootfsProvisioner.SeedInput("debian", "13", corruptXz, null, null,
                            "0000000000000000000000000000000000000000000000000000000000000000", corruptBytes.length),
                    UmlInstanceRootfsProvisioner.ProvisionMode.CREATE_NEW, null, kernelFull, stubFull, runtimeDir,
                    new Object(), (id, gen) -> true, null, 60_000L, 2_000L, 2_000L, 2_000L,
                    UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64));
        } catch (UmlInstanceRootfsProvisioner.ProvisionException expected) {
            wrongShaRejected = UmlInstanceRootfsProvisioner.REASON_CHECKSUM_MISMATCH.equals(expected.reason());
        }
        require(wrongShaRejected && Files.notExists(wrongShaVol) && !registry.isReserved(wrongShaVol),
                "wrong sha check failed");

        // Host insufficient space must fail closed before writing .part or root volume
        UmlInstanceRootfsProvisioner lowSpaceProv = new UmlInstanceRootfsProvisioner(
                new UmlNativePtyBridge(), registry, stager, path -> 1024L, Clock.systemUTC());
        Path lowSpaceVol = faultDir.resolve("lowspace.raw");
        boolean lowSpaceRejected = false;
        try {
            lowSpaceProv.provision(new UmlInstanceRootfsProvisioner.ProvisionRequest(
                    "w0105-lowspace", "g4", lowSpaceVol, StorageContract.MIN_ROOT_VOLUME_BYTES, "qa-uml",
                    new UmlInstanceRootfsProvisioner.SeedInput("debian", "13", corruptXz, null, null,
                            corruptSha, corruptBytes.length),
                    UmlInstanceRootfsProvisioner.ProvisionMode.CREATE_NEW, null, kernelFull, stubFull, runtimeDir,
                    new Object(), (id, gen) -> true, null, 60_000L, 2_000L, 2_000L, 2_000L,
                    UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64));
        } catch (StorageContract.InsufficientStorageException expected) {
            lowSpaceRejected = expected.getMessage() != null
                    && expected.getMessage().contains("Insufficient host storage space");
        }
        require(lowSpaceRejected && Files.notExists(lowSpaceVol) && !registry.isReserved(lowSpaceVol),
                "host insufficient space check failed");

        // FAST_UP_ARM64 + Debian must be refused at provisioner gate
        Path fastDebVol = faultDir.resolve("fastdeb.raw");
        boolean fastDebRejected = false;
        try {
            prov.provision(new UmlInstanceRootfsProvisioner.ProvisionRequest(
                    "w0105-fastdeb", "g5", fastDebVol, StorageContract.MIN_ROOT_VOLUME_BYTES, "qa-uml",
                    new UmlInstanceRootfsProvisioner.SeedInput("debian", "13", corruptXz, null, null,
                            corruptSha, corruptBytes.length),
                    UmlInstanceRootfsProvisioner.ProvisionMode.CREATE_NEW, null, kernelFull, stubFull, runtimeDir,
                    new Object(), (id, gen) -> true, null, 60_000L, 2_000L, 2_000L, 2_000L,
                    UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64));
        } catch (UmlInstanceRootfsProvisioner.ProvisionException expected) {
            fastDebRejected = UmlInstanceRootfsProvisioner.REASON_DISTRO_REFUSED.equals(expected.reason());
        }
        require(fastDebRejected && Files.notExists(fastDebVol) && !registry.isReserved(fastDebVol),
                "fast_up + debian provision check failed");
    }

    private void runUnconfirmedStopLeaseCheck(Path probeRoot) throws Exception {
        Path resRoot = Files.createDirectories(probeRoot.resolve("unconfirmed-lease"));
        Path vol = Files.createFile(resRoot.resolve("unconf.raw"));
        UmlRootReservationRegistry registry = UmlRootReservationRegistry.defaultRegistry();
        UmlRootReservationRegistry.Reservation reservation =
                registry.acquire(vol, "w0105-unconf", "gen-unconf");
        AtomicBoolean alive = new AtomicBoolean(true);
        UmlProcessHandle fakeHandle = new UmlProcessHandle() {
            @Override public long pid() { return 999_999L; }
            @Override public boolean isAlive() { return alive.get(); }
            @Override public int waitFor() { return 0; }
            @Override public boolean waitFor(long timeout, TimeUnit unit) { return !alive.get(); }
            @Override public void destroy() { }
            @Override public void destroyForcibly() { }
            @Override public OutputStream terminalInput() { return OutputStream.nullOutputStream(); }
            @Override public InputStream terminalOutput() { return InputStream.nullInputStream(); }
            @Override public OutputStream controlInput() { return OutputStream.nullOutputStream(); }
            @Override public InputStream controlOutput() { return InputStream.nullInputStream(); }
            @Override public boolean resizeTerminal(int c, int r) { return false; }
            @Override public void closeChannels() { }
        };
        UmlRuntimeSupervisor supervisor = new UmlRuntimeSupervisor(
                fakeHandle, () -> { }, () -> { }, registry, reservation, 50L, 50L, null);
        UmlRuntimeSupervisor.StopEvidence unconf = supervisor.stop(50L);
        require(unconf.result() == UmlRuntimeSupervisor.StopResult.UNCONFIRMED, "expected UNCONFIRMED");
        require(!unconf.rootReleased() && registry.isReserved(vol),
                "reservation must be retained on UNCONFIRMED");
        boolean reacquireBlocked = false;
        try {
            registry.acquire(vol, "w0105-unconf", "gen-retry");
        } catch (IOException expected) {
            reacquireBlocked = true;
        }
        require(reacquireBlocked, "reacquire should be blocked while UNCONFIRMED");

        // Now settle process death and verify confirmed retry releases reservation cleanly.
        alive.set(false);
        UmlRuntimeSupervisor.StopEvidence confirmed = supervisor.stop(50L);
        require(confirmed.result() == UmlRuntimeSupervisor.StopResult.CONFIRMED
                && confirmed.rootReleased()
                && !registry.isReserved(vol),
                "confirmed retry did not release reservation");
    }

    private void runRuntimeFaultAndReuseChecks(Context target, Path probeRoot) throws Exception {
        Path filesDir = target.getFilesDir().toPath();
        Path distroInputsDir = filesDir.resolve("distro-inputs");
        Path storeDir = Files.createDirectories(probeRoot.resolve("rt-instances"));
        Path rootsDir = Files.createDirectories(probeRoot.resolve("rt-roots"));
        Path runtimeRoot = Files.createDirectories(probeRoot.resolve("rt-runtime"));

        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        IsolatedQaExecutionEntry qa = new IsolatedQaExecutionEntry();
        InstanceLifecycle.Instance debian = qa.ensurePersistentDebianInstance(store, controller, rootsDir);

        String gen1 = InstanceLaunchGuard.begin(debian.id());
        require(gen1 != null, "guard busy");
        InstanceLaunchSequence.beforeLaunch(
                controller, debian, InstanceLifecycle.ConcurrencyPolicy.SINGLE_RUNNING_INSTANCE);
        Path rtDir = Files.createDirectories(runtimeRoot.resolve(debian.id()));
        ExecutionSession session = null;
        try {
            UmlInstanceLauncher launcher = new UmlInstanceLauncher(target, store);
            session = launcher.launch(paramsForDebian(debian, distroInputsDir, rtDir));
            ExecutionSessionRegistry.LiveSession live = ExecutionSessionRegistry.getLiveSession(debian.id());
            require(live != null, "missing live session");
            awaitPrompt(live, PROVISION_PROMPT_TIMEOUT_MS);
            authenticateBridge(session, debian.bridgeToken());
            awaitSystemdActive(live, SYSTEMD_ACTIVE_TIMEOUT_MS);
            ((UmlInstanceLauncher.Session) session).markShellReady();

            // Duplicate launch while running must fail closed (both same-root reservation & instance budget)
            boolean sameRootBlocked = false;
            try {
                launcher.launch(paramsForDebian(debian, distroInputsDir, rtDir));
            } catch (UmlRootReservationRegistry.RootVolumeBusyException expected) {
                sameRootBlocked = true;
            }
            require(sameRootBlocked, "duplicate same-root launch was not rejected");

            Path altDupRoot = Files.createFile(probeRoot.resolve("dup-alt.raw"));
            try {
                InstanceStrategyLauncher.LaunchParameters dupAltParams =
                        new InstanceStrategyLauncher.LaunchParameters(
                                debian.id(), debian.bridgeToken(), DEBIAN_TAR, altDupRoot.toString(),
                                rtDir.toString(), distroInputsDir.toString(), debian.guestPassword(),
                                debian.hardwareConfig(), null, null, null, debian.hostname(),
                                debian.defaultUser(), debian.hasSudo(), null, debian.kernelProfile());
                boolean budgetDupBlocked = false;
                try {
                    launcher.launch(dupAltParams);
                } catch (UmlInstanceBudget.BudgetRefusalException expected) {
                    budgetDupBlocked = expected.reason() == UmlInstanceBudget.RefusalReason.INSTANCE_ALREADY_ACTIVE;
                }
                require(budgetDupBlocked, "duplicate instance-id launch was not rejected by budget");
                require(!UmlRootReservationRegistry.defaultRegistry().isReserved(altDupRoot),
                        "alt dup root reservation leaked");
            } finally {
                Files.deleteIfExists(altDupRoot);
            }

            // Loopback port conflict must fail closed
            int conflictPort = pickFreeUserPort(debian.id());
            LoopbackEndpointRegistry.EndpointLease lease = LoopbackEndpointRegistry.defaultRegistry()
                    .acquire("127.0.0.1", conflictPort, debian.id(), gen1, "rule-1");
            try {
                boolean portRejected = false;
                try {
                    LoopbackEndpointRegistry.defaultRegistry()
                            .acquire("127.0.0.1", conflictPort, debian.id(), "other-gen", "rule-2");
                } catch (IOException expected) {
                    portRejected = true;
                }
                require(portRejected, "port collision not rejected");
            } finally {
                LoopbackEndpointRegistry.defaultRegistry().release(lease);
            }

            // Guest ENOSPC check without corrupting marker
            String enospcRes = runGuestCmd(
                    live,
                    "mkdir -p /tmp/w0105-lowdisk && mount -t tmpfs -o size=2M tmpfs /tmp/w0105-lowdisk; "
                            + "dd if=/dev/zero of=/tmp/w0105-lowdisk/fill bs=1M count=4 >/tmp/w0105-lowdisk.log 2>&1 || true; "
                            + "umount /tmp/w0105-lowdisk && rmdir /tmp/w0105-lowdisk; "
                            + "grep -qi 'no space left' /tmp/w0105-lowdisk.log && "
                            + "printf 'ENOSPC_OK;MARKER=%s\\n' \"$(grep -c kernel_profile=full-smp-arm64 /etc/orchestella/provision-marker)\"",
                    "__W0105_ENOSPC__",
                    30_000L);
            require(enospcRes.contains("ENOSPC_OK;MARKER=1"), "guest ENOSPC check failed: " + enospcRes);

            session.terminate();
            RuntimeProcessSupervisor.StopResult stop1 = ((RuntimeOwnedSession) session).stopRuntime(STOP_GRACE_MS);
            require(stop1 == RuntimeProcessSupervisor.StopResult.CONFIRMED
                    || stop1 == RuntimeProcessSupervisor.StopResult.ALREADY_STOPPED, "stop1 unconfirmed");
            ExecutionSessionRegistry.unregister(debian.id(), session);
            session = null;
        } finally {
            cleanupSessionSafely(session, store, controller, debian.id(), gen1, null);
        }

        // Reuse stopped resource cleanly (HIT)
        InstanceLifecycle.Instance stopped = store.load(debian.id());
        String gen2 = InstanceLaunchGuard.begin(stopped.id());
        require(gen2 != null, "reuse guard busy");
        InstanceLaunchSequence.beforeLaunch(
                controller, stopped, InstanceLifecycle.ConcurrencyPolicy.SINGLE_RUNNING_INSTANCE);
        ExecutionSession session2 = null;
        try {
            UmlInstanceLauncher launcher = new UmlInstanceLauncher(target, store);
            session2 = launcher.launch(paramsForDebian(stopped, distroInputsDir, rtDir));
            ExecutionSessionRegistry.LiveSession live2 = ExecutionSessionRegistry.getLiveSession(stopped.id());
            require(live2 != null, "reuse missing live session");
            awaitPrompt(live2, REBOOT_PROMPT_TIMEOUT_MS);
            authenticateBridge(session2, stopped.bridgeToken());
            require(((UmlInstanceLauncher.Session) session2).launchTimeline().credentialsOnlyOutcome()
                    == LaunchTimeline.CredentialsOnlyOutcome.HIT, "stopped reuse should HIT");
            session2.terminate();
            RuntimeProcessSupervisor.StopResult stop2 = ((RuntimeOwnedSession) session2).stopRuntime(STOP_GRACE_MS);
            require(stop2 == RuntimeProcessSupervisor.StopResult.CONFIRMED
                    || stop2 == RuntimeProcessSupervisor.StopResult.ALREADY_STOPPED, "reuse stop unconfirmed");
            ExecutionSessionRegistry.unregister(stopped.id(), session2);
            session2 = null;
        } finally {
            cleanupSessionSafely(session2, store, controller, stopped.id(), gen2, null);
        }
    }

    private void runAlpineFastRegression(Context target, Path probeRoot) throws Exception {
        Path distroInputsDir = target.getFilesDir().toPath().resolve("distro-inputs");
        stageAlpineIfMissing(target, distroInputsDir.resolve(ALPINE_TAR));
        Path storeDir = Files.createDirectories(probeRoot.resolve("alp-instances"));
        Path rootsDir = Files.createDirectories(probeRoot.resolve("alp-roots"));
        Path rtRoot = Files.createDirectories(probeRoot.resolve("alp-runtime"));
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        IsolatedQaExecutionEntry qa = new IsolatedQaExecutionEntry();
        InstanceLifecycle.Instance alpine = qa.ensurePersistentAlpineInstance(store, controller, rootsDir);
        SampleData s = runSingleEngineBoot(
                target, store, controller, distroInputsDir, rtRoot,
                alpine, "uml", "alpine_reg", 1, ALPINE_PROMPT_TIMEOUT_MS, false);
        require("PASS".equals(s.status), "Alpine FAST_UP_ARM64 regression failed: " + s.error + " " + s.detail);
    }

    private void runQemuAlpineRegression(Context target, Path probeRoot) throws Exception {
        Path distroInputsDir = target.getFilesDir().toPath().resolve("distro-inputs");
        stageAlpineIfMissing(target, distroInputsDir.resolve(ALPINE_TAR));
        Path storeDir = Files.createDirectories(probeRoot.resolve("qemu-alp-instances"));
        Path rootsDir = Files.createDirectories(probeRoot.resolve("qemu-alp-roots"));
        Path rtRoot = Files.createDirectories(probeRoot.resolve("qemu-alp-runtime"));
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance qemuInst = createBenchInstance(
                controller, rootsDir, "qemu", "alpine", "3.22.2",
                512, VmHardwareConfig.MIN_DISK_CAPACITY_BYTES, "w0105-qemu-alp", null);
        SampleData s = runSingleEngineBoot(
                target, store, controller, distroInputsDir, rtRoot,
                qemuInst, "qemu", "qemu_alp_reg", 1, 180_000L, false);
        require("PASS".equals(s.status), "QEMU Alpine regression failed: " + s.error + " " + s.detail);
    }

    // ---- Debian 13 + Docker multi-window benchmark ----

    private static Path debianBenchRoot(Context target) {
        return target.getFilesDir().toPath().resolve("w0105-bench/debian-docker");
    }

    private void runDebianBenchPrepare(Context target, Bundle out) throws Exception {
        reportStage("debian_bench_prepare");
        Path root = debianBenchRoot(target);
        Path propsPath = root.resolve("fixture.properties");
        if (Files.isRegularFile(propsPath)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(propsPath)) {
                p.load(in);
            }
            String id = p.getProperty("instanceId");
            if (id != null && Files.isRegularFile(root.resolve("instances").resolve(id + ".properties"))) {
                out.putString("w0105_summary", "debian_bench_fixture_reused");
                return;
            }
        }
        deleteTreeBestEffort(root);
        Path instancesDir = Files.createDirectories(root.resolve("instances"));
        Path rootsDir = Files.createDirectories(root.resolve("roots"));
        Path runtimeRoot = Files.createDirectories(root.resolve("runtime"));
        Path distroInputsDir = target.getFilesDir().toPath().resolve("distro-inputs");
        Path fixturesDir = target.getFilesDir().toPath().resolve("w0104-fixtures");

        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(instancesDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        IsolatedQaExecutionEntry qa = new IsolatedQaExecutionEntry();
        InstanceLifecycle.Instance inst = qa.ensurePersistentDebianInstance(
                store, controller, rootsDir, null,
                IsolatedQaExecutionEntry.DEFAULT_DEBIAN_RAM_MB, DISK_QUOTA_8G_BYTES, "w0105-deb-bench");

        String gen = InstanceLaunchGuard.begin(inst.id());
        require(gen != null, "bench prepare guard busy");
        InstanceLaunchSequence.beforeLaunch(
                controller, inst, InstanceLifecycle.ConcurrencyPolicy.SINGLE_RUNNING_INSTANCE);
        Path rtDir = Files.createDirectories(runtimeRoot.resolve(inst.id()));
        ExecutionSession session = null;
        try {
            session = new UmlInstanceLauncher(target, store).launch(paramsForDebian(inst, distroInputsDir, rtDir));
            UmlInstanceLauncher.Session umlSession = (UmlInstanceLauncher.Session) session;
            ExecutionSessionRegistry.LiveSession live = ExecutionSessionRegistry.getLiveSession(inst.id());
            require(live != null, "bench prepare missing live");
            awaitPrompt(live, PROVISION_PROMPT_TIMEOUT_MS);
            authenticateBridge(session, inst.bridgeToken());
            awaitSystemdActive(live, SYSTEMD_ACTIVE_TIMEOUT_MS);
            umlSession.markShellReady();

            String hostFixturesPath = fixturesDir.toAbsolutePath().toString();
            String prepCmd = runGuestCmd(
                    live,
                    "sh -ec '"
                            + "mkdir -p /mnt/w0104-fixtures && (mount -t hostfs -o "
                            + shellQuote("hostfs=" + hostFixturesPath)
                            + " none /mnt/w0104-fixtures || LIBMOUNT_FORCE_MOUNT2=always mount -t hostfs -o "
                            + shellQuote(hostFixturesPath)
                            + " none /mnt/w0104-fixtures); "
                            + "printf \"deb [signed-by=/mnt/w0104-fixtures/apt-repo/qa-w0104-archive.gpg] file:///mnt/w0104-fixtures/apt-repo trixie main\\n\" >/etc/apt/sources.list.d/w0104-offline.list; "
                            + "trap \"rm -f /etc/apt/sources.list.d/w0104-offline.list\" EXIT; "
                            + "apt-get update -o Dir::Etc::sourcelist=/etc/apt/sources.list.d/w0104-offline.list -o Dir::Etc::sourceparts=- "
                            + "-o Acquire::AllowInsecureRepositories=false -o Acquire::AllowDowngradeToInsecureRepositories=false -o APT::Get::AllowUnauthenticated=false </dev/null >/tmp/prep-apt.log 2>&1; "
                            + "DEBIAN_FRONTEND=noninteractive timeout -k 10 660 apt-get install -y --no-install-recommends "
                            + "-o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold "
                            + "-o Dir::Etc::sourcelist=/etc/apt/sources.list.d/w0104-offline.list -o Dir::Etc::sourceparts=- "
                            + "-o APT::Get::AllowUnauthenticated=false "
                            + "docker.io docker-cli containerd runc iptables nftables ca-certificates python3 </dev/null >>/tmp/prep-apt.log 2>&1; "
                            + "systemctl enable docker.service >/dev/null 2>&1 || :; "
                            + "systemctl start containerd.service docker.service; "
                            + "for i in $(seq 1 30); do docker info >/dev/null 2>&1 && break; sleep 1; done; "
                            + "docker load -i /mnt/w0104-fixtures/base-image-arm64.tar >/dev/null; "
                            + "mkdir -p /opt/w0105-http; "
                            + "cat >/opt/w0105-http/handle.sh <<\"SH\"\n"
                            + "#!/bin/sh\n"
                            + "read _r || true\n"
                            + "printf \"HTTP/1.0 200 OK\\r\\nContent-Length: 12\\r\\n\\r\\nW0105_BENCH\\n\"\n"
                            + "SH\n"
                            + "chmod +x /opt/w0105-http/handle.sh; "
                            + "cat >/opt/w0105-http/Dockerfile <<\"DF\"\n"
                            + "FROM alpine:3.22.2\n"
                            + "COPY handle.sh /app/handle.sh\n"
                            + "CMD [\"nc\", \"-ll\", \"-p\", \"8080\", \"-e\", \"/app/handle.sh\"]\n"
                            + "DF\n"
                            + "docker build --pull=false --platform linux/arm64 -t orchestella-w0105-bench:v1 /opt/w0105-http >/dev/null; "
                            + "sync; echo PREP_BENCH_OK'",
                    "__W0105_PREP__",
                    750_000L);
            require(prepCmd.contains("PREP_BENCH_OK"), "debian bench prep failed: " + prepCmd);

            live.sendBytes("systemctl poweroff\n".getBytes(StandardCharsets.UTF_8));
            require(umlSession.awaitPowerDownOrExit(90_000L), "prep poweroff timeout");
            RuntimeProcessSupervisor.StopResult stop = ((RuntimeOwnedSession) session).stopRuntime(STOP_GRACE_MS);
            require(stop == RuntimeProcessSupervisor.StopResult.CONFIRMED
                    || stop == RuntimeProcessSupervisor.StopResult.ALREADY_STOPPED, "prep stop unconfirmed");
            ExecutionSessionRegistry.unregister(inst.id(), session);
            session = null;
        } finally {
            cleanupSessionSafely(session, store, controller, inst.id(), gen, null);
        }

        Properties p = new Properties();
        p.setProperty("instanceId", inst.id());
        try (OutputStream outStream = Files.newOutputStream(propsPath)) {
            p.store(outStream, "W01.05 Debian+Docker benchmark fixture");
        }
        out.putString("w0105_summary", "debian_bench_fixture_prepared");
    }

    private void runDebianFirstProvision(Context target) throws Exception {
        reportStage("debian_first_provision_" + iteration);
        Path fpRoot = Files.createDirectories(target.getFilesDir().toPath().resolve("w0105-fp-" + iteration));
        Path instancesDir = Files.createDirectories(fpRoot.resolve("instances"));
        Path rootsDir = Files.createDirectories(fpRoot.resolve("roots"));
        Path rtRoot = Files.createDirectories(fpRoot.resolve("runtime"));
        Path distroInputsDir = target.getFilesDir().toPath().resolve("distro-inputs");
        try {
            InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(instancesDir);
            InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
            IsolatedQaExecutionEntry qa = new IsolatedQaExecutionEntry();
            InstanceLifecycle.Instance inst = qa.ensurePersistentDebianInstance(
                    store, controller, rootsDir, null,
                    IsolatedQaExecutionEntry.DEFAULT_DEBIAN_RAM_MB, DISK_QUOTA_8G_BYTES, "w0105-fp-" + iteration);
            SampleData s = runDebianBootSample(
                    target, store, controller, distroInputsDir, rtRoot, inst,
                    "first_provision", iteration, PROVISION_PROMPT_TIMEOUT_MS, false);
            emitSampleJson(s);
            require("PASS".equals(s.status), "first provision failed: " + s.error + " " + s.detail);
        } finally {
            deleteTreeBestEffort(fpRoot);
        }
    }

    private void runDebianBenchBoot(Context target, String bucketName) throws Exception {
        reportStage("debian_" + bucketName + "_" + iteration);
        Path root = debianBenchRoot(target);
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(root.resolve("fixture.properties"))) {
            p.load(in);
        }
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(root.resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        controller.reconcileAfterHostDeath(InstanceLifecycle.State.STOPPED);
        InstanceLifecycle.Instance inst = store.load(p.getProperty("instanceId"));
        Path distroInputsDir = target.getFilesDir().toPath().resolve("distro-inputs");
        SampleData s = runDebianBootSample(
                target, store, controller, distroInputsDir, root.resolve("runtime"), inst,
                bucketName, iteration, REBOOT_PROMPT_TIMEOUT_MS, true);
        emitSampleJson(s);
        require("PASS".equals(s.status), bucketName + " failed: " + s.error + " " + s.detail);
    }

    private SampleData runDebianBootSample(
            Context target,
            InstanceLifecycle.FileStore store,
            InstanceLifecycle.Controller controller,
            Path distroInputsDir,
            Path runtimeRoot,
            InstanceLifecycle.Instance inst,
            String bucketName,
            int iter,
            long promptTimeoutMs,
            boolean checkDockerAndHttp) {
        String gen = InstanceLaunchGuard.begin(inst.id());
        if (gen == null) {
            return SampleData.failure("uml", "full-smp-arm64", "debian", "13", 1, 1024, "offline",
                    iter, 0, null, "launch_guard_busy", "guard busy");
        }
        ExecutionSession session = null;
        RuntimeProcessSupervisor.StopResult stopRes = null;
        PortForwardingManager fwdMgr = null;
        try {
            fwdMgr = PortForwardingManager.forInstance(
                    inst.id(), target.getFilesDir().toPath().resolve("network"));
            InstanceLaunchSequence.beforeLaunch(
                    controller, inst, InstanceLifecycle.ConcurrencyPolicy.SINGLE_RUNNING_INSTANCE);
            clearRules(fwdMgr);
            Path rtDir = Files.createDirectories(runtimeRoot.resolve(inst.id()));
            long launchT0Ns = SystemClock.elapsedRealtimeNanos();
            session = new UmlInstanceLauncher(target, store).launch(paramsForDebian(inst, distroInputsDir, rtDir));
            UmlInstanceLauncher.Session umlSession = (UmlInstanceLauncher.Session) session;
            ExecutionSessionRegistry.LiveSession live = ExecutionSessionRegistry.getLiveSession(inst.id());
            require(live != null, "missing live session");

            awaitPromptAndBridge(live, session, inst.bridgeToken(), promptTimeoutMs, "w0105-deb-bridge-" + iter);

            awaitSystemdActive(live, SYSTEMD_ACTIVE_TIMEOUT_MS);
            umlSession.markShellReady();

            LaunchTimeline tl = umlSession.launchTimeline();
            long shellMs = tl != null ? tl.elapsedMs(LaunchTimeline.Stage.SHELL_READY) : -1L;

            String echoRes = runGuestCmd(live, "echo W0105_ECHO_" + iter, "__W0105_EC_" + iter + "__", 15_000L);
            require(echoRes.contains("W0105_ECHO_" + iter), "first cmd echo missing");
            long actualEchoMs = Math.max(Math.max(0L, shellMs),
                    (SystemClock.elapsedRealtimeNanos() - launchT0Ns) / 1_000_000L);

            Long dockerReadyMs = null;
            Long httpReadyMs = null;
            if (checkDockerAndHttp) {
                String dReady = runGuestCmd(
                        live,
                        "sh -ec 'for i in $(seq 1 40); do systemctl is-active docker.service >/dev/null 2>&1 && docker info >/dev/null 2>&1 && break; sleep 0.2; done; "
                                + "docker info >/dev/null 2>&1 && printf \"%s%s\\n\" \"DOCKER_\" \"READY\"'",
                        "__W0105_DRDY__",
                        30_000L);
                require(dReady.contains("DOCKER_READY"), "docker daemon not ready: " + dReady);
                dockerReadyMs = Math.max(actualEchoMs,
                        (SystemClock.elapsedRealtimeNanos() - launchT0Ns) / 1_000_000L);

                int hostPort = pickFreeUserPort(inst.id());
                PortForwardingPresenter pres = PortForwardingPresenter.forActiveSession(inst.id(), fwdMgr);
                String runCtr = runGuestCmd(
                        live,
                        "sh -ec 'docker rm -f w0105-b >/dev/null 2>&1 || :; "
                                + "docker run -d --name w0105-b --memory=64m --pids-limit=32 -p 127.0.0.1:"
                                + GUEST_CONTAINER_HOST_PORT + ":8080 orchestella-w0105-bench:v1 >/dev/null && printf \"%s%s\\n\" \"CTR_\" \"UP\"'",
                        "__W0105_CTR__",
                        30_000L);
                require(runCtr.contains("CTR_UP"), "bench container failed: " + runCtr);
                pres.addRule(Integer.toString(hostPort), Integer.toString(GUEST_CONTAINER_HOST_PORT), Protocol.TCP, "bench");
                String httpBody = readHttpWithRetry(hostPort, 5_000L);
                require(httpBody.contains("W0105_BENCH"), "bench http failed: " + httpBody);
                httpReadyMs = Math.max(dockerReadyMs,
                        (SystemClock.elapsedRealtimeNanos() - launchT0Ns) / 1_000_000L);
                for (PortForwardingRule r : List.copyOf(fwdMgr.listRules())) {
                    pres.deleteRule(r.id());
                }
                runGuestCmd(live, "docker rm -f w0105-b >/dev/null 2>&1 || :", "__W0105_CRM__", 15_000L);
            }

            long bridgeMs = tl != null ? tl.elapsedMs(LaunchTimeline.Stage.BRIDGE_READY) : -1L;
            long systemdMs = tl != null ? tl.elapsedMs(LaunchTimeline.Stage.SYSTEMD_READY) : -1L;
            String cred = tl != null && tl.credentialsOnlyOutcome() != null
                    ? tl.credentialsOnlyOutcome().name() : null;

            session.terminate();
            stopRes = ((RuntimeOwnedSession) session).stopRuntime(STOP_GRACE_MS);
            ExecutionSessionRegistry.unregister(inst.id(), session);
            session = null;
            if (stopRes != RuntimeProcessSupervisor.StopResult.CONFIRMED
                    && stopRes != RuntimeProcessSupervisor.StopResult.ALREADY_STOPPED) {
                return SampleData.failure("uml", "full-smp-arm64", "debian", "13", 1, 1024, "offline",
                        iter, 0, bucketName, "stop_unconfirmed", "stop=" + stopRes);
            }

            SampleData s = new SampleData();
            s.bucket = bucketName;
            s.engine = "uml";
            s.profile = "full-smp-arm64";
            s.distro = "debian";
            s.distroVersion = "13";
            s.vcpu = 1;
            s.ramMb = 1024;
            s.networkMode = "offline";
            s.iteration = iter;
            s.status = "PASS";
            s.t0ToBridgeReadyMs = bridgeMs >= 0 ? bridgeMs : null;
            s.t0ToSystemdReadyMs = systemdMs >= 0 ? systemdMs : null;
            s.t0ToShellReadyMs = shellMs >= 0 ? shellMs : null;
            s.t0ToFirstCmdEchoMs = actualEchoMs;
            s.t0ToDockerDaemonReadyMs = dockerReadyMs;
            s.t0ToContainerHttpReadyMs = httpReadyMs;
            s.credentialsOnlyReason = cred;
            s.stopResult = stopRes.name();
            s.detail = "ok";
            return s;
        } catch (Throwable failure) {
            String msg = sanitizeDetail(failure.getMessage());
            boolean to = msg != null && msg.toLowerCase(Locale.ROOT).contains("timeout");
            SampleData s = SampleData.failure("uml", "full-smp-arm64", "debian", "13", 1, 1024, "offline",
                    iter, 0, bucketName, failure.getClass().getSimpleName(), msg);
            s.status = to ? "TIMEOUT" : "FAIL";
            return s;
        } finally {
            try {
                if (fwdMgr != null) clearRules(fwdMgr);
            } catch (IOException ignored) {
            }
            cleanupSessionSafely(session, store, controller, inst.id(), gen, null);
        }
    }

    private void runDebianWarmReconnect(Context target) throws Exception {
        reportStage("debian_warm_reconnect");
        Path root = debianBenchRoot(target);
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(root.resolve("fixture.properties"))) {
            p.load(in);
        }
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(root.resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        controller.reconcileAfterHostDeath(InstanceLifecycle.State.STOPPED);
        InstanceLifecycle.Instance inst = store.load(p.getProperty("instanceId"));
        Path distroInputsDir = target.getFilesDir().toPath().resolve("distro-inputs");
        String gen = InstanceLaunchGuard.begin(inst.id());
        require(gen != null, "reconnect guard busy");
        InstanceLaunchSequence.beforeLaunch(
                controller, inst, InstanceLifecycle.ConcurrencyPolicy.SINGLE_RUNNING_INSTANCE);
        ExecutionSession session = null;
        try {
            Path rtDir = Files.createDirectories(root.resolve("runtime").resolve(inst.id()));
            session = new UmlInstanceLauncher(target, store).launch(paramsForDebian(inst, distroInputsDir, rtDir));
            ExecutionSessionRegistry.LiveSession live = ExecutionSessionRegistry.getLiveSession(inst.id());
            require(live != null, "reconnect missing live");
            awaitPrompt(live, REBOOT_PROMPT_TIMEOUT_MS);
            authenticateBridge(session, inst.bridgeToken());
            awaitSystemdActive(live, SYSTEMD_ACTIVE_TIMEOUT_MS);
            ((UmlInstanceLauncher.Session) session).markShellReady();

            int passed = 0;
            for (int i = 1; i <= count; i++) {
                live.setOutputListener(null);
                long t0 = SystemClock.elapsedRealtimeNanos();
                CountDownLatch latch = new CountDownLatch(1);
                String marker = "__W0105_DREC_" + i + "__";
                StringBuilder seen = new StringBuilder();
                live.setOutputListener(chunk -> {
                    if (chunk == null) return;
                    synchronized (seen) {
                        seen.append(chunk);
                        if (seen.indexOf(marker) >= 0) latch.countDown();
                    }
                });
                long reattachMs = Math.max(0L, (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L);
                live.sendBytes(("printf '%s%s\\n' '__W0105_DREC_' '" + i + "__'\n").getBytes(StandardCharsets.UTF_8));
                boolean ok = latch.await(15_000L, TimeUnit.MILLISECONDS);
                long roundtripMs = Math.max(0L, (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L);
                live.setOutputListener(null);
                SampleData s = new SampleData();
                s.engine = "uml";
                s.profile = "full-smp-arm64";
                s.distro = "debian";
                s.distroVersion = "13";
                s.vcpu = 1;
                s.ramMb = 1024;
                s.networkMode = "offline";
                s.iteration = i;
                s.status = ok ? "PASS" : "TIMEOUT";
                s.t0ToShellReadyMs = roundtripMs;
                s.t0ToFirstCmdEchoMs = roundtripMs;
                s.reattachMs = reattachMs;
                s.roundtripMs = roundtripMs;
                s.credentialsOnlyReason = "HIT";
                s.stopResult = "CONFIRMED";
                s.detail = ok ? "ok" : "reconnect_timeout";
                emitSampleJson(s);
                if (ok) passed++;
            }
            session.terminate();
            RuntimeProcessSupervisor.StopResult stop = ((RuntimeOwnedSession) session).stopRuntime(STOP_GRACE_MS);
            ExecutionSessionRegistry.unregister(inst.id(), session);
            session = null;
            require(passed == count, "debian warm reconnect partial: " + passed + "/" + count);
            require(stop == RuntimeProcessSupervisor.StopResult.CONFIRMED
                    || stop == RuntimeProcessSupervisor.StopResult.ALREADY_STOPPED, "stop unconfirmed");
        } finally {
            cleanupSessionSafely(session, store, controller, inst.id(), gen, null);
        }
    }

    private void runDebianWarmRestartBatch(Context target, Bundle out) throws Exception {
        reportStage("debian_warm_restart_batch");
        Path root = debianBenchRoot(target);
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(root.resolve("fixture.properties"))) {
            p.load(in);
        }
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(root.resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        controller.reconcileAfterHostDeath(InstanceLifecycle.State.STOPPED);
        InstanceLifecycle.Instance inst = store.load(p.getProperty("instanceId"));
        Path distroInputsDir = target.getFilesDir().toPath().resolve("distro-inputs");
        Path rtRoot = root.resolve("runtime");

        if (warmup) {
            reportStage("debian_warm_batch_warmup");
            SampleData w = runDebianBootSample(
                    target, store, controller, distroInputsDir, rtRoot, inst,
                    "warm_restart", 0, REBOOT_PROMPT_TIMEOUT_MS, true);
            require("PASS".equals(w.status), "debian warm warmup failed: " + w.error + " " + w.detail);
        }
        int passed = 0;
        for (int i = 0; i < count; i++) {
            int iterIdx = startIter + i;
            reportStage("debian_warm_batch_" + iterIdx);
            SampleData s = runDebianBootSample(
                    target, store, controller, distroInputsDir, rtRoot, inst,
                    "warm_restart", iterIdx, REBOOT_PROMPT_TIMEOUT_MS, true);
            emitSampleJson(s);
            if ("PASS".equals(s.status)) {
                passed++;
            }
        }
        out.putInt("debian_warm_batch_passed", passed);
        out.putInt("debian_warm_batch_expected", count);
        require(passed == count, "debian warm batch partial: " + passed + "/" + count);
    }

    // ---- Paired Alpine FAST_UP_ARM64 vs FULL_SMP_ARM64 ----

    private static Path alpinePairedRoot(Context target) {
        return target.getFilesDir().toPath().resolve("w0105-bench/alpine-paired");
    }

    private void runAlpinePairedPrepare(Context target, Bundle out) throws Exception {
        reportStage("alpine_paired_prepare");
        Path root = alpinePairedRoot(target);
        Path propsPath = root.resolve("fixture.properties");
        if (Files.isRegularFile(propsPath)) {
            out.putString("w0105_summary", "alpine_paired_fixture_reused");
            return;
        }
        deleteTreeBestEffort(root);
        Path instancesDir = Files.createDirectories(root.resolve("instances"));
        Path rootsDir = Files.createDirectories(root.resolve("roots"));
        Path rtDir = Files.createDirectories(root.resolve("runtime"));
        Path distroInputsDir = target.getFilesDir().toPath().resolve("distro-inputs");
        stageAlpineIfMissing(target, distroInputsDir.resolve(ALPINE_TAR));

        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(instancesDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance fastInst = createBenchInstance(
                controller, rootsDir, "uml", "alpine", "3.22.2", 512,
                VmHardwareConfig.MIN_DISK_CAPACITY_BYTES, "w0105-alp-fast",
                UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64);
        InstanceLifecycle.Instance fullInst = createBenchInstance(
                controller, rootsDir, "uml", "alpine", "3.22.2", 512,
                VmHardwareConfig.MIN_DISK_CAPACITY_BYTES, "w0105-alp-full",
                UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64);

        SampleData fPrep = runSingleEngineBoot(
                target, store, controller, distroInputsDir, rtDir,
                fastInst, "uml", "first_provision", 0, 180_000L, true);
        require("PASS".equals(fPrep.status), "alpine fast prep failed: " + fPrep.error + " " + fPrep.detail);
        SampleData uPrep = runSingleEngineBoot(
                target, store, controller, distroInputsDir, rtDir,
                fullInst, "uml", "first_provision", 0, 180_000L, true);
        require("PASS".equals(uPrep.status), "alpine full prep failed: " + uPrep.error + " " + uPrep.detail);

        Properties p = new Properties();
        p.setProperty("fastId", fastInst.id());
        p.setProperty("fullId", fullInst.id());
        try (OutputStream os = Files.newOutputStream(propsPath)) {
            p.store(os, "W01.05 Alpine fast vs full paired fixture");
        }
        out.putString("w0105_summary", "alpine_paired_prepared");
    }

    private void runAlpinePairedCold(Context target) throws Exception {
        Path root = alpinePairedRoot(target);
        Properties p = loadProps(root.resolve("fixture.properties"));
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(root.resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        controller.reconcileAfterHostDeath(InstanceLifecycle.State.STOPPED);
        String id = "full".equals(variant) ? p.getProperty("fullId") : p.getProperty("fastId");
        InstanceLifecycle.Instance inst = store.load(id);
        SampleData s = runSingleEngineBoot(
                target, store, controller, target.getFilesDir().toPath().resolve("distro-inputs"),
                root.resolve("runtime"), inst, "uml", "cold_provisioned", pairIndex, ALPINE_PROMPT_TIMEOUT_MS, false);
        s.variant = variant;
        s.pairIndex = pairIndex;
        emitSampleJson(s);
        require("PASS".equals(s.status), "alpine paired cold failed: " + s.error + " " + s.detail);
    }

    private void runAlpinePairedWarmPair(Context target) throws Exception {
        Path root = alpinePairedRoot(target);
        Properties p = loadProps(root.resolve("fixture.properties"));
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(root.resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        controller.reconcileAfterHostDeath(InstanceLifecycle.State.STOPPED);
        String[] order = (pairIndex % 2 == 1) ? new String[] {"fast", "full"} : new String[] {"full", "fast"};
        Path distroInputs = target.getFilesDir().toPath().resolve("distro-inputs");
        if (warmup) {
            runAlpineWarmupPair(target, store, controller, distroInputs, root.resolve("runtime"), p, order);
        }
        for (String v : order) {
            InstanceLifecycle.Instance inst = store.load(p.getProperty("full".equals(v) ? "fullId" : "fastId"));
            SampleData s = runSingleEngineBoot(
                    target, store, controller, distroInputs, root.resolve("runtime"),
                    inst, "uml", "warm_restart", pairIndex, ALPINE_PROMPT_TIMEOUT_MS, false);
            s.variant = v;
            s.pairIndex = pairIndex;
            emitSampleJson(s);
            require("PASS".equals(s.status), "alpine paired warm failed (" + v + "): " + s.error);
        }
    }

    private void runAlpinePairedWarmBatch(Context target, Bundle out) throws Exception {
        reportStage("alpine_paired_warm_batch");
        Path root = alpinePairedRoot(target);
        Properties p = loadProps(root.resolve("fixture.properties"));
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(root.resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        controller.reconcileAfterHostDeath(InstanceLifecycle.State.STOPPED);
        Path distroInputs = target.getFilesDir().toPath().resolve("distro-inputs");
        Path rtRoot = root.resolve("runtime");
        if (warmup) {
            runAlpineWarmupPair(target, store, controller, distroInputs, rtRoot, p, new String[] {"fast", "full"});
        }
        int passed = 0;
        for (int i = 0; i < count; i++) {
            int pIdx = startIter + i;
            String[] order = (pIdx % 2 == 1) ? new String[] {"fast", "full"} : new String[] {"full", "fast"};
            for (String v : order) {
                reportStage("alpine_warm_pair_" + pIdx + "_" + v);
                InstanceLifecycle.Instance inst = store.load(p.getProperty("full".equals(v) ? "fullId" : "fastId"));
                SampleData s = runSingleEngineBoot(
                        target, store, controller, distroInputs, rtRoot,
                        inst, "uml", "warm_restart", pIdx, ALPINE_PROMPT_TIMEOUT_MS, false);
                s.variant = v;
                s.pairIndex = pIdx;
                emitSampleJson(s);
                if ("PASS".equals(s.status)) passed++;
            }
        }
        out.putInt("alpine_warm_batch_passed", passed);
        out.putInt("alpine_warm_batch_expected", count * 2);
        require(passed == count * 2, "alpine paired warm batch partial: " + passed + "/" + (count * 2));
    }

    private void runAlpineWarmupPair(
            Context target,
            InstanceLifecycle.FileStore store,
            InstanceLifecycle.Controller controller,
            Path distroInputs,
            Path rtRoot,
            Properties props,
            String[] order) throws Exception {
        for (String v : order) {
            reportStage("alpine_warm_warmup_" + v);
            InstanceLifecycle.Instance inst = store.load(props.getProperty("full".equals(v) ? "fullId" : "fastId"));
            SampleData wu = runSingleEngineBoot(
                    target, store, controller, distroInputs, rtRoot,
                    inst, "uml", "warm_restart", 0, ALPINE_PROMPT_TIMEOUT_MS, false);
            require("PASS".equals(wu.status), "alpine warmup failed (" + v + "): " + wu.error);
        }
    }

    private void runAlpinePairedReconnect(Context target) throws Exception {
        reportStage("alpine_paired_reconnect");
        Path root = alpinePairedRoot(target);
        Properties p = loadProps(root.resolve("fixture.properties"));
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(root.resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        controller.reconcileAfterHostDeath(InstanceLifecycle.State.STOPPED);
        InstanceLifecycle.Instance fastInst = store.load(p.getProperty("fastId"));
        InstanceLifecycle.Instance fullInst = store.load(p.getProperty("fullId"));
        Path rtRoot = root.resolve("runtime");

        int passed = 0;
        int blockSize = 5;
        int blockIdx = 0;
        for (int start = 1; start <= count; start += blockSize) {
            blockIdx++;
            int end = Math.min(count, start + blockSize - 1);
            int blockCount = end - start + 1;
            String[] order = (blockIdx % 2 == 1) ? new String[] {"fast", "full"} : new String[] {"full", "fast"};
            SampleData[][] byVar = new SampleData[2][];
            for (int vIdx = 0; vIdx < order.length; vIdx++) {
                String varLabel = order[vIdx];
                InstanceLifecycle.Instance inst = "full".equals(varLabel) ? fullInst : fastInst;
                byVar[vIdx] = measureAlpineReconnectBlock(
                        target, store, controller, rtRoot, inst, varLabel, start, blockCount);
            }
            for (int offset = 0; offset < blockCount; offset++) {
                int pIdx = start + offset;
                SampleData s0 = byVar[0][offset];
                SampleData s1 = byVar[1][offset];
                emitSampleJson(s0);
                emitSampleJson(s1);
                if ("PASS".equals(s0.status)) passed++;
                if ("PASS".equals(s1.status)) passed++;
                assertTrueOrFail(pIdx, s0, s1);
            }
        }
        require(passed == count * 2, "alpine paired reconnect partial: " + passed + "/" + (count * 2));
    }

    private static void assertTrueOrFail(int pairIdx, SampleData a, SampleData b) {
        if (!"PASS".equals(a.status) || !"PASS".equals(b.status)) {
            throw new AssertionError("alpine reconnect failed at pair " + pairIdx);
        }
    }

    private SampleData[] measureAlpineReconnectBlock(
            Context target,
            InstanceLifecycle.FileStore store,
            InstanceLifecycle.Controller controller,
            Path runtimeRoot,
            InstanceLifecycle.Instance inst,
            String varLabel,
            int startPairIdx,
            int blockCount) throws Exception {
        String gen = InstanceLaunchGuard.begin(inst.id());
        require(gen != null, "alpine rec guard busy");
        InstanceLaunchSequence.beforeLaunch(
                controller, inst, InstanceLifecycle.ConcurrencyPolicy.SINGLE_RUNNING_INSTANCE);
        ExecutionSession session = null;
        SampleData[] outSamples = new SampleData[blockCount];
        try {
            Path rtDir = Files.createDirectories(runtimeRoot.resolve(inst.id()));
            session = new UmlInstanceLauncher(target, store).launch(paramsForAlpine(inst,
                    target.getFilesDir().toPath().resolve("distro-inputs"), rtDir));
            ExecutionSessionRegistry.LiveSession live = ExecutionSessionRegistry.getLiveSession(inst.id());
            require(live != null, "alpine rec missing live");
            awaitPromptAndBridge(live, session, inst.bridgeToken(), ALPINE_PROMPT_TIMEOUT_MS,
                    "w0105-arec-bridge-" + varLabel);
            String warmupEcho = runGuestCmd(live, "echo W0105_AREC_WARMUP", "__W0105_AWARM__", 15_000L);
            require(warmupEcho.contains("W0105_AREC_WARMUP"), "alpine rec warmup failed");

            for (int i = 0; i < blockCount; i++) {
                int pIdx = startPairIdx + i;
                live.setOutputListener(null);
                long t0 = SystemClock.elapsedRealtimeNanos();
                CountDownLatch latch = new CountDownLatch(1);
                String marker = "__W0105_AREC_" + varLabel + "_" + pIdx + "__";
                StringBuilder seen = new StringBuilder();
                live.setOutputListener(chunk -> {
                    if (chunk == null) return;
                    synchronized (seen) {
                        seen.append(chunk);
                        if (seen.indexOf(marker) >= 0) latch.countDown();
                    }
                });
                long reattachMs = Math.max(0L, (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L);
                live.sendBytes(("printf '%s%s\\n' '__W0105_AREC_' '" + varLabel + "_" + pIdx + "__'\n")
                        .getBytes(StandardCharsets.UTF_8));
                boolean ok = latch.await(15_000L, TimeUnit.MILLISECONDS);
                long roundtripMs = Math.max(0L, (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L);
                live.setOutputListener(null);
                SampleData s = new SampleData();
                s.engine = "uml";
                s.profile = "full".equals(varLabel) ? "full-smp-arm64" : "fast-up-arm64";
                s.variant = varLabel;
                s.pairIndex = pIdx;
                s.iteration = pIdx;
                s.distro = "alpine";
                s.distroVersion = "3.22.2";
                s.vcpu = 1;
                s.ramMb = 512;
                s.networkMode = "offline";
                s.status = ok ? "PASS" : "TIMEOUT";
                s.t0ToShellReadyMs = roundtripMs;
                s.t0ToFirstCmdEchoMs = roundtripMs;
                s.reattachMs = reattachMs;
                s.roundtripMs = roundtripMs;
                s.credentialsOnlyReason = "HIT";
                s.stopResult = "CONFIRMED";
                s.detail = ok ? "ok" : "reconnect_timeout";
                outSamples[i] = s;
            }
            ((RuntimeOwnedSession) session).stopRuntime(STOP_GRACE_MS);
            ExecutionSessionRegistry.unregister(inst.id(), session);
            session = null;
        } finally {
            cleanupSessionSafely(session, store, controller, inst.id(), gen, null);
        }
        return outSamples;
    }

    // ---- QEMU Debian 13 reference (no QEMU optimizations) ----

    private void runQemuDebianRef(Context target, Bundle out) throws Exception {
        reportStage("qemu_debian_ref");
        Path root = target.getFilesDir().toPath().resolve("w0105-qemu-deb-ref");
        deleteTreeBestEffort(root);
        Path instancesDir = Files.createDirectories(root.resolve("instances"));
        Path rootsDir = Files.createDirectories(root.resolve("roots"));
        Path rtRoot = Files.createDirectories(root.resolve("runtime"));
        Path distroInputsDir = target.getFilesDir().toPath().resolve("distro-inputs");
        try {
            InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(instancesDir);
            InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
            InstanceLifecycle.Instance qDeb = createBenchInstance(
                    controller, rootsDir, "qemu", "debian", "13",
                    1024, DISK_QUOTA_8G_BYTES, "w0105-qemu-deb", null);
            SampleData fp = runSingleEngineBoot(
                    target, store, controller, distroInputsDir, rtRoot,
                    qDeb, "qemu", "first_provision", 1, 600_000L, true);
            fp.iteration = 1;
            emitSampleJson(fp);
            require("PASS".equals(fp.status), "qemu debian first provision failed: " + fp.error + " " + fp.detail);

            // Wait/ensure provisioning evidence recorded so subsequent boots hit fast path
            if (!store.load(qDeb.id()).isProvisionedFor(OfficialDebianCatalog.debian().sha256())) {
                controller.recordProvisioning(
                        qDeb.id(),
                        new InstanceLifecycle.ProvisioningEvidence(
                                1, OfficialDebianCatalog.debian().sha256(), System.currentTimeMillis()));
            }
            for (int i = 1; i <= count; i++) {
                SampleData cold = runSingleEngineBoot(
                        target, store, controller, distroInputsDir, rtRoot,
                        store.load(qDeb.id()), "qemu", "cold_provisioned", i, 180_000L, false);
                cold.iteration = i;
                emitSampleJson(cold);
                require("PASS".equals(cold.status), "qemu debian cold #" + i + " failed: " + cold.error);
            }
            out.putString("w0105_summary", "qemu_debian_reference_ok");
        } finally {
            deleteTreeBestEffort(root);
        }
    }

    private SampleData runSingleEngineBoot(
            Context target,
            InstanceLifecycle.FileStore store,
            InstanceLifecycle.Controller controller,
            Path distroInputsDir,
            Path runtimeRoot,
            InstanceLifecycle.Instance inst,
            String eng,
            String bucketName,
            int idx,
            long promptTimeoutMs,
            boolean isFirstProvision) {
        String distroId = inst.distro().id();
        String distroVer = inst.distro().version();
        int ramMb = inst.hardwareConfig().ramMegabytes();
        String profile = "uml".equals(eng)
                ? (inst.kernelProfile() != null ? inst.kernelProfile() : "fast-up-arm64")
                : "qemu";
        String gen = InstanceLaunchGuard.begin(inst.id());
        if (gen == null) {
            return SampleData.failure(eng, profile, distroId, distroVer, 1, ramMb, "offline",
                    idx, idx, bucketName, "launch_guard_busy", "guard busy");
        }
        ExecutionSession session = null;
        RuntimeProcessSupervisor.StopResult stopRes = null;
        try {
            InstanceLaunchSequence.beforeLaunch(
                    controller, inst, InstanceLifecycle.ConcurrencyPolicy.SINGLE_RUNNING_INSTANCE);
            Path rtDir = Files.createDirectories(runtimeRoot.resolve(inst.id()));
            String tarName = "debian".equals(distroId) ? DEBIAN_TAR : ALPINE_TAR;
            InstanceStrategyLauncher.LaunchParameters params = new InstanceStrategyLauncher.LaunchParameters(
                    inst.id(), inst.bridgeToken(), tarName, inst.rootVolume().toString(),
                    rtDir.toString(), distroInputsDir.toString(), inst.guestPassword(),
                    inst.hardwareConfig(), null, null, null, inst.hostname(),
                    inst.defaultUser(), inst.hasSudo(), null, inst.kernelProfile());
            long launchT0Ns = SystemClock.elapsedRealtimeNanos();
            if ("uml".equals(eng)) {
                session = new UmlInstanceLauncher(target, store).launch(params);
            } else {
                session = new QemuInstanceLauncher(
                        target, List.of(ALPINE_MANIFEST),
                        store.load(inst.id()).rootVolume().getParent().getParent().resolve("instances"))
                        .launch(params);
            }
            ExecutionSessionRegistry.LiveSession live = ExecutionSessionRegistry.getLiveSession(inst.id());
            require(live != null, "missing live session");

            awaitPromptAndBridge(live, session, inst.bridgeToken(), promptTimeoutMs,
                    "w0105-bridge-" + eng + "-" + idx);
            LaunchTimeline tl = extractTimeline(session);
            long shellMs = tl != null ? tl.elapsedMs(LaunchTimeline.Stage.SHELL_READY) : -1L;
            String echo = runGuestCmd(live, "echo W0105_OK_" + idx, "__W0105_OK_" + idx + "__", 20_000L);
            require(echo.contains("W0105_OK_" + idx), "echo failed");
            long echoMs = Math.max(Math.max(0L, shellMs),
                    (SystemClock.elapsedRealtimeNanos() - launchT0Ns) / 1_000_000L);

            if (isFirstProvision && "qemu".equals(eng)) {
                String qMark = runGuestCmd(
                        live,
                        "grep -Fqx 'provisioning_version=1' /var/lib/droidlayer/provisioned-v1 && echo QEMU_PROV_OK",
                        "__W0105_QMARK__",
                        15_000L);
                require(qMark.contains("QEMU_PROV_OK"), "qemu marker missing: " + qMark);
            }

            long bridgeMs = tl != null ? tl.elapsedMs(LaunchTimeline.Stage.BRIDGE_READY) : -1L;
            String cred = tl != null && tl.credentialsOnlyOutcome() != null
                    ? tl.credentialsOnlyOutcome().name() : null;

            stopRes = stopAnySession(session);
            ExecutionSessionRegistry.unregister(inst.id(), session);
            session = null;
            if (stopRes != RuntimeProcessSupervisor.StopResult.CONFIRMED
                    && stopRes != RuntimeProcessSupervisor.StopResult.ALREADY_STOPPED) {
                return SampleData.failure(eng, profile, distroId, distroVer, 1, ramMb, "offline",
                        idx, idx, bucketName, "stop_unconfirmed", "stop=" + stopRes);
            }

            SampleData s = new SampleData();
            s.engine = eng;
            s.profile = profile;
            s.bucket = bucketName;
            s.distro = distroId;
            s.distroVersion = distroVer;
            s.vcpu = 1;
            s.ramMb = ramMb;
            s.networkMode = "offline";
            s.iteration = idx;
            s.pairIndex = idx;
            s.status = "PASS";
            s.t0ToBridgeReadyMs = bridgeMs >= 0 ? bridgeMs : null;
            s.t0ToShellReadyMs = shellMs >= 0 ? shellMs : null;
            s.t0ToFirstCmdEchoMs = echoMs;
            s.credentialsOnlyReason = cred;
            s.stopResult = stopRes.name();
            s.detail = "ok";
            return s;
        } catch (Throwable failure) {
            String msg = sanitizeDetail(failure.getMessage());
            boolean to = msg != null && msg.toLowerCase(Locale.ROOT).contains("timeout");
            SampleData s = SampleData.failure(eng, profile, distroId, distroVer, 1, ramMb, "offline",
                    idx, idx, bucketName, failure.getClass().getSimpleName(), msg);
            s.status = to ? "TIMEOUT" : "FAIL";
            return s;
        } finally {
            cleanupSessionSafely(session, store, controller, inst.id(), gen, null);
        }
    }

    private static RuntimeProcessSupervisor.StopResult stopAnySession(ExecutionSession session) {
        if (session instanceof RuntimeOwnedSession owned) {
            return owned.stopRuntime(STOP_GRACE_MS);
        }
        try {
            session.terminate();
        } catch (IOException ignored) {
        }
        session.close();
        return RuntimeProcessSupervisor.StopResult.CONFIRMED;
    }

    private static InstanceLifecycle.Instance createBenchInstance(
            InstanceLifecycle.Controller controller,
            Path rootsDir,
            String eng,
            String distroId,
            String distroVersion,
            int ramMb,
            long diskBytes,
            String hostname,
            UmlDistroCapabilityCatalog.KernelProfile profile) throws IOException {
        Files.createDirectories(rootsDir);
        Path volume = rootsDir.resolve(hostname + "-" + UUID.randomUUID() + ".raw");
        if ("qemu".equals(eng)) {
            writeQemuEmptyRootVolume(volume, diskBytes);
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
        String profileId = profile != null
                ? UmlKernelPayloadContract.descriptorFor(profile).profileId() : null;
        InstanceLifecycle.Instance created = controller.create(
                new InstanceLifecycle.DistroReference(distroId, distroVersion),
                volume,
                VmHardwareConfig.builder()
                        .cpuCores(1)
                        .ramMegabytes(ramMb)
                        .diskCapacityBytes(diskBytes)
                        .gpuAccelerationEnabled(false)
                        .build(),
                new InstanceUserConfig(InstanceUserConfig.DEFAULT_ROOT_USER, "orchestellaqa", false, hostname),
                identity,
                profileId);
        controller.beginProvisioning(created.id());
        return controller.completeProvisioning(created.id());
    }

    private static void writeQemuEmptyRootVolume(Path volume, long sizeBytes) throws IOException {
        try (SeekableByteChannel ch = Files.newByteChannel(
                volume, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] sig = GuestRuntimeProvisioner.ROOT_VOLUME_CREATION_SIGNATURE
                    .getBytes(StandardCharsets.US_ASCII);
            ch.write(ByteBuffer.wrap(sig));
            ch.position(sizeBytes - 1L);
            ch.write(ByteBuffer.wrap(new byte[] {0}));
        }
    }

    private static void stageAlpineIfMissing(Context target, Path destination) throws Exception {
        Files.createDirectories(destination.getParent());
        if (Files.isRegularFile(destination) && Files.size(destination) == ALPINE_SIZE_BYTES) {
            return;
        }
        byte[] buf = new byte[65_536];
        try (InputStream in = PackagedAssetStreams.openResource(target, ALPINE_TAR);
                OutputStream out = Files.newOutputStream(
                        destination, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) out.write(buf, 0, n);
            }
        }
    }

    private static InstanceStrategyLauncher.LaunchParameters paramsForDebian(
            InstanceLifecycle.Instance inst, Path distroInputsDir, Path instanceRuntimeDir) {
        return new InstanceStrategyLauncher.LaunchParameters(
                inst.id(), inst.bridgeToken(), DEBIAN_TAR, inst.rootVolume().toString(),
                instanceRuntimeDir.toString(), distroInputsDir.toString(), inst.guestPassword(),
                inst.hardwareConfig(), null, null, null, inst.hostname(),
                inst.defaultUser(), inst.hasSudo(), null, inst.kernelProfile());
    }

    private static InstanceStrategyLauncher.LaunchParameters paramsForAlpine(
            InstanceLifecycle.Instance inst, Path distroInputsDir, Path instanceRuntimeDir) {
        return new InstanceStrategyLauncher.LaunchParameters(
                inst.id(), inst.bridgeToken(), ALPINE_TAR, inst.rootVolume().toString(),
                instanceRuntimeDir.toString(), distroInputsDir.toString(), inst.guestPassword(),
                inst.hardwareConfig(), null, null, null, inst.hostname(),
                inst.defaultUser(), inst.hasSudo(), null, inst.kernelProfile());
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

    private static Properties loadProps(Path propsPath) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(propsPath)) {
            p.load(in);
        }
        return p;
    }

    private static void awaitPromptAndBridge(
            ExecutionSessionRegistry.LiveSession live,
            ExecutionSession session,
            String bridgeToken,
            long promptTimeoutMs,
            String threadName) throws Exception {
        AtomicReference<Throwable> bridgeErr = new AtomicReference<>();
        CountDownLatch bridgeDone = new CountDownLatch(1);
        Thread bridgeThread = new Thread(() -> {
            try {
                authenticateBridge(session, bridgeToken);
            } catch (Throwable t) {
                bridgeErr.set(t);
            } finally {
                bridgeDone.countDown();
            }
        }, threadName);
        bridgeThread.setDaemon(true);
        bridgeThread.start();

        awaitPrompt(live, promptTimeoutMs);
        if (!bridgeDone.await(BRIDGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IOException("bridge-auth-timeout");
        }
        if (bridgeErr.get() != null) {
            Throwable t = bridgeErr.get();
            throw (t instanceof Exception e) ? e : new IOException("bridge-error", t);
        }
    }

    private static void cleanupSessionSafely(
            ExecutionSession session,
            InstanceLifecycle.FileStore store,
            InstanceLifecycle.Controller controller,
            String instanceId,
            String generation,
            Context fgContextOrNull) {
        if (session != null) {
            try {
                stopAnySession(session);
            } catch (Throwable ignored) {
            }
            ExecutionSessionRegistry.unregister(instanceId, session);
        }
        if (fgContextOrNull != null) {
            OrchestellaForegroundService.stop(fgContextOrNull, instanceId, generation);
        }
        InstanceLaunchGuard.end(instanceId, generation);
        reconcileStopState(store, controller, instanceId);
    }

    private static void reconcileStopState(
            InstanceLifecycle.FileStore store,
            InstanceLifecycle.Controller controller,
            String instanceId) {
        try {
            InstanceLifecycle.Instance cur = store.load(instanceId);
            if (cur.state() == InstanceLifecycle.State.RUNNING
                    || cur.state() == InstanceLifecycle.State.SUSPENDED) {
                controller.recordNormalGuestStop(instanceId);
            } else if (cur.state() == InstanceLifecycle.State.CRASHED
                    || cur.state() == InstanceLifecycle.State.INTERRUPTED) {
                controller.resetToStopped(instanceId);
            }
        } catch (IOException ignored) {
        }
    }

    private static void clearRules(PortForwardingManager mgr) throws IOException {
        for (PortForwardingRule rule : List.copyOf(mgr.listRules())) {
            mgr.removeRule(rule.id());
        }
    }

    private static void awaitForegroundLease(int expectedCount, boolean expectedWake, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            OrchestellaForegroundService.LeaseSnapshot snap = OrchestellaForegroundService.leaseSnapshot();
            if (snap.activeCount() >= expectedCount && snap.wakeLockHeld() == expectedWake) {
                return;
            }
            SystemClock.sleep(50L);
        }
        OrchestellaForegroundService.LeaseSnapshot snap = OrchestellaForegroundService.leaseSnapshot();
        require(snap.activeCount() >= expectedCount && snap.wakeLockHeld() == expectedWake,
                "foreground lease mismatch: count=" + snap.activeCount() + " wake=" + snap.wakeLockHeld());
    }

    private static void assertZeroState(String instanceId, Path umlRootVolume) {
        require(ExecutionSessionRegistry.get(instanceId) == null, "session leaked");
        require(!InstanceLaunchGuard.isInFlight(instanceId), "guard leaked");
        if (umlRootVolume != null) {
            require(!UmlRootReservationRegistry.defaultRegistry().isReserved(umlRootVolume),
                    "root reservation leaked");
        }
        require(UmlInstanceBudget.defaultBudget().committedStandaloneMb() == 0, "budget leaked");
        require(LoopbackEndpointRegistry.defaultRegistry().activeEndpointCount() == 0, "endpoint leaked");
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
                    "__W0105_SD__",
                    10_000L);
            if (last.contains("MU=active")
                    && last.contains("GA=active")
                    && last.contains("SR=SYSTEMD_READY")) {
                return last;
            }
            SystemClock.sleep(500L);
        }
        throw new IOException("systemd multi-user did not become active: " + last);
    }

    private long readGuestUptimeMs(ExecutionSessionRegistry.LiveSession live) throws Exception {
        String out = runGuestCmd(live, "cut -d' ' -f1 /proc/uptime", "__W0105_UP__", 10_000L);
        String cleaned = ANSI_ESCAPE_PATTERN.matcher(out).replaceAll("");
        for (String line : cleaned.split("\\r?\\n")) {
            String t = line.trim();
            if (t.matches("\\d+(?:\\.\\d+)?")) {
                return Math.round(Double.parseDouble(t) * 1000.0);
            }
        }
        throw new IOException("unable to parse uptime: " + out);
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
            String full = command + "\nprintf '\\n%s%s\\n' '" + halfA + "' '" + halfB + "'\n";
            live.sendBytes(full.getBytes(StandardCharsets.UTF_8));
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                synchronized (captured) {
                    int start = Math.max(0, captured.length() - MAX_TIMEOUT_TAIL_CHARS);
                    throw new IOException("timeout waiting for " + endToken + ", seen=" + captured.substring(start));
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
            SystemClock.sleep(10L);
        }
        throw new IOException("shell prompt timeout (" + timeoutMs + "ms)");
    }

    private static int pickFreeUserPort(String instanceId, int... avoid) throws IOException {
        int derived = GatewaySlotArbiter.derivedPort(instanceId);
        for (int attempt = 0; attempt < 16; attempt++) {
            int candidate;
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("127.0.0.1", 0));
                candidate = socket.getLocalPort();
            }
            if (PortForwardingRule.isReservedHostPort(candidate) || candidate == derived) continue;
            boolean clash = false;
            for (int f : avoid) {
                if (candidate == f) {
                    clash = true;
                    break;
                }
            }
            if (!clash) return candidate;
        }
        throw new IOException("no free user port available");
    }

    private static boolean isBound(int port) throws IOException {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException ignored) {
        }
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return false;
        } catch (BindException bound) {
            return true;
        }
    }

    private static String readHttpWithRetry(int hostPort, long timeoutMs) throws IOException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        IOException lastErr = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                String resp = readHttp(hostPort);
                if (!resp.isEmpty()) return resp;
            } catch (IOException err) {
                lastErr = err;
            }
            SystemClock.sleep(150L);
        }
        throw lastErr != null ? lastErr : new IOException("http retry timeout");
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

    private static void verifyNoTempResidue(Path rootsDir, Path runtimeDir) throws IOException {
        if (Files.isDirectory(rootsDir)) {
            try (var stream = Files.list(rootsDir)) {
                for (Path p : stream.collect(Collectors.toList())) {
                    require(!p.getFileName().toString().contains(".part"), "leftover .part volume");
                }
            }
        }
        if (Files.isDirectory(runtimeDir)) {
            try (var stream = Files.list(runtimeDir)) {
                for (Path p : stream.collect(Collectors.toList())) {
                    String name = p.getFileName().toString();
                    require(
                            !name.startsWith("boot-creds-")
                                    && !name.startsWith("uml-prov-")
                                    && !name.startsWith("passt-"),
                            "leftover temp dir/file: " + name);
                }
            }
        }
    }

    private static void deleteTreeBestEffort(Path dir) throws IOException {
        if (dir == null || Files.notExists(dir)) return;
        try (var walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted((a, b) -> b.compareTo(a)).collect(Collectors.toList());
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static String extractResultLine(String full, String prefix) {
        String cleaned = ANSI_ESCAPE_PATTERN.matcher(full).replaceAll("");
        for (String line : cleaned.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) return trimmed;
        }
        return "";
    }

    private static String extractField(String line, String key) {
        int idx = line.indexOf(key);
        if (idx < 0) return "";
        int start = idx + key.length();
        int end = line.indexOf(';', start);
        return end < 0 ? line.substring(start).trim() : line.substring(start, end).trim();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String sha256File(Path path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[65_536];
        try (InputStream in = Files.newInputStream(path)) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) md.update(buf, 0, n);
            }
        }
        return toHex(md.digest());
    }

    private static String sha256Bytes(byte[] data) throws Exception {
        return toHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static String toHex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static int parsePositiveInt(String raw, int fallback) {
        try {
            int v = Integer.parseInt(raw);
            return v > 0 ? v : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String sanitizeDetail(String detail) {
        if (detail == null) return null;
        String clean = detail.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() > 160) clean = clean.substring(0, 160);
        return clean.isEmpty() ? null : clean;
    }

    private void emitSampleJson(SampleData sample) {
        Bundle status = new Bundle();
        status.putString("w0105_sample_json", sample.toJson());
        sendStatus(0, status);
    }

    private void reportStage(String next) {
        this.stage = next;
        Bundle b = new Bundle();
        b.putString("w0105_stage", next);
        sendStatus(0, b);
    }

    private static void require(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static final class SampleData {
        String bucket;
        String variant;
        int pairIndex;
        int iteration;
        String engine;
        String profile;
        String distro;
        String distroVersion;
        int vcpu;
        int ramMb;
        String networkMode;
        String status;
        Long t0ToBridgeReadyMs;
        Long t0ToSystemdReadyMs;
        Long t0ToShellReadyMs;
        Long t0ToFirstCmdEchoMs;
        Long t0ToDockerDaemonReadyMs;
        Long t0ToContainerHttpReadyMs;
        Long reattachMs;
        Long roundtripMs;
        String credentialsOnlyReason;
        String stopResult;
        String error;
        String detail;

        static SampleData failure(
                String eng, String profile, String distro, String distroVer, int vcpu, int ramMb,
                String netMode, int iter, int pairIdx, String bucket, String err, String detail) {
            SampleData s = new SampleData();
            s.engine = eng;
            s.profile = profile;
            s.distro = distro;
            s.distroVersion = distroVer;
            s.vcpu = vcpu;
            s.ramMb = ramMb;
            s.networkMode = netMode;
            s.iteration = iter;
            s.pairIndex = pairIdx;
            s.bucket = bucket;
            s.status = "FAIL";
            s.error = err;
            s.detail = sanitizeDetail(detail);
            return s;
        }

        String toJson() {
            StringBuilder sb = new StringBuilder(320);
            sb.append('{');
            addStr(sb, "bucket", bucket, true);
            addStr(sb, "variant", variant, false);
            addInt(sb, "pair_index", pairIndex);
            addInt(sb, "iteration", iteration);
            addStr(sb, "engine", engine, false);
            addStr(sb, "profile", profile, false);
            addStr(sb, "distro", distro, false);
            addStr(sb, "distro_version", distroVersion, false);
            addInt(sb, "vcpu", vcpu);
            addInt(sb, "ram_mb", ramMb);
            addStr(sb, "network_mode", networkMode, false);
            addStr(sb, "status", status, false);
            addNum(sb, "t0_to_bridge_ready_ms", t0ToBridgeReadyMs);
            addNum(sb, "t0_to_systemd_ready_ms", t0ToSystemdReadyMs);
            addNum(sb, "t0_to_shell_ready_ms", t0ToShellReadyMs);
            addNum(sb, "t0_to_first_cmd_echo_ms", t0ToFirstCmdEchoMs);
            addNum(sb, "t0_to_docker_daemon_ready_ms", t0ToDockerDaemonReadyMs);
            addNum(sb, "t0_to_container_http_ready_ms", t0ToContainerHttpReadyMs);
            addNum(sb, "reattach_ms", reattachMs);
            addNum(sb, "roundtrip_ms", roundtripMs);
            addStr(sb, "credentials_only_reason", credentialsOnlyReason, false);
            addStr(sb, "stop_result", stopResult, false);
            addStr(sb, "error", error, false);
            addStr(sb, "detail", detail, false);
            sb.append('}');
            return sb.toString();
        }

        private static void addStr(StringBuilder sb, String k, String v, boolean first) {
            if (!first) sb.append(',');
            sb.append('"').append(k).append("\":");
            if (v == null) sb.append("null");
            else sb.append('"').append(esc(v)).append('"');
        }

        private static void addInt(StringBuilder sb, String k, int v) {
            sb.append(",\"").append(k).append("\":").append(v);
        }

        private static void addNum(StringBuilder sb, String k, Long v) {
            sb.append(",\"").append(k).append("\":").append(v == null ? "null" : v.toString());
        }

        private static String esc(String text) {
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
                        if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                        else out.append(c);
                    }
                }
            }
            return out.toString();
        }
    }
}
