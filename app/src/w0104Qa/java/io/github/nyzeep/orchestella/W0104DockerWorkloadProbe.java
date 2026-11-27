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
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.contract.Protocol;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlDistroCapabilityCatalog;
import io.github.nyzeep.orchestella.runtime.instance.GatewaySlotArbiter;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import io.github.nyzeep.orchestella.runtime.network.LoopbackEndpointRegistry;
import io.github.nyzeep.orchestella.runtime.network.PortForwardingManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Isolated QA 4 KB physical-device instrumentation probe for W01-04:
 * Debian 13 (trixie) signed Docker package closure, minimal OCI runc bundle + SysV IPC isolation,
 * systemd-managed Docker Engine (overlay2/overlayfs, cgroup v2, default seccomp),
 * ARM64 Dockerfile build (RUN) + container network/DNS/TLS + cgroup limits + named volumes,
 * explicit Android loopback port forwarding + conflict/reclaim, and full-instance reboot persistence.
 */
public final class W0104DockerWorkloadProbe extends Instrumentation {
    private static final String DEBIAN_TAR = "debian-13-rootfs.tar.xz";
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
    private static final long SYSTEMD_ACTIVE_TIMEOUT_MS = 45_000L;
    private static final long STOP_GRACE_MS = 8_000L;
    private static final int MAX_TIMEOUT_TAIL_CHARS = 4_096;
    private static final Pattern ANSI_ESCAPE_PATTERN =
            Pattern.compile("\\u001B(?:\\[[0-9;?]*[A-Za-z]|\\][^\\u0007]*\\u0007)");

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
        Path probeRoot = null;
        try {
            Context target = getTargetContext();
            require(BuildConfig.IS_ISOLATED_QA_BUILD, "probe requires isolated QA build");
            probeRoot = target.getFilesDir().toPath().resolve("w0104-probe");
            deleteTreeBestEffort(probeRoot);
            Files.createDirectories(probeRoot);

            if ("all".equals(action) || "preflight".equals(action)) {
                runPreflight(target, result);
            }
            if ("all".equals(action) || "workload".equals(action)) {
                runDockerWorkloadAndPersistence(target, probeRoot, result);
            }
            result.putString("w0104_status", "PASS");
            result.putString("w0104_stage", stage);
            code = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("w0104_status", "FAIL");
            result.putString("w0104_stage", stage);
            result.putString(
                    "w0104_error", failure.getClass().getName() + ": " + failure.getMessage());
        } finally {
            if (probeRoot != null) {
                try {
                    deleteTreeBestEffort(probeRoot);
                } catch (IOException ignored) {
                }
            }
        }
        finish(code, result);
    }

    private void runPreflight(Context target, Bundle out) throws Exception {
        reportStage("preflight");
        ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight fullPre =
                StandaloneUmlPreflightProbe.forAndroid(
                        target, UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64).probe();
        require(
                fullPre.status()
                        == ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.READY,
                "full preflight failed: " + fullPre.status() + " " + fullPre.detail());
        require(fullPre.hostPageSizeBytes() == 4096, "expected 4 KB host");

        boolean fastRefused = false;
        try {
            UmlDistroCapabilityCatalog.requireCreatable(
                    "debian", UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64);
        } catch (UmlDistroCapabilityCatalog.DistroNotCreatableException expected) {
            fastRefused = true;
        }
        require(fastRefused, "FAST_UP_ARM64 must refuse Debian");

        Path filesDir = target.getFilesDir().toPath();
        Path debianTar = filesDir.resolve("distro-inputs").resolve(DEBIAN_TAR);
        require(Files.isRegularFile(debianTar), "missing Debian tarball: " + debianTar);

        Path fixturesDir = filesDir.resolve("w0104-fixtures");
        Path lockFile = fixturesDir.resolve("w0104-lock.json");
        Path baseImgTar = fixturesDir.resolve(BASE_IMAGE_TAR_NAME);
        Path keyring = fixturesDir.resolve("apt-repo/qa-w0104-archive.gpg");
        Path inRelease = fixturesDir.resolve("apt-repo/dists/trixie/InRelease");
        require(Files.isRegularFile(lockFile), "missing w0104-lock.json");
        require(Files.isRegularFile(baseImgTar), "missing base-image-arm64.tar");
        require(Files.isRegularFile(keyring), "missing qa-w0104-archive.gpg");
        require(Files.isRegularFile(inRelease), "missing offline InRelease");
        String actualImgSha = sha256File(baseImgTar);
        require(
                BASE_IMAGE_TAR_SHA256.equals(actualImgSha),
                "base-image-arm64.tar sha256 mismatch: " + actualImgSha);

        out.putInt("host_page_size", fullPre.hostPageSizeBytes());
        out.putString("base_image_tar_sha256", actualImgSha);
    }

    private void runDockerWorkloadAndPersistence(Context target, Path probeRoot, Bundle out)
            throws Exception {
        Path filesDir = target.getFilesDir().toPath();
        Path distroInputsDir = filesDir.resolve("distro-inputs");
        Path fixturesDir = filesDir.resolve("w0104-fixtures");
        Path storeDir = probeRoot.resolve("instances");
        Path rootsDir = probeRoot.resolve("roots");
        Path runtimeRoot = Files.createDirectories(probeRoot.resolve("runtime"));

        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller =
                new InstanceLifecycle.Controller(store, Clock.systemUTC());
        IsolatedQaExecutionEntry qa = new IsolatedQaExecutionEntry();

        reportStage("boot1_create");
        InstanceLifecycle.Instance debian =
                qa.ensurePersistentDebianInstance(
                        store,
                        controller,
                        rootsDir,
                        null,
                        IsolatedQaExecutionEntry.DEFAULT_DEBIAN_RAM_MB,
                        DISK_QUOTA_8G_BYTES,
                        IsolatedQaExecutionEntry.DEFAULT_DEBIAN_QA_HOSTNAME);
        require(debian.hardwareConfig().diskCapacityBytes() == DISK_QUOTA_8G_BYTES,
                "expected 8 GiB disk quota");

        int hostPort = pickFreeUserPort(debian.id());
        int deadHostPort = pickFreeUserPort(debian.id(), hostPort);
        PortForwardingManager fwdMgr =
                PortForwardingManager.forInstance(debian.id(), filesDir.resolve("network"));
        for (PortForwardingRule rule : List.copyOf(fwdMgr.listRules())) {
            fwdMgr.removeRule(rule.id());
        }

        try {
            reportStage("boot1_launch");
            String volShaBoot1 =
                    runBoot1(
                            target,
                            store,
                            controller,
                            distroInputsDir,
                            fixturesDir,
                            runtimeRoot,
                            debian,
                            fwdMgr,
                            hostPort,
                            deadHostPort,
                            out);

            require(!isBound(hostPort), "hostPort still bound after boot1 poweroff");
            require(UmlInstanceLauncher.areLeasesReleased(debian.id(), debian.rootVolume()),
                    "leases leaked after boot1");

            reportStage("boot2_launch");
            InstanceLifecycle.Instance reloaded = store.load(debian.id());
            runBoot2(
                    target,
                    store,
                    controller,
                    distroInputsDir,
                    runtimeRoot,
                    reloaded,
                    fwdMgr,
                    hostPort,
                    volShaBoot1,
                    out);

            require(!isBound(hostPort), "hostPort still bound after boot2 cleanup");
            require(UmlInstanceLauncher.areLeasesReleased(debian.id(), debian.rootVolume()),
                    "leases leaked after boot2");
        } finally {
            for (PortForwardingRule rule : List.copyOf(fwdMgr.listRules())) {
                try {
                    fwdMgr.removeRule(rule.id());
                } catch (IOException ignored) {
                }
            }
        }
    }

    private String runBoot1(
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
            Bundle out)
            throws Exception {
        String gen = InstanceLaunchGuard.begin(inst.id());
        require(gen != null, "launch guard busy");
        InstanceLaunchSequence.beforeLaunch(
                controller, inst, InstanceLifecycle.ConcurrencyPolicy.SINGLE_RUNNING_INSTANCE);
        Path instanceRuntimeDir = Files.createDirectories(runtimeRoot.resolve(inst.id()));
        ExecutionSession session = null;
        try {
            UmlInstanceLauncher launcher = new UmlInstanceLauncher(target, store);
            session = launcher.launch(paramsFor(inst, distroInputsDir, instanceRuntimeDir));
            UmlInstanceLauncher.Session umlSession = (UmlInstanceLauncher.Session) session;
            ExecutionSessionRegistry.LiveSession live =
                    ExecutionSessionRegistry.getLiveSession(inst.id());
            require(live != null, "missing LiveSession");

            awaitPrompt(live, PROVISION_PROMPT_TIMEOUT_MS);
            authenticateBridge(session, inst.bridgeToken());
            awaitSystemdActive(live, SYSTEMD_ACTIVE_TIMEOUT_MS);
            umlSession.markShellReady();

            long netDeadline = SystemClock.elapsedRealtime() + 10_000L;
            while (SystemClock.elapsedRealtime() < netDeadline
                    && umlSession.networkStatus() != UmlPasstNetwork.Status.READY) {
                SystemClock.sleep(100L);
            }
            out.putString("boot1_net_status", umlSession.networkStatus().name());
            out.putString("boot1_cred_outcome", umlSession.launchTimeline().credentialsOnlyOutcome().name());

            // 1. Mount read-only QA fixtures & install signed Debian package closure + verify online APT chain
            reportStage("boot1_install_packages");
            String hostFixturesPath = fixturesDir.toAbsolutePath().toString();
            String mountRes =
                    runGuestCmd(
                            live,
                            "mkdir -p /mnt/w0104-fixtures && (mount -t hostfs -o "
                                    + shellQuote("hostfs=" + hostFixturesPath)
                                    + " none /mnt/w0104-fixtures || LIBMOUNT_FORCE_MOUNT2=always mount -t hostfs -o "
                                    + shellQuote(hostFixturesPath)
                                    + " none /mnt/w0104-fixtures) && test -r /mnt/w0104-fixtures/w0104-lock.json "
                                    + "&& printf '%s%s\\n' 'HOSTFS_' 'OK' || printf '%s%s\\n' 'HOSTFS_' 'FAIL'",
                            "__W0104_MNT__",
                            15_000L);
            require(mountRes.contains("HOSTFS_OK"), "hostfs fixtures mount failed: " + mountRes);

            String onlineAptRes =
                    runGuestCmd(
                            live,
                            "timeout -k 5 120 apt-get update -o Acquire::ForceIPv4=true -o Acquire::Retries=2 -o Acquire::http::Timeout=15 -o Acquire::Languages=none </dev/null >/tmp/online-apt.log 2>&1 "
                                    + "&& printf '%s%s\\n' 'ONLINE_APT_' 'OK' "
                                    + "|| printf '%s%s=%s\\n' 'ONLINE_APT_' 'ERR' \"$(tail -n 2 /tmp/online-apt.log | tr '\\n' ' ')\"",
                            "__W0104_ONLINE_APT__",
                            135_000L);
            out.putString("boot1_online_apt",
                    onlineAptRes.contains("ONLINE_APT_OK")
                            ? "ONLINE_APT_OK"
                            : extractResultLine(onlineAptRes, "ONLINE_APT_ERR="));

            String offlineUpdRes =
                    runGuestCmd(
                            live,
                            "sh -ec '"
                                    + "printf \"deb [signed-by=/mnt/w0104-fixtures/apt-repo/qa-w0104-archive.gpg] file:///mnt/w0104-fixtures/apt-repo trixie main\\n\" >/etc/apt/sources.list.d/w0104-offline.list; "
                                    + "trap \"rm -f /etc/apt/sources.list.d/w0104-offline.list\" EXIT; "
                                    + "timeout -k 5 45 apt-get update -o Dir::Etc::sourcelist=/etc/apt/sources.list.d/w0104-offline.list -o Dir::Etc::sourceparts=- "
                                    + "-o Acquire::AllowInsecureRepositories=false -o Acquire::AllowDowngradeToInsecureRepositories=false -o APT::Get::AllowUnauthenticated=false </dev/null >/tmp/offline-apt-upd.log 2>&1"
                                    + "' && printf '%s%s\\n' 'OFFLINE_UPD_' 'OK' "
                                    + "|| { printf '%s%s\\n' 'OFFLINE_UPD_' 'FAIL'; cat /tmp/offline-apt-upd.log 2>/dev/null || :; }",
                            "__W0104_OFFLINE_UPD__",
                            60_000L);
            require(offlineUpdRes.contains("OFFLINE_UPD_OK"),
                    "offline apt-get update failed: " + offlineUpdRes);

            String offlineInstRes =
                    runGuestCmd(
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
                                    + "|| { printf '%s%s\\n' 'OFFLINE_INST_' 'FAIL'; tail -n 60 /tmp/offline-apt.log 2>/dev/null || :; }",
                            "__W0104_OFFLINE_INST__",
                            700_000L);
            require(offlineInstRes.contains("OFFLINE_INST_OK"),
                    "offline apt-get install failed: " + offlineInstRes);

            String offlineAptRes =
                    runGuestCmd(
                            live,
                            "python3 - <<'PY'\n"
                                    + "import hashlib, json, pathlib, subprocess\n"
                                    + "lock = json.loads(pathlib.Path('/mnt/w0104-fixtures/w0104-lock.json').read_text())\n"
                                    + "for p in lock['packages']:\n"
                                    + "    deb = pathlib.Path('/mnt/w0104-fixtures/apt-repo') / p['filename']\n"
                                    + "    assert hashlib.sha256(deb.read_bytes()).hexdigest() == p['sha256'], p['name']\n"
                                    + "    installed = subprocess.check_output(['dpkg-query', '-W', '-f=${Status}\\t${Version}', p['name']], text=True).strip()\n"
                                    + "    assert installed == 'install ok installed\\t' + p['version'], (p['name'], installed, p['version'])\n"
                                    + "print('CLOSURE_' + 'VERIFIED=' + str(len(lock['packages'])))\n"
                                    + "PY",
                            "__W0104_OFFLINE_APT__",
                            30_000L);
            out.putString("boot1_offline_apt", extractResultLine(offlineAptRes, "CLOSURE_VERIFIED="));
            require(offlineAptRes.contains("CLOSURE_VERIFIED=25"),
                    "offline signed package closure verification failed: " + offlineAptRes);

            // 2. CLI-only vs daemon-unavailable proof (before starting docker.service)
            reportStage("boot1_cli_vs_daemon");
            String cliSepRes =
                    runGuestCmd(
                            live,
                            "systemctl stop docker.service docker.socket 2>/dev/null || :; "
                                    + "docker --version && ! docker info >/dev/null 2>&1 "
                                    + "&& printf '%s%s\\n' 'CLI_WITHOUT_' 'DAEMON_OK'",
                            "__W0104_CLISEP__",
                            20_000L);
            require(cliSepRes.contains("CLI_WITHOUT_DAEMON_OK"),
                    "CLI vs daemon separation failed: " + cliSepRes);
            out.putBoolean("cli_without_daemon_ok", true);

            // 3. Minimal OCI bundle (runc) + SysV IPC namespace isolation fixture
            reportStage("boot1_oci_and_ipc");
            String ociIpcRes =
                    runGuestCmd(
                            live,
                            "python3 - <<'PY'\n"
                                    + "import json, pathlib, shutil, subprocess, tarfile\n"
                                    + "base = pathlib.Path('/tmp/w0104-oci')\n"
                                    + "shutil.rmtree(base, ignore_errors=True)\n"
                                    + "rootfs = base / 'rootfs'\n"
                                    + "rootfs.mkdir(parents=True)\n"
                                    + "with tarfile.open('/mnt/w0104-fixtures/base-image-arm64.tar', 'r') as tf:\n"
                                    + "    m = json.loads(tf.extractfile('manifest.json').read())\n"
                                    + "    layer = tf.extractfile(m[0]['Layers'][0])\n"
                                    + "    with tarfile.open(fileobj=layer, mode='r:*') as lf:\n"
                                    + "        lf.extractall(rootfs)\n"
                                    + "subprocess.check_call(['runc', 'spec'], cwd=base)\n"
                                    + "cfg_path = base / 'config.json'\n"
                                    + "cfg = json.loads(cfg_path.read_text())\n"
                                    + "cfg['process']['terminal'] = False\n"
                                    + "cfg['process']['args'] = ['/bin/sh', '-ec', 'touch /oci-rw && rm /oci-rw && printf \"OCI_OK;PID=%s;CG=%s;SEC=%s\\n\" \"$$\" \"$(stat -fc %t /sys/fs/cgroup)\" \"$(grep ^Seccomp: /proc/self/status | tr -dc 0-9)\"']\n"
                                    + "cfg['root']['readonly'] = False\n"
                                    + "cfg['linux']['seccomp'] = {'defaultAction': 'SCMP_ACT_ALLOW', 'architectures': ['SCMP_ARCH_AARCH64'], 'syscalls': [{'names': ['kcmp'], 'action': 'SCMP_ACT_ERRNO'}]}\n"
                                    + "cfg_path.write_text(json.dumps(cfg))\n"
                                    + "oci_out = subprocess.check_output(['runc', 'run', 'w0104-oci-min'], cwd=base, text=True).strip()\n"
                                    + "assert 'OCI_OK;PID=1;CG=63677270;SEC=2' in oci_out, oci_out\n"
                                    + "assert subprocess.check_output(['runc', 'list', '-q'], text=True).strip() == ''\n"
                                    + "shutil.rmtree(base, ignore_errors=True)\n"
                                    + "print('MIN_' + 'OCI=' + oci_out)\n"
                                    + "PY\n"
                                    + "python3 - <<'PY'\n"
                                    + "import ctypes, subprocess, sys\n"
                                    + "libc = ctypes.CDLL('libc.so.6', use_errno=True)\n"
                                    + "libc.shmat.restype = ctypes.c_void_p\n"
                                    + "KEY, IPC_CREAT, IPC_EXCL, IPC_RMID = 0x57303134, 0o1000, 0o2000, 0\n"
                                    + "def write_shm(msg):\n"
                                    + "    shmid = libc.shmget(KEY, 4096, IPC_CREAT | IPC_EXCL | 0o600)\n"
                                    + "    assert shmid >= 0, ctypes.get_errno()\n"
                                    + "    addr = libc.shmat(shmid, None, 0)\n"
                                    + "    assert addr != ctypes.c_void_p(-1).value\n"
                                    + "    ctypes.memmove(addr, msg, len(msg))\n"
                                    + "    libc.shmdt(ctypes.c_void_p(addr))\n"
                                    + "    return shmid\n"
                                    + "def read_shm(shmid):\n"
                                    + "    addr = libc.shmat(shmid, None, 0)\n"
                                    + "    data = ctypes.string_at(addr, 32).split(b'\\x00', 1)[0]\n"
                                    + "    libc.shmdt(ctypes.c_void_p(addr))\n"
                                    + "    return data\n"
                                    + "sid = write_shm(b'IPC_HOST_MARK')\n"
                                    + "try:\n"
                                    + "    assert read_shm(libc.shmget(KEY, 4096, 0)) == b'IPC_HOST_MARK'\n"
                                    + "    priv = subprocess.check_output(['unshare', '--ipc', sys.executable, '-c', "
                                    + "'import ctypes; l=ctypes.CDLL(\"libc.so.6\"); l.shmat.restype=ctypes.c_void_p; s=l.shmget(0x57303134,4096,0o1000|0o2000|0o600); assert s>=0; a=l.shmat(s,None,0); ctypes.memmove(a,b\"IPC_PRIV_MARK\",13); l.shmdt(ctypes.c_void_p(a)); a2=l.shmat(s,None,0); print(ctypes.string_at(a2,13).decode()); l.shmdt(ctypes.c_void_p(a2)); l.shmctl(s,0,None)'], text=True).strip()\n"
                                    + "    assert priv == 'IPC_PRIV_MARK', priv\n"
                                    + "    assert read_shm(sid) == b'IPC_HOST_MARK'\n"
                                    + "finally:\n"
                                    + "    libc.shmctl(sid, IPC_RMID, None)\n"
                                    + "assert '57303134' not in subprocess.check_output(['ipcs', '-m'], text=True)\n"
                                    + "print('SYSV_IPC_' + 'OK')\n"
                                    + "PY",
                            "__W0104_OCI_IPC__",
                            90_000L);
            require(ociIpcRes.contains("MIN_OCI=OCI_OK;PID=1;CG=63677270;SEC=2"),
                    "minimal OCI bundle failed: " + ociIpcRes);
            require(ociIpcRes.contains("SYSV_IPC_OK"), "SysV IPC isolation failed: " + ociIpcRes);
            out.putString("min_oci_summary", extractResultLine(ociIpcRes, "MIN_OCI="));

            // 4. Start systemd containerd + docker.service and inspect docker info
            reportStage("boot1_docker_daemon");
            String daemonRes =
                    runGuestCmd(
                            live,
                            "systemctl start containerd.service docker.service "
                                    + "&& systemctl is-active containerd.service docker.service >/dev/null "
                                    + "&& python3 - <<'PY'\n"
                                    + "import json, subprocess\n"
                                    + "info = json.loads(subprocess.check_output(['docker', 'info', '--format', '{{json .}}'], text=True))\n"
                                    + "sec = [str(x) for x in (info.get('SecurityOptions') or [])]\n"
                                    + "assert info.get('ServerVersion'), info\n"
                                    + "assert str(info.get('CgroupVersion')) == '2', info\n"
                                    + "assert any('name=seccomp' in s and 'profile=unconfined' not in s for s in sec), sec\n"
                                    + "assert info.get('Driver') in ('overlay2', 'overlayfs'), info.get('Driver')\n"
                                    + "print('DOCKER_' + 'INFO;VER=%s;DRV=%s;CG=%s:%s;RT=%s;SEC=%s' % (info['ServerVersion'], info['Driver'], info['CgroupVersion'], info.get('CgroupDriver'), info.get('DefaultRuntime'), '|'.join(sec)))\n"
                                    + "PY",
                            "__W0104_DINFO__",
                            60_000L);
            require(daemonRes.contains("DOCKER_INFO;"), "docker daemon info failed: " + daemonRes);
            out.putString("docker_info", extractResultLine(daemonRes, "DOCKER_INFO;"));

            // 5. Load pinned ARM64 image, build ARM64 app with RUN, test cgroup limits, bridge DNS/TLS, named volume
            reportStage("boot1_build_and_workload");
            String buildAndLoadRes =
                    runGuestCmd(
                            live,
                            "sh -ec '"
                                    + "docker load -i /mnt/w0104-fixtures/base-image-arm64.tar; "
                                    + "IMG_ID=$(docker image inspect alpine:3.22.2 --format \"{{.Id}}\"); "
                                    + "test \"$IMG_ID\" = \"" + BASE_IMAGE_ID + "\"; "
                                    + "mkdir -p /tmp/w0104-build' "
                                    + "&& cat >/tmp/w0104-build/Dockerfile <<'EOF'\n"
                                    + "FROM alpine:3.22.2\n"
                                    + "RUN mkdir -p /app && printf 'BUILD_ARM64_RUN_OK' >/app/build.marker && uname -m >/app/arch.txt\n"
                                    + "RUN printf '%s\\n' '#!/bin/sh' 'IFS= read -r _ || :' 'BODY=$(printf \"APP=w0104;BUILD=%s;ARCH=%s;VOL=%s\\n\" \"$(cat /app/build.marker)\" \"$(cat /app/arch.txt)\" \"$(cat /data/persist.txt 2>/dev/null || echo NONE)\")' 'LEN=$(printf \"%s\" \"$BODY\" | wc -c)' 'printf \"HTTP/1.0 200 OK\\r\\nContent-Length: %s\\r\\n\\r\\n%s\" \"$LEN\" \"$BODY\"' >/app/handle.sh && chmod 0755 /app/handle.sh\n"
                                    + "EXPOSE 8080\n"
                                    + "CMD [\"nc\", \"-ll\", \"-p\", \"8080\", \"-e\", \"/app/handle.sh\"]\n"
                                    + "EOF\n"
                                    + "sh -ec '"
                                    + "docker build --pull=false --platform linux/arm64 -t orchestella-w0104-app:v1 /tmp/w0104-build >/tmp/docker-build.log 2>&1; "
                                    + "APP_ID=$(docker image inspect orchestella-w0104-app:v1 --format \"{{.Id}}|{{.Architecture}}|{{.Os}}\"); "
                                    + "IMG_ID=$(docker image inspect alpine:3.22.2 --format \"{{.Id}}\"); "
                                    + "printf \"%s%s=%s;BASE=%s\\n\" \"BUILD_\" \"OK\" \"$APP_ID\" \"$IMG_ID\"' "
                                    + "|| { printf '%s%s\\n' 'BUILD_' 'FAIL'; tail -n 30 /tmp/docker-build.log 2>/dev/null || :; }",
                            "__W0104_BUILD__",
                            180_000L);
            require(buildAndLoadRes.contains("BUILD_OK=") && buildAndLoadRes.contains("|arm64|linux"),
                    "docker build failed: " + buildAndLoadRes);
            out.putString("arm64_build", extractResultLine(buildAndLoadRes, "BUILD_OK="));

            String workloadRes =
                    runGuestCmd(
                            live,
                            "python3 - <<'PY'\n"
                                    + "import subprocess\n"
                                    + "subprocess.check_call(['docker', 'network', 'create', 'w0104-net'], stdout=subprocess.DEVNULL)\n"
                                    + "subprocess.check_call(['docker', 'volume', 'create', 'w0104-data'], stdout=subprocess.DEVNULL)\n"
                                    + "subprocess.check_call([\n"
                                    + "    'docker', 'run', '-d', '--name', 'w0104-srv', '--network', 'w0104-net',\n"
                                    + "    '--memory=128m', '--pids-limit=64', '-v', 'w0104-data:/data',\n"
                                    + "    '-p', '127.0.0.1:" + GUEST_CONTAINER_HOST_PORT + ":8080', 'orchestella-w0104-app:v1'\n"
                                    + "], stdout=subprocess.DEVNULL)\n"
                                    + "srv_script = (\n"
                                    + "    'echo copyup-ok >/app/copyup.txt && '\n"
                                    + "    'test \"$(cat /app/copyup.txt)\" = copyup-ok && '\n"
                                    + "    'printf \"%s\\\\n\" w0104-volume-payload-gen1 >/data/persist.txt && '\n"
                                    + "    'sync && '\n"
                                    + "    'SEC=$(grep ^Seccomp: /proc/self/status | tr -dc 0-9) && '\n"
                                    + "    'MEM=$(cat /sys/fs/cgroup/memory.max) && '\n"
                                    + "    'PIDS=$(cat /sys/fs/cgroup/pids.max) && '\n"
                                    + "    'VSHA=$(sha256sum /data/persist.txt | cut -c1-64) && '\n"
                                    + "    'printf \"SRV_CHECK;SEC=%s;MEM=%s;PIDS=%s;VSHA=%s\\\\n\" \"$SEC\" \"$MEM\" \"$PIDS\" \"$VSHA\"'\n"
                                    + ")\n"
                                    + "srv_out = subprocess.check_output(['docker', 'exec', 'w0104-srv', 'sh', '-ec', srv_script], text=True).strip()\n"
                                    + "print(srv_out)\n"
                                    + "port_bind = subprocess.check_output(['docker', 'port', 'w0104-srv', '8080/tcp'], text=True).strip()\n"
                                    + "assert port_bind == '127.0.0.1:" + GUEST_CONTAINER_HOST_PORT + "', port_bind\n"
                                    + "subprocess.check_call([\n"
                                    + "    'docker', 'run', '--rm', '--name', 'w0104-cli', '--network', 'w0104-net', 'orchestella-w0104-app:v1',\n"
                                    + "    'sh', '-ec', 'printf \"GET / HTTP/1.0\\\\r\\\\n\\\\r\\\\n\" | nc w0104-srv 8080 | grep -q VOL=w0104-volume-payload-gen1'\n"
                                    + "])\n"
                                    + "print('BRIDGE_IPC_' + 'OK')\n"
                                    + "PY",
                            "__W0104_WORKLOAD__",
                            90_000L);
            require(workloadRes.contains("SRV_CHECK;SEC=2;MEM=134217728;PIDS=64;"),
                    "server container cgroup/seccomp check failed: " + workloadRes);
            require(workloadRes.contains("BRIDGE_IPC_OK"),
                    "container-to-container bridge HTTP failed: " + workloadRes);
            String srvLine = extractResultLine(workloadRes, "SRV_CHECK;");
            String volSha = extractField(srvLine, "VSHA=");
            out.putString("boot1_srv_check", srvLine);

            // Observable PID & Memory limit enforcement + Container outbound DNS/TLS
            reportStage("boot1_limits_and_dns_tls");
            String limitsRes =
                    runGuestCmd(
                            live,
                            "PID_FAIL=0; PIDS_ERR=$(docker run --rm --pids-limit=5 alpine:3.22.2 sh -c 'for i in 1 2 3 4 5 6 7 8; do sleep 2 & done; wait' 2>&1 >/dev/null); "
                                    + "PID_RC=$?; if [ \"$PID_RC\" -ne 0 ] || printf '%s' \"$PIDS_ERR\" | grep -qiE 'fork|resource|pids'; then PID_FAIL=1; fi; "
                                    + "docker run --rm --memory=16m alpine:3.22.2 sh -c 'dd if=/dev/zero of=/dev/shm/oom bs=1M count=24' >/dev/null 2>&1; MEM_RC=$?; "
                                    + "printf '%s%s;PID_FAIL=%s;MEM_RC=%s\\n' 'LIMITS_' 'OK' \"$PID_FAIL\" \"$MEM_RC\"",
                            "__W0104_LIMITS__",
                            60_000L);
            require(limitsRes.contains("LIMITS_OK;PID_FAIL=1;MEM_RC=137")
                            || limitsRes.contains("LIMITS_OK;PID_FAIL=1;MEM_RC=1"),
                    "cgroup limit enforcement not observed: " + limitsRes);
            out.putString("cgroup_limits_enforcement", extractResultLine(limitsRes, "LIMITS_OK;"));

            String tlsRes =
                    runGuestCmd(
                            live,
                            "timeout -k 5 30 docker run --rm alpine:3.22.2 sh -ec "
                                    + "'nslookup deb.debian.org >/dev/null 2>&1 && wget -q -O /dev/null --timeout=15 https://deb.debian.org/debian/dists/trixie/Release' "
                                    + "&& printf '%s%s\\n' 'CONTAINER_DNS_TLS_' 'ONLINE' || printf '%s%s\\n' 'CONTAINER_DNS_TLS_' 'OFFLINE'",
                            "__W0104_TLS__",
                            45_000L);
            out.putString("container_dns_tls",
                    tlsRes.contains("CONTAINER_DNS_TLS_ONLINE") ? "ONLINE" : "OFFLINE");

            // 6. Controlled guest loopback -> Android explicit PortForwardingPresenter + conflict/failure/reclaim
            reportStage("boot1_port_forwarding");
            PortForwardingPresenter presenter =
                    PortForwardingPresenter.forActiveSession(inst.id(), fwdMgr);
            require(presenter.hasLiveEngine(), "missing live UmlPasstNetwork forwarding engine");
            presenter.addRule(
                    Integer.toString(hostPort),
                    Integer.toString(GUEST_CONTAINER_HOST_PORT),
                    Protocol.TCP,
                    "w0104-docker-http");
            String androidHttp1 = readHttp(hostPort);
            require(androidHttp1.contains("BUILD_ARM64_RUN_OK")
                            && androidHttp1.contains("VOL=w0104-volume-payload-gen1"),
                    "Android loopback HTTP mismatch: " + androidHttp1);
            out.putString("boot1_android_http", androidHttp1.replace("\r\n", "|"));

            boolean conflictRejected = false;
            try {
                LoopbackEndpointRegistry.defaultRegistry().acquire(
                        "127.0.0.1", hostPort, "foreign-inst", "gen-x", "rule-x");
            } catch (IOException expected) {
                conflictRejected = true;
            }
            require(conflictRejected, "port conflict was not rejected");

            presenter.addRule(
                    Integer.toString(deadHostPort),
                    Integer.toString(GUEST_DEAD_PORT),
                    Protocol.TCP,
                    "w0104-dead-port");
            boolean deadFailedCleanly = false;
            try {
                String deadResp = readHttp(deadHostPort);
                deadFailedCleanly = deadResp.isEmpty();
            } catch (IOException expected) {
                deadFailedCleanly = true;
            }
            require(deadFailedCleanly, "dead guest port unexpectedly returned data");
            String deadRuleId =
                    fwdMgr.listRules().stream()
                            .filter(r -> r.hostPort() == deadHostPort)
                            .findFirst()
                            .orElseThrow()
                            .id();
            presenter.deleteRule(deadRuleId);
            require(!isBound(deadHostPort), "deadHostPort not released");

            String activeRuleId =
                    fwdMgr.listRules().stream()
                            .filter(r -> r.hostPort() == hostPort)
                            .findFirst()
                            .orElseThrow()
                            .id();
            presenter.deleteRule(activeRuleId);
            require(!isBound(hostPort), "hostPort not released after rule delete");
            presenter.addRule(
                    Integer.toString(hostPort),
                    Integer.toString(GUEST_CONTAINER_HOST_PORT),
                    Protocol.TCP,
                    "w0104-docker-persist");
            require(readHttp(hostPort).contains("VOL=w0104-volume-payload-gen1"),
                    "re-added forwarding rule failed");

            // 7. Delete and recreate container against same named volume (Build/Run #2)
            reportStage("boot1_container_rebuild");
            String rebuildRes =
                    runGuestCmd(
                            live,
                            "sh -ec '"
                                    + "docker rm -f w0104-srv >/dev/null; "
                                    + "docker run -d --name w0104-srv --network w0104-net --memory=128m --pids-limit=64 "
                                    + "-v w0104-data:/data -p 127.0.0.1:" + GUEST_CONTAINER_HOST_PORT + ":8080 orchestella-w0104-app:v1 >/dev/null; "
                                    + "VSHA1=$(docker exec w0104-srv sha256sum /data/persist.txt | cut -d\" \" -f1); "
                                    + "test \"$VSHA1\" = \"" + volSha + "\"; "
                                    + "printf \"%s%s\\n\" \"REBUILD_VOL_\" \"OK\"'",
                            "__W0104_REBUILD__",
                            45_000L);
            require(rebuildRes.contains("REBUILD_VOL_OK"), "rebuild volume check failed: " + rebuildRes);
            require(readHttp(hostPort).contains("VOL=w0104-volume-payload-gen1"),
                    "Android HTTP after container recreate failed");

            reportStage("boot1_poweroff");
            runGuestCmd(
                    live,
                    "docker stop w0104-srv >/dev/null && umount /mnt/w0104-fixtures 2>/dev/null || :; sync",
                    "__W0104_PREOFF__",
                    60_000L);
            live.sendBytes("systemctl poweroff\n".getBytes(StandardCharsets.UTF_8));
            require(umlSession.awaitPowerDownOrExit(90_000L), "boot1 systemctl poweroff timeout");
            RuntimeProcessSupervisor.StopResult stopResult =
                    ((RuntimeOwnedSession) session).stopRuntime(STOP_GRACE_MS);
            require(
                    stopResult == RuntimeProcessSupervisor.StopResult.CONFIRMED
                            || stopResult == RuntimeProcessSupervisor.StopResult.ALREADY_STOPPED,
                    "boot1 stopResult unconfirmed: " + stopResult);
            out.putString("boot1_stop_result", stopResult.name());
            ExecutionSessionRegistry.unregister(inst.id(), session);
            session = null;
            verifyNoTempResidue(inst.rootVolume().getParent(), instanceRuntimeDir);
            return volSha;
        } finally {
            if (session != null) {
                try {
                    ((RuntimeOwnedSession) session).stopRuntime(STOP_GRACE_MS);
                } catch (Throwable ignored) {
                }
                ExecutionSessionRegistry.unregister(inst.id(), session);
            }
            InstanceLaunchGuard.end(inst.id(), gen);
            reconcileStopState(store, controller, inst.id());
        }
    }

    private void runBoot2(
            Context target,
            InstanceLifecycle.FileStore store,
            InstanceLifecycle.Controller controller,
            Path distroInputsDir,
            Path runtimeRoot,
            InstanceLifecycle.Instance inst,
            PortForwardingManager fwdMgr,
            int hostPort,
            String expectedVolSha,
            Bundle out)
            throws Exception {
        String gen = InstanceLaunchGuard.begin(inst.id());
        require(gen != null, "boot2 launch guard busy");
        InstanceLaunchSequence.beforeLaunch(
                controller, inst, InstanceLifecycle.ConcurrencyPolicy.SINGLE_RUNNING_INSTANCE);
        Path instanceRuntimeDir = Files.createDirectories(runtimeRoot.resolve(inst.id()));
        ExecutionSession session = null;
        try {
            UmlInstanceLauncher launcher = new UmlInstanceLauncher(target, store);
            session = launcher.launch(paramsFor(inst, distroInputsDir, instanceRuntimeDir));
            UmlInstanceLauncher.Session umlSession = (UmlInstanceLauncher.Session) session;
            ExecutionSessionRegistry.LiveSession live =
                    ExecutionSessionRegistry.getLiveSession(inst.id());
            require(live != null, "boot2 missing LiveSession");

            awaitPrompt(live, REBOOT_PROMPT_TIMEOUT_MS);
            authenticateBridge(session, inst.bridgeToken());
            awaitSystemdActive(live, SYSTEMD_ACTIVE_TIMEOUT_MS);
            umlSession.markShellReady();
            out.putString("boot2_cred_outcome", umlSession.launchTimeline().credentialsOnlyOutcome().name());
            require(
                    umlSession.launchTimeline().credentialsOnlyOutcome()
                            == LaunchTimeline.CredentialsOnlyOutcome.HIT,
                    "expected HIT on boot2");

            reportStage("boot2_verify_persistence");
            String boot2Res =
                    runGuestCmd(
                            live,
                            "sh -ec '"
                                    + "test \"$(cat /proc/1/comm)\" = systemd; "
                                    + "test \"$(systemctl is-active docker.service)\" = active; "
                                    + "test -z \"$(docker ps -q)\"; "
                                    + "docker volume inspect w0104-data >/dev/null; "
                                    + "docker image inspect orchestella-w0104-app:v1 >/dev/null; "
                                    + "docker rm -f w0104-srv >/dev/null 2>&1 || :; "
                                    + "docker run -d --name w0104-srv2 --network w0104-net --memory=128m --pids-limit=64 "
                                    + "-v w0104-data:/data -p 127.0.0.1:" + GUEST_CONTAINER_HOST_PORT + ":8080 orchestella-w0104-app:v1 >/dev/null; "
                                    + "VSHA2=$(docker exec w0104-srv2 sha256sum /data/persist.txt | cut -d\" \" -f1); "
                                    + "printf \"%s%s%s\\n\" \"BOOT2_\" \"PERSIST;VSHA=\" \"$VSHA2\"'",
                            "__W0104_BOOT2__",
                            90_000L);
            require(boot2Res.contains("BOOT2_PERSIST;VSHA=" + expectedVolSha),
                    "boot2 volume SHA mismatch: " + boot2Res);
            out.putString("boot2_vol_sha256", expectedVolSha);

            String androidHttp2 = readHttp(hostPort);
            require(androidHttp2.contains("VOL=w0104-volume-payload-gen1"),
                    "Android loopback HTTP after full reboot failed: " + androidHttp2);
            out.putString("boot2_android_http", androidHttp2.replace("\r\n", "|"));

            reportStage("boot2_fault_diagnostics");
            String faultRes =
                    runGuestCmd(
                            live,
                            "VOL_FAULT=0; docker run --rm --mount type=bind,source=/nonexistent/w0104-missing/file,target=/probe,readonly alpine:3.22.2 cat /probe >/dev/null 2>&1 || VOL_FAULT=1; "
                                    + "mkdir -p /tmp/w0104-lowdisk && mount -t tmpfs -o size=2M tmpfs /tmp/w0104-lowdisk; "
                                    + "DISK_FAULT=0; dd if=/dev/zero of=/tmp/w0104-lowdisk/fill bs=1M count=4 >/tmp/lowdisk.log 2>&1 || DISK_FAULT=1; "
                                    + "umount /tmp/w0104-lowdisk && rmdir /tmp/w0104-lowdisk; "
                                    + "grep -qi 'no space left' /tmp/lowdisk.log && printf '%s%s\\n' 'LOWDISK_DIAG_' 'ENOSPC'; "
                                    + "docker rm -f w0104-srv2 >/dev/null && docker network rm w0104-net >/dev/null && docker volume rm w0104-data >/dev/null; "
                                    + "printf '%s%s;VOL=%s;DISK=%s;MARKER=%s\\n' 'FAULT_' 'DIAG' \"$VOL_FAULT\" \"$DISK_FAULT\" \"$(grep -c kernel_profile=full-smp-arm64 /etc/orchestella/provision-marker)\"",
                            "__W0104_FAULTS__",
                            45_000L);
            require(faultRes.contains("LOWDISK_DIAG_ENOSPC")
                            && faultRes.contains("FAULT_DIAG;VOL=1;DISK=1;MARKER=1"),
                    "fault diagnostics failed: " + faultRes);
            out.putString("fault_diagnostics", extractResultLine(faultRes, "FAULT_DIAG;"));

            PortForwardingPresenter presenter =
                    PortForwardingPresenter.forActiveSession(inst.id(), fwdMgr);
            for (PortForwardingRule rule : List.copyOf(fwdMgr.listRules())) {
                presenter.deleteRule(rule.id());
            }
            require(!isBound(hostPort), "hostPort still bound after delete");

            StructStat st = Os.stat(inst.rootVolume().toString());
            out.putLong("ext4_logical_bytes", Files.size(inst.rootVolume()));
            out.putLong("ext4_allocated_bytes", st.st_blocks * 512L);

            reportStage("boot2_app_terminate");
            session.terminate();
            RuntimeProcessSupervisor.StopResult stopResult =
                    ((RuntimeOwnedSession) session).stopRuntime(STOP_GRACE_MS);
            require(
                    stopResult == RuntimeProcessSupervisor.StopResult.CONFIRMED
                            || stopResult == RuntimeProcessSupervisor.StopResult.ALREADY_STOPPED,
                    "boot2 stopResult unconfirmed: " + stopResult);
            out.putString("boot2_stop_result", stopResult.name());
            ExecutionSessionRegistry.unregister(inst.id(), session);
            session = null;
            verifyNoTempResidue(inst.rootVolume().getParent(), instanceRuntimeDir);
        } finally {
            if (session != null) {
                try {
                    ((RuntimeOwnedSession) session).stopRuntime(STOP_GRACE_MS);
                } catch (Throwable ignored) {
                }
                ExecutionSessionRegistry.unregister(inst.id(), session);
            }
            InstanceLaunchGuard.end(inst.id(), gen);
            reconcileStopState(store, controller, inst.id());
        }
    }

    private static InstanceStrategyLauncher.LaunchParameters paramsFor(
            InstanceLifecycle.Instance inst, Path distroInputsDir, Path instanceRuntimeDir) {
        return new InstanceStrategyLauncher.LaunchParameters(
                inst.id(),
                inst.bridgeToken(),
                DEBIAN_TAR,
                inst.rootVolume().toString(),
                instanceRuntimeDir.toString(),
                distroInputsDir.toString(),
                inst.guestPassword(),
                inst.hardwareConfig(),
                null, null, null,
                inst.hostname(),
                inst.defaultUser(),
                inst.hasSudo(),
                null,
                inst.kernelProfile());
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
                    "__W0104_SD__",
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

    private String runGuestCmd(
            ExecutionSessionRegistry.LiveSession live,
            String command,
            String sentinel,
            long timeoutMs)
            throws Exception {
        String halfA = sentinel + "_";
        String halfB = "END";
        String endToken = halfA + halfB;
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder captured = new StringBuilder();
        live.setOutputListener(
                chunk -> {
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
                    throw new IOException(
                            "command timeout waiting for " + endToken + ", seen=" + captured.substring(start));
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

    private static int pickFreeUserPort(String instanceId, int... avoid) throws IOException {
        int derived = GatewaySlotArbiter.derivedPort(instanceId);
        for (int attempt = 0; attempt < 16; attempt++) {
            int candidate;
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("127.0.0.1", 0));
                candidate = socket.getLocalPort();
            }
            if (PortForwardingRule.isReservedHostPort(candidate) || candidate == derived) {
                continue;
            }
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
            // No active listener accepted TCP; confirm kernel allows binding with SO_REUSEADDR (ignoring TIME_WAIT).
        }
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
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
                    require(
                            !name.startsWith("boot-creds-")
                                    && !name.startsWith("uml-prov-")
                                    && !name.startsWith("passt-"),
                            "leftover temp dir/file: " + p);
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

    private static String extractResultLine(String full, String prefix) {
        String cleaned = ANSI_ESCAPE_PATTERN.matcher(full).replaceAll("");
        for (String line : cleaned.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed;
            }
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
                if (n > 0) {
                    md.update(buf, 0, n);
                }
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    private void reportStage(String next) {
        this.stage = next;
        Bundle b = new Bundle();
        b.putString("w0104_progress", next);
        sendStatus(0, b);
    }

    private static void require(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }
}
