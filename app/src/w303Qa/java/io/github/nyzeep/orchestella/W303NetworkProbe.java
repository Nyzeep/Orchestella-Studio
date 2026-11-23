package io.github.nyzeep.orchestella;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentProviderClient;
import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.system.Os;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingEngine;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.contract.Protocol;
import io.github.nyzeep.orchestella.runtime.instance.GatewaySlotArbiter;
import io.github.nyzeep.orchestella.runtime.instance.GatewaySlotLease;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Device-only smoke test. Reports stages and exception types without addresses or process IDs. */
public final class W303NetworkProbe extends Instrumentation {
    private String stage = "preflight";
    private String scenario;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        scenario = arguments != null ? arguments.getString("scenario", "gateway") : "gateway";
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            Context target = getTargetContext();
            require(BuildConfig.IS_ISOLATED_QA_BUILD && target.getPackageName().endsWith(".qa.w303"));
            switch (scenario) {
                case "gateway" -> probeGateway(target);
                case "uml" -> W303UmlProbe.run(target, this::reportStage);
                default -> throw new IllegalArgumentException("unknown network probe scenario");
            }
            result.putString("w303_result", "PASS");
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError failure) {
            result.putString("w303_result", "FAIL");
            result.putString("w303_stage", stage);
            result.putString("w303_error", failure.getClass().getSimpleName());
            String reason = failure.getMessage();
            if (reason != null && reason.matches(
                    "gvproxy-api-(?:connect|read|write)-failed|uml-network-status [A-Za-z0-9_= -]{1,80}|uml-shell-rc=[0-9]{1,5}|uml-shell-timeout alive=(?:true|false)")) {
                result.putString("w303_reason", reason);
            }
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void reportStage(String name) {
        stage = name;
        Bundle progress = new Bundle();
        progress.putString("w303_stage", name);
        sendStatus(0, progress);
    }

    private void probeGateway(Context target) throws Exception {
        String id = GatewaySlotProbeProvider.INSTANCE_ID;
        int port = GatewaySlotArbiter.derivedPort(id);
        File socket = new File(target.getFilesDir(),
                "net-api/" + GatewaySlotArbiter.apiSocketDirectoryName(id) + "/api.sock");
        stage = "separate-process";
        try (Peer peer = new Peer(target)) {
            int peerPid = peer.request(GatewaySlotProbeProvider.IDENTIFY);
            require(peerPid > 0 && peerPid != Process.myPid());

            stage = "live-owner-conflict";
            NetworkGatewayDaemon.Running owner = GatewaySlotProbeProvider.acquire(target);
            try {
                long ownerSocket = Os.stat(socket.getPath()).st_ino;
                require(isBound(port));
                long started = SystemClock.elapsedRealtime();
                require(peer.request(GatewaySlotProbeProvider.ACQUIRE) == 0);
                require(SystemClock.elapsedRealtime() - started < 3_000L);
                require(isBound(port));
                require(Os.stat(socket.getPath()).st_ino == ownerSocket);
                probeQemuRule(owner);
            } finally {
                require(owner.stop(1_000));
            }
            require(!isBound(port) && !socket.exists());

            stage = "reacquire-and-stale-stop";
            require(peer.request(GatewaySlotProbeProvider.ACQUIRE) == 1);
            long replacementSocket = Os.stat(socket.getPath()).st_ino;
            require(owner.stop(0));
            require(isBound(port));
            require(Os.stat(socket.getPath()).st_ino == replacementSocket);
            require(peer.request(GatewaySlotProbeProvider.RELEASE) == 1);
            require(!isBound(port) && !socket.exists());

            stage = "concurrent-acquire";
            FutureTask<Integer> remote = new FutureTask<>(() -> peer.request(GatewaySlotProbeProvider.ACQUIRE));
            Thread contender = new Thread(remote, "gateway-probe-contender");
            contender.setDaemon(true);
            contender.start();
            NetworkGatewayDaemon.Running local = null;
            try {
                try {
                    local = GatewaySlotProbeProvider.acquire(target);
                } catch (IOException conflict) {
                    // The assertion checks that the other process actually acquired the slot.
                }
                boolean remoteAcquired = remote.get(12, TimeUnit.SECONDS) == 1;
                require(remoteAcquired != (local != null));
                require(isBound(port) && socket.exists());
            } finally {
                if (local != null) require(local.stop(1_000));
            }
            require(peer.request(GatewaySlotProbeProvider.RELEASE) == 1);
            require(!isBound(port) && !socket.exists());

            stage = "unconfirmed-stop";
            probeUnconfirmedStop(target, peer, id, port);
        }
        stage = "unknown-owner";
        Files.createDirectories(socket.toPath().getParent());
        Files.createFile(socket.toPath());
        try (ServerSocket unknown = bind(port)) {
            long unknownSocket = Os.stat(socket.getPath()).st_ino;
            try {
                NetworkGatewayDaemon.Running unexpected = GatewaySlotProbeProvider.acquire(target);
                unexpected.stop(1_000);
                throw new AssertionError();
            } catch (IOException expected) {
                require(isBound(port));
                require(Os.stat(socket.getPath()).st_ino == unknownSocket);
            }
        } finally {
            Files.deleteIfExists(socket.toPath());
        }
        require(!isBound(port));
    }

    private void probeQemuRule(NetworkGatewayDaemon.Running gateway) throws IOException {
        stage = "gvproxy-user-rule-add";
        int hostPort = 0;
        for (int attempt = 0; attempt < 8; attempt++) {
            int candidate;
            try (ServerSocket available = bind(0)) {
                candidate = available.getLocalPort();
            }
            if (!PortForwardingRule.isReservedHostPort(candidate) && candidate != gateway.tcpPort) {
                hostPort = candidate;
                break;
            }
        }
        require(hostPort > 0);
        PortForwardingRule rule = PortForwardingRule.defaultRule("w303", Protocol.TCP, hostPort, 18080);
        PortForwardingEngine.Live engine = gateway.forwardingEngine();
        require(engine != null);
        engine.addRule(rule);
        try {
            require(engine.isApplied(rule) && isBound(hostPort));
        } finally {
            engine.removeRule(rule);
        }
        stage = "gvproxy-user-rule-release";
        require(!isBound(hostPort));
    }

    private static void probeUnconfirmedStop(Context target, Peer peer, String id, int port)
            throws Exception {
        GatewaySlotLease lease = GatewaySlotLease.acquire(target.getFilesDir().toPath(), id,
                (pid, startTicks) -> new GatewaySlotLease.ProcessObservation(
                        GatewaySlotLease.ProcessState.UNAVAILABLE),
                path -> Files.exists(path) ? "qa-owned-socket" : null);
        File socket = lease.apiSocket().toFile();
        StopProbeProcess process = new StopProbeProcess();
        NetworkGatewayDaemon.Running running =
                new NetworkGatewayDaemon.Running("", port, process, id, socket, null, lease);
        try (ServerSocket held = bind(port)) {
            Files.createFile(socket.toPath());
            lease.recordProcess(Process.myPid(), 0L, "qa-owned-socket");
            long heldSocket = Os.stat(socket.getPath()).st_ino;
            require(!running.stop(0));
            require(peer.request(GatewaySlotProbeProvider.ACQUIRE) == 0);
            require(isBound(port) && Os.stat(socket.getPath()).st_ino == heldSocket);
        } finally {
            process.exited = true;
            require(running.stop(0));
        }
        require(!isBound(port) && !socket.exists());
        require(peer.request(GatewaySlotProbeProvider.ACQUIRE) == 1);
        require(peer.request(GatewaySlotProbeProvider.RELEASE) == 1);
    }

    private static final class StopProbeProcess extends java.lang.Process {
        boolean exited;

        @Override public boolean isAlive() { return !exited; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return exited; }
        @Override public void destroy() {}
        @Override public java.lang.Process destroyForcibly() { return this; }
        @Override public InputStream getInputStream() { throw new UnsupportedOperationException(); }
        @Override public InputStream getErrorStream() { throw new UnsupportedOperationException(); }
        @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
        @Override public int waitFor() { return exitValue(); }
        @Override public int exitValue() {
            if (!exited) throw new IllegalThreadStateException();
            return 0;
        }
    }

    private static ServerSocket bind(int port) throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return socket;
        } catch (IOException failure) {
            socket.close();
            throw failure;
        }
    }

    private static boolean isBound(int port) throws IOException {
        try (ServerSocket free = bind(port)) {
            return false;
        } catch (BindException bound) {
            return true;
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError();
    }

    private static final class Peer implements AutoCloseable {
        private final ContentProviderClient client;

        Peer(Context context) {
            client = context.getContentResolver().acquireContentProviderClient(
                    context.getPackageName() + ".w303-gateway");
            require(client != null);
        }

        int request(String action) throws RemoteException {
            Bundle response = client.call(action, null, null);
            require(response != null && response.containsKey(GatewaySlotProbeProvider.RESULT));
            return response.getInt(GatewaySlotProbeProvider.RESULT);
        }

        @Override
        public void close() throws RemoteException {
            try {
                require(request(GatewaySlotProbeProvider.RELEASE) == 1);
            } finally {
                client.close();
            }
        }
    }
}
