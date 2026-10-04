package io.github.nyzeep.droidlayer.runtime.engineavf;

import android.system.virtualmachine.FakeVirtualizationContext;
import android.system.virtualmachine.FakeVsockNetwork;
import android.system.virtualmachine.VirtualMachine;
import android.system.virtualmachine.VirtualMachineConfig;
import android.system.virtualmachine.VirtualMachineCustomImageConfig;
import android.system.virtualmachine.VirtualMachineManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * The "AVF 适配层模拟实现" for the Ticket 34 contract tests: a JVM guest that
 * serves the Bridge v1 protocol on the control vsock port and echoes a shell
 * on the terminal port — over the exact host-side engine path a real device
 * would take. Guest handlers run on daemon threads.
 */
public final class SimulatedAvfGuest implements AutoCloseable {
    private final FakeVsockNetwork network = new FakeVsockNetwork();
    private final String expectedToken;
    private final int controlPort;
    private final int terminalPort;
    private final long listenDelayMs;
    private final List<String> receivedControlLines = Collections.synchronizedList(new ArrayList<>());
    private final List<String> sentResponses = Collections.synchronizedList(new ArrayList<>());
    private final List<VirtualMachineCustomImageConfig> observedConfigs =
            Collections.synchronizedList(new ArrayList<>());

    public SimulatedAvfGuest(String expectedToken, int controlPort, int terminalPort)
            throws IOException {
        this(expectedToken, controlPort, terminalPort, 0);
    }

    /** @param listenDelayMs simulates the guest boot window before ports open. */
    public SimulatedAvfGuest(
            String expectedToken, int controlPort, int terminalPort, long listenDelayMs)
            throws IOException {
        this.expectedToken = expectedToken;
        this.controlPort = controlPort;
        this.terminalPort = terminalPort;
        this.listenDelayMs = listenDelayMs;
        openListeners();
    }

    private void openListeners() throws IOException {
        if (listenDelayMs <= 0) {
            listenNow();
            return;
        }
        Thread delayed = new Thread(
                () -> {
                    try {
                        Thread.sleep(listenDelayMs);
                        listenNow();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } catch (IOException ignored) {
                        // fixture-only failure path
                    }
                },
                "simulated-avf-guest-boot");
        delayed.setDaemon(true);
        delayed.start();
    }

    private synchronized void listenNow() throws IOException {
        network.listen(controlPort, this::serveControl);
        network.listen(terminalPort, this::serveTerminal);
    }

    /** Manager whose creation records the observed config, then boots the guest. */
    public VirtualMachineManager newManager(Consumer<VirtualMachineCustomImageConfig> recorder) {
        return new VirtualMachineManager((name, config) -> {
            VirtualMachineCustomImageConfig custom = (VirtualMachineCustomImageConfig) config;
            observedConfigs.add(custom);
            if (recorder != null) {
                recorder.accept(custom);
            }
            return VirtualMachine.over(network);
        });
    }

    public VirtualMachineManager newManager() {
        return newManager(null);
    }

    public FakeVirtualizationContext newContext(VirtualMachineManager manager) {
        return new FakeVirtualizationContext(manager);
    }

    public List<String> receivedControlLines() {
        return Collections.unmodifiableList(receivedControlLines);
    }

    public List<String> sentResponses() {
        return Collections.unmodifiableList(sentResponses);
    }

    public List<VirtualMachineCustomImageConfig> observedConfigs() {
        return observedConfigs;
    }

    /** Bridge v1 host-initiated wire: HELLO/READY, PING/PONG, RESIZE/OK, ... */
    private void serveControl(Socket socket) {
        try (BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        socket.getInputStream(), StandardCharsets.US_ASCII));
                OutputStream out = socket.getOutputStream()) {
            String line;
            while ((line = reader.readLine()) != null) {
                receivedControlLines.add(line);
                String response = respond(line);
                sentResponses.add(response);
                out.write(response.getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        } catch (IOException ignored) {
            // guest-side channel end; the host observes EOF
        }
    }

    private String respond(String line) {
        if (line.equals("DROIDLAYER/HELLO 1 1 " + expectedToken)) {
            return "DROIDLAYER/READY 1\n";
        }
        if (line.equals("DROIDLAYER/PING")) {
            return "DROIDLAYER/PONG 1\n";
        }
        if (line.startsWith("DROIDLAYER/RESIZE ")) {
            return "DROIDLAYER/OK resize\n";
        }
        if (line.startsWith("DROIDLAYER/NOTIFY ")) {
            return "DROIDLAYER/OK notify\n";
        }
        if (line.startsWith("DROIDLAYER/OPEN-URL ")) {
            return "DROIDLAYER/OK open-url\n";
        }
        return "DROIDLAYER/ERROR unauthorized\n";
    }

    /** Terminal echo: the simulated root shell repeats every byte it reads. */
    private void serveTerminal(Socket socket) {
        try (socket) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = socket.getInputStream().read(buffer)) >= 0) {
                OutputStream out = socket.getOutputStream();
                out.write(buffer, 0, count);
                out.flush();
            }
        } catch (IOException ignored) {
            // guest-side channel end; the host observes EOF
        }
    }

    @Override
    public void close() {
        network.closeGuestSide();
    }
}
