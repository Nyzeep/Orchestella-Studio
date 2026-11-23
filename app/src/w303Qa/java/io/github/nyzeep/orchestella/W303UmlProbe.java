package io.github.nyzeep.orchestella;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.contract.Protocol;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.instance.GatewaySlotArbiter;
import io.github.nyzeep.orchestella.runtime.network.PortForwardingManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exercises the production UML PTY, network and forwarding adapters on a disposable Alpine root. */
final class W303UmlProbe {
    private static final String ALPINE_REPOSITORY = "https://dl-cdn.alpinelinux.org/alpine/v3.22/main";
    private static final String ALPINE_HOST = "dl-cdn.alpinelinux.org";
    private static final int GUEST_HTTP_A = 18_080;
    private static final int GUEST_HTTP_B = 18_081;

    private W303UmlProbe() {}

    static void run(Context context, Consumer<String> stage) throws Exception {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        stage.accept("uml-offline-preflight");
        require(connectivity != null && connectivity.getActiveNetwork() == null);

        String instance = "w303-uml-probe";
        Path files = context.getFilesDir().toPath();
        PortForwardingManager manager = PortForwardingManager.forInstance(instance, files.resolve("network"));
        for (PortForwardingRule stale : List.copyOf(manager.listRules())) {
            manager.removeRule(stale.id());
        }

        Path runtime = Files.createTempDirectory(files, instance);
        Path runtimeRestore = null;
        String restoreRuleId = null;
        try {
            InstanceStrategyLauncher.LaunchParameters launch = launchParams(instance, files, runtime);

            stage.accept("uml-offline-shell");
            UmlInstanceLauncher.Session session =
                    (UmlInstanceLauncher.Session) UmlInstanceLauncher.forEphemeralQa(context).launch(launch);
            int restoredHostPort;
            try {
                Shell shell = new Shell(instance, session);
                shell.awaitPrompt();
                shell.succeeds("test -x /bin/busybox && test -r /etc/alpine-release", 20);
                stage.accept("uml-bridge");
                authenticateBridge(session, launch.bridgeToken);
                stage.accept("uml-offline-diagnostic");
                awaitStatus(session, UmlPasstNetwork.Status.OFFLINE, 18_000L);
                shell.succeeds("! timeout 4 nslookup " + ALPINE_HOST + " >/dev/null 2>&1", 8);
                require(connectivity.getActiveNetwork() == null);

                // The host restores the original device network after this instrumentation status.
                stage.accept("uml-restore-network");
                long deadline = SystemClock.elapsedRealtime() + 60_000L;
                while (connectivity.getActiveNetwork() == null && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(200L);
                }
                require(connectivity.getActiveNetwork() != null);
                long validatedDeadline = SystemClock.elapsedRealtime() + 10_000L;
                while (SystemClock.elapsedRealtime() < validatedDeadline) {
                    Network activeNetwork = connectivity.getActiveNetwork();
                    NetworkCapabilities caps = activeNetwork == null
                            ? null
                            : connectivity.getNetworkCapabilities(activeNetwork);
                    if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        break;
                    }
                    SystemClock.sleep(250L);
                }

                stage.accept("uml-dns");
                awaitStatus(session, UmlPasstNetwork.Status.READY, 25_000L);
                shell.succeeds("for attempt in 1 2 3 4 5 6; do "
                        + "timeout -s KILL 5 nslookup " + ALPINE_HOST + " >/dev/null 2>&1 && exit 0; "
                        + "sleep 1; done; exit 1", 55);

                stage.accept("uml-authorized-package-source");
                shell.succeeds("mkdir -p /tmp/w303-cache && printf '%s\\n' '" + ALPINE_REPOSITORY
                        + "' >/tmp/w303-repositories"
                        + " && timeout 60 apk --cache-dir /tmp/w303-cache"
                        + " --repositories-file /tmp/w303-repositories update >/dev/null 2>&1", 65);

                stage.accept("uml-curl");
                shell.succeeds("ok=0; rc=1; idx=0; root=''; "
                        + "for repo in"
                        + " 'https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.22/main'"
                        + " 'https://mirrors.aliyun.com/alpine/v3.22/main'"
                        + " '" + ALPINE_REPOSITORY + "'; do "
                        + "idx=$((idx + 1)); dir=/tmp/w303-curl-$idx; "
                        + "mkdir -p \"$dir/etc/apk\" || exit 121; "
                        + "cp -r /etc/apk/keys \"$dir/etc/apk/keys\" || exit 122; "
                        + "cp /etc/apk/arch \"$dir/etc/apk/arch\" || exit 123; "
                        + "printf '%s\\n' \"$repo\" >\"$dir/etc/apk/repositories\" || exit 124; "
                        + "if timeout -s KILL 60 apk --root \"$dir\" --initdb --no-cache"
                        + " --no-scripts add curl >/dev/null 2>/tmp/w303-apk.err; then "
                        + "ok=1; root=\"$dir\"; break; else rc=$?; fi; "
                        + "done; "
                        + "if [ \"$ok\" -ne 1 ]; then "
                        + "if [ \"$rc\" -eq 137 ] || [ \"$rc\" -eq 143 ]; then exit \"$rc\"; fi; "
                        + "if grep -qiE 'No space left|ENOSPC' /tmp/w303-apk.err 2>/dev/null; then exit 144; fi; "
                        + "if grep -qiE 'UNTRUSTED|signature' /tmp/w303-apk.err 2>/dev/null; then exit 145; fi; "
                        + "if grep -qiE 'unsatisfiable|unable to select|world' /tmp/w303-apk.err 2>/dev/null; then exit 146; fi; "
                        + "if grep -qiE 'Permission denied|lock' /tmp/w303-apk.err 2>/dev/null; then exit 147; fi; "
                        + "if grep -qiE 'SSL|TLS|certificate' /tmp/w303-apk.err 2>/dev/null; then exit 142; fi; "
                        + "if grep -qiE 'IO ERROR|temporary error|timed out|network|reset|EOF|fetch' /tmp/w303-apk.err 2>/dev/null; then exit 141; fi; "
                        + "exit 149; fi; "
                        + "\"$root/lib/ld-musl-aarch64.so.1\""
                        + " --library-path \"$root/lib:$root/usr/lib\""
                        + " \"$root/usr/bin/curl\" --cacert /etc/ssl/certs/ca-certificates.crt"
                        + " --fail --silent --max-time 25 --head '"
                        + ALPINE_REPOSITORY + "/aarch64/APKINDEX.tar.gz' >/dev/null || exit 133", 220);

                stage.accept("uml-forwarding");
                restoredHostPort = probeForwarding(instance, manager, shell, stage);
                restoreRuleId = manager.listRules().get(0).id();
            } finally {
                ExecutionSessionRegistry.unregister(instance, session);
            }
            stage.accept("uml-stop");
            require(!session.isRuntimeAlive());
            try (ServerSocket freeBetweenBoots = bind("127.0.0.1", restoredHostPort)) {
                require(freeBetweenBoots.isBound());
            }

            stage.accept("uml-forward-restart-restore");
            runtimeRestore = Files.createTempDirectory(files, instance + "-restore");
            InstanceStrategyLauncher.LaunchParameters launchRestore =
                    launchParams(instance, files, runtimeRestore);
            UmlInstanceLauncher.Session restoredSession =
                    (UmlInstanceLauncher.Session) UmlInstanceLauncher.forEphemeralQa(context)
                            .launch(launchRestore);
            try {
                Shell shell2 = new Shell(instance, restoredSession);
                shell2.awaitPrompt();
                authenticateBridge(restoredSession, launchRestore.bridgeToken);
                awaitStatus(restoredSession, UmlPasstNetwork.Status.READY, 25_000L);
                startHttpFixtures(shell2);

                PortForwardingPresenter presenter2 =
                        PortForwardingPresenter.forActiveSession(instance, manager);
                require(presenter2.hasLiveEngine() && manager.listRules().size() == 1);
                require(readHttp(restoredHostPort).endsWith("w303-service-a"));
                presenter2.deleteRule(restoreRuleId);
                restoreRuleId = null;
                require(manager.listRules().isEmpty());
                try (ServerSocket released = bind("127.0.0.1", restoredHostPort)) {
                    require(released.isBound());
                }
            } finally {
                ExecutionSessionRegistry.unregister(instance, restoredSession);
            }
            require(!restoredSession.isRuntimeAlive());
        } finally {
            if (restoreRuleId != null) {
                manager.removeRule(restoreRuleId);
            }
            deleteTree(runtime);
            deleteTree(runtimeRestore);
        }
    }

    private static InstanceStrategyLauncher.LaunchParameters launchParams(
            String instance, Path files, Path runtime) {
        byte[] tokenBytes = new byte[32];
        new SecureRandom().nextBytes(tokenBytes);
        return new InstanceStrategyLauncher.LaunchParameters(
                instance, StorageContract.bytesToHex(tokenBytes),
                UmlInstanceLauncher.ALPINE_MINIROOTFS_RESOURCE,
                runtime.resolve("unused-root.img").toString(),
                runtime.toString(),
                files.toString());
    }

    private static void authenticateBridge(UmlInstanceLauncher.Session session, String token)
            throws IOException {
        BridgeClient bridge = session.bridgeClient();
        require(bridge != null);
        bridge.startResponsePump(request -> "ORCHESTELLA/ERROR unsupported-command\n");
        boolean authenticated = bridge.handshake(token).accepted();
        BridgeConnector.notifyAuthenticated(session.launchStageObserver(), authenticated);
        require(authenticated && bridge.ping().accepted());
    }

    private static void awaitStatus(
            UmlInstanceLauncher.Session session, UmlPasstNetwork.Status expected, long timeoutMillis) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        do {
            if (session.networkStatus() == expected) return;
            SystemClock.sleep(100L);
        } while (session.isRuntimeAlive() && SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("uml-network-status expected=" + expected
                + " actual=" + session.networkStatus() + " alive=" + session.isRuntimeAlive());
    }

    private static void startHttpFixtures(Shell shell) throws IOException {
        shell.succeeds("set -e; "
                + "printf '%s\\n' '#!/bin/sh' 'IFS= read -r _req || :' "
                + "'printf \"HTTP/1.0 200 OK\\r\\nContent-Length: 14\\r\\n\\r\\nw303-service-a\"' "
                + ">/tmp/w303-http-a.sh; "
                + "printf '%s\\n' '#!/bin/sh' 'IFS= read -r _req || :' "
                + "'printf \"HTTP/1.0 200 OK\\r\\nContent-Length: 14\\r\\n\\r\\nw303-service-b\"' "
                + ">/tmp/w303-http-b.sh; "
                + "chmod 0755 /tmp/w303-http-a.sh /tmp/w303-http-b.sh; "
                + "/bin/busybox nc -ll -p " + GUEST_HTTP_A
                + " -e /tmp/w303-http-a.sh </dev/null >/dev/null 2>&1 & "
                + "/bin/busybox nc -ll -p " + GUEST_HTTP_B
                + " -e /tmp/w303-http-b.sh </dev/null >/dev/null 2>&1 & "
                + "sleep 1", 10);
    }

    private static int probeForwarding(
            String instance, PortForwardingManager manager, Shell shell, Consumer<String> stage)
            throws Exception {
        startHttpFixtures(shell);

        PortForwardingPresenter presenter = PortForwardingPresenter.forActiveSession(instance, manager);
        require(presenter.hasLiveEngine() && manager.listRules().isEmpty());
        int hostPort = pickFreeUserPort(instance);
        String host = Integer.toString(hostPort);
        presenter.addRule(host, Integer.toString(GUEST_HTTP_A), Protocol.TCP, "");
        require(manager.listRules().size() == 1);
        String ruleId = manager.listRules().get(0).id();
        try {
            require(readHttp(hostPort).endsWith("w303-service-a"));
            // Binding the same port on a different loopback address rules out a wildcard bind.
            try (ServerSocket otherAddress = bind("127.0.0.2", hostPort)) {
                require(otherAddress.isBound());
            }

            stage.accept("uml-forward-update");
            presenter.updateRule(ruleId, host, Integer.toString(GUEST_HTTP_B), "");
            require(readHttp(hostPort).endsWith("w303-service-b"));

            stage.accept("uml-forward-conflict");
            try (ServerSocket conflict = bind("127.0.0.1", 0)) {
                presenter.updateRule(ruleId, Integer.toString(conflict.getLocalPort()),
                        Integer.toString(GUEST_HTTP_A), "");
                require(manager.getRule(ruleId).orElseThrow().hostPort() == hostPort);
                require(readHttp(hostPort).endsWith("w303-service-b"));
            }
        } finally {
            presenter.deleteRule(ruleId);
        }
        stage.accept("uml-forward-revoked");
        require(manager.listRules().isEmpty());
        // A new direct bind verifies that the listener and its endpoint were released.
        try (ServerSocket released = bind("127.0.0.1", hostPort)) {
            require(released.isBound());
        }

        // Persist a fresh rule so Boot 2 proves automatic restart-restore.
        int restoreHostPort = pickFreeUserPort(instance);
        presenter.addRule(Integer.toString(restoreHostPort), Integer.toString(GUEST_HTTP_A),
                Protocol.TCP, "w303-restore");
        require(manager.listRules().size() == 1);
        require(readHttp(restoreHostPort).endsWith("w303-service-a"));
        return restoreHostPort;
    }

    private static int pickFreeUserPort(String instance) throws IOException {
        int derived = GatewaySlotArbiter.derivedPort(instance);
        for (int attempt = 0; attempt < 8; attempt++) {
            int candidate;
            try (ServerSocket available = bind("127.0.0.1", 0)) {
                candidate = available.getLocalPort();
            }
            if (!PortForwardingRule.isReservedHostPort(candidate) && candidate != derived) {
                return candidate;
            }
        }
        throw new IOException("no unreserved loopback port available");
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Disposable temp cleanup only.
                }
            });
        } catch (IOException ignored) {
            // Disposable temp cleanup only.
        }
    }

    private static ServerSocket bind(String address, int port) throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(address, port));
            return socket;
        } catch (IOException failure) {
            socket.close();
            throw failure;
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

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError();
    }

    private static final class Shell {
        private final ExecutionSessionRegistry.LiveSession terminal;
        private int nextCommand;

        Shell(String instance, UmlInstanceLauncher.Session session) {
            terminal = ExecutionSessionRegistry.getLiveSession(instance);
            require(terminal != null && terminal.rawSession() == session);
        }

        void awaitPrompt() {
            long deadline = SystemClock.elapsedRealtime() + 20_000L;
            do {
                if (terminal.holder().getScreenModel().hasObservedShellPrompt()) return;
                SystemClock.sleep(50L);
            } while (terminal.isAlive() && SystemClock.elapsedRealtime() < deadline);
            throw new AssertionError();
        }

        void succeeds(String script, long timeoutSeconds) throws IOException {
            int command = ++nextCommand;
            // The echoed command contains placeholders, so it cannot match the actual marker.
            String frame = "( " + script + " ); printf '\\n__W303_RESULT_%d_%d\\n' "
                    + command + " \"$?\"\n";
            terminal.sendBytes(frame.getBytes(StandardCharsets.US_ASCII));
            Pattern result = Pattern.compile("__W303_RESULT_" + command + "_(\\d+)");
            long deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
            do {
                Matcher marker = result.matcher(terminal.getTranscript());
                if (marker.find()) {
                    String rc = marker.group(1);
                    if (!"0".equals(rc)) {
                        throw new AssertionError("uml-shell-rc=" + rc);
                    }
                    return;
                }
                SystemClock.sleep(50L);
            } while (terminal.isAlive() && SystemClock.elapsedRealtime() < deadline);
            throw new AssertionError("uml-shell-timeout alive=" + terminal.isAlive());
        }
    }
}
