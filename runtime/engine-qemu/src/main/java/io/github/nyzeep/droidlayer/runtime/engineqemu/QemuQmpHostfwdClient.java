package io.github.nyzeep.droidlayer.runtime.engineqemu;

import io.github.nyzeep.droidlayer.runtime.contract.PortForwardingEngine;
import io.github.nyzeep.droidlayer.runtime.contract.PortForwardingRule;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * QMP client for dynamic hostfwd reconciliation on a running QEMU instance (Ticket 29 AC-2).
 *
 * <p>Communicates over a QMP stream (connected to QEMU's abstract unix domain socket or pipe),
 * performs greeting negotiation and capability exchange, and emits {@code hostfwd_add}
 * and {@code hostfwd_remove} commands using {@code human-monitor-command}.
 */
public final class QemuQmpHostfwdClient implements PortForwardingEngine {
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final String netdevId;
    private boolean initialized = false;
    private boolean running = true;

    public QemuQmpHostfwdClient(InputStream inbound, OutputStream outbound, String netdevId) {
        Objects.requireNonNull(inbound, "inbound");
        Objects.requireNonNull(outbound, "outbound");
        this.reader = new BufferedReader(new InputStreamReader(inbound, StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(outbound, StandardCharsets.UTF_8));
        this.netdevId = Objects.requireNonNull(netdevId, "netdevId");
    }

    /**
     * Performs QMP initial handshake: reads greeting banner and executes qmp_capabilities.
     */
    public void init() throws IOException {
        String greeting = readLine();
        if (greeting == null || !greeting.contains("\"QMP\"")) {
            throw new IOException("Invalid QMP greeting: " + greeting);
        }

        sendCommand("{\"execute\":\"qmp_capabilities\"}");
        String response = readResponse();
        if (response.contains("\"error\"")) {
            throw new IOException("qmp_capabilities rejected: " + response);
        }
        initialized = true;
    }

    @Override
    public void addRule(PortForwardingRule rule) throws IOException {
        ensureInitialized();
        String command = QemuHostfwdFormatter.formatQmpHostfwdAdd(netdevId, rule);
        sendCommand(command);
        String response = readResponse();
        if (response.contains("\"error\"")) {
            throw new IOException("Failed to add hostfwd rule: " + response);
        }
    }

    @Override
    public void removeRule(PortForwardingRule rule) throws IOException {
        ensureInitialized();
        String command = QemuHostfwdFormatter.formatQmpHostfwdRemove(netdevId, rule);
        sendCommand(command);
        String response = readResponse();
        if (response.contains("\"error\"")) {
            throw new IOException("Failed to remove hostfwd rule: " + response);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public void close() {
        running = false;
        try {
            reader.close();
        } catch (IOException ignored) {}
        try {
            writer.close();
        } catch (IOException ignored) {}
    }

    private void ensureInitialized() throws IOException {
        if (!initialized) {
            init();
        }
    }

    private void sendCommand(String jsonCommand) throws IOException {
        writer.write(jsonCommand);
        writer.write("\n");
        writer.flush();
    }

    private String readResponse() throws IOException {
        while (true) {
            String line = readLine();
            if (line == null) {
                running = false;
                throw new IOException("QMP stream closed unexpectedly");
            }
            if (line.contains("\"return\"") || line.contains("\"error\"")) {
                return line;
            }
        }
    }

    private String readLine() throws IOException {
        return reader.readLine();
    }
}
