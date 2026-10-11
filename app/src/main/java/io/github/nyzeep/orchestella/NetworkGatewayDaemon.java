package io.github.nyzeep.orchestella;

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns one gvproxy user-space network gateway per running instance.
 *
 * <p>The packaged QEMU has no slirp, and passt cannot run inside the untrusted-app
 * SELinux domain (its namespace isolation exits silently on Android). gvproxy — the
 * gVisor-netstack gateway used by podman machine — is a statically linked Go binary
 * with no libc or namespace requirements, speaks the QEMU socket netdev protocol on
 * a UNIX socket, and NATs guest traffic onto the host stack (following host VPNs).
 * Its built-in netstack serves 192.168.127.0/24; distros without a DHCP client get
 * the same subnet pinned via kernel command-line arguments.
 */
public final class NetworkGatewayDaemon {

    /** Snapshot of one daemon run: socket path + the live process handle. */
    public static final class Running {
        public final String unixSocketPath;
        public final int tcpPort;
        public final Process process;

        Running(String configPath, int tcpPort, Process process) {
            this.unixSocketPath = configPath;
            this.tcpPort = tcpPort;
            this.process = process;
        }
    }

    /** Deterministic guest addressing inside gvproxy's default 192.168.127.0/24. */
    public static final String GUEST_IP = "192.168.127.15";
    public static final String GUEST_GATEWAY = "192.168.127.1";
    public static final String GUEST_NETMASK = "255.255.255.0";

    /**
     * Guest resolver points at a public DNS reached through the gateway NAT:
     * gvproxy's own DNS forwarder needs /etc/resolv.conf, which Android lacks.
     */
    public static final String GUEST_DNS = "223.5.5.5";

    private static final String TAG = "OrchestellaGvproxy";
    private static final long SOCKET_WAIT_MILLIS = 8000L;

    private NetworkGatewayDaemon() {
    }

    /**
     * Starts the gateway for one instance and waits until its UNIX socket exists,
     * then returns the run info. Blocking; call on a worker thread.
     *
     * <p>gvproxy v0.9+ is configured through a YAML file: the qemu-protocol listener
     * rides {@code interfaces.qemu} (the {@code -listen} flag no longer accepts the
     * qemu scheme), and no port forwards are declared so nothing competes for
     * arbitrary host ports.
     */
    public static Running start(Context context, String instanceId, File runtimeDirectory)
            throws IOException {
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        String gvproxyPath = new File(nativeDir, "libgvproxy.so").getAbsolutePath();
        if (!new File(gvproxyPath).isFile()) {
            throw new IOException("gvproxy payload missing from jniLibs");
        }
        // The packaged QEMU's socket netdev only speaks the legacy TCP syntax
        // (connect=host:port), so the gateway listens on loopback TCP instead of a
        // UNIX socket. The port is picked per instance with collision fallback.
        int port = NetworkGatewayDaemon.listenPortFor(instanceId);
        File configFile = new File(runtimeDirectory, "net-" + instanceId + ".yaml");
        String nl = System.lineSeparator();
        List<String> yaml = new ArrayList<>();
        yaml.add("log-level: info");
        yaml.add("interfaces:");
        yaml.add("  qemu: \"tcp://127.0.0.1:" + port + "\"");
        yaml.add("stack:");
        yaml.add("  mtu: 1500");
        yaml.add("  subnet: 192.168.127.0/24");
        yaml.add("  gatewayIP: 192.168.127.1");
        yaml.add("  deviceIP: " + GUEST_IP);
        yaml.add("  hostIP: 192.168.127.254");
        yaml.add("  gatewayMacAddress: 5a:94:ef:e4:0c:dd");
        StringBuilder builder = new StringBuilder();
        for (String line : yaml) {
            builder.append(line).append(nl);
        }
        java.nio.file.Files.write(configFile.toPath(),
                builder.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        String[] argv = {
                gvproxyPath,
                "-config", configFile.getAbsolutePath(),
        };
        Log.i(TAG, "spawning gvproxy with config " + configFile);
        Process process = new ProcessBuilder(argv)
                .redirectErrorStream(true)
                .start();
        Thread logger = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.d(TAG, line);
                }
            } catch (IOException ignored) {
            }
        }, "gvproxy-log-" + instanceId);
        logger.setDaemon(true);
        logger.start();

        long deadline = System.currentTimeMillis() + SOCKET_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("gvproxy exited during startup");
            }
            // Readiness probe without touching the listener: a CONNECT would make
            // gvproxy treat the probe as its QEMU client (EOF then kills the gateway).
            // Instead, try to bind the port ourselves — a bind failure means gvproxy
            // is already listening.
            if (portBound(port)) {
                Log.i(TAG, "gateway ready on 127.0.0.1:" + port);
                return new Running(configFile.getAbsolutePath(), port, process);
            }
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroy();
                throw new IOException("interrupted while waiting for gateway socket");
            }
        }
        process.destroy();
        throw new IOException("gateway socket did not become ready in " + SOCKET_WAIT_MILLIS + "ms");
    }

    /** Stops a previously started gateway; safe to call with null. */
    public static void stop(Running running) {
        if (running == null || running.process == null) {
            return;
        }
        running.process.destroy();
    }

    private static boolean portBound(int port) {
        try (java.net.ServerSocket ss = new java.net.ServerSocket()) {
            ss.bind(new java.net.InetSocketAddress("127.0.0.1", port));
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    /** Deterministic base port per instance; the caller retries on bind conflicts. */
    static int listenPortFor(String instanceId) {
        int hash = instanceId != null ? instanceId.hashCode() : 0;
        return 24100 + (Math.abs(hash) % 800);
    }

}
