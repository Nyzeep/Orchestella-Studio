package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.contract.DisplayControl;
import io.github.nyzeep.orchestella.runtime.contract.DisplayListener;
import io.github.nyzeep.orchestella.runtime.contract.DisplayMode;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.InputEvent;
import io.github.nyzeep.orchestella.runtime.contract.InputEventSerialization;
import io.github.nyzeep.orchestella.runtime.contract.WaylandChannel;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AVF hardware-virtualized implementation of {@link WaylandChannel} and {@link DisplayControl}.
 *
 * <p>Transports graphics frame streaming, dynamic resolution renegotiation, and input events
 * over dedicated vsock port 9103. Conforms to ADR-0002, ADR-0017, and Ticket 42:
 * <ul>
 *   <li>Establishes authenticated vsock channel using Bridge v1 line protocol;</li>
 *   <li>Delivers double-buffered ping-pong frame streaming for 60 FPS presentation;</li>
 *   <li>Detects zero-damage dirty regions to skip redundant rendering on static desktops;</li>
 *   <li>Forwards input events down to the guest Wayland compositor;</li>
 *   <li>Declares {@link FullLinuxContract.Capability#GRAPHICS_WAYLAND} capability parity.</li>
 * </ul>
 */
public class AvfWaylandChannel implements WaylandChannel, DisplayControl {
    public static final int AVF_GRAPHICS_VSOCK_PORT = 9103;
    public static final int DEFAULT_PORT = AVF_GRAPHICS_VSOCK_PORT;

    /** Supplier interface for opening guest-bound vsock graphics channels. */
    @FunctionalInterface
    public interface VsockChannelSupplier {
        AvfVirtualMachineApi.AvfVsockChannel get() throws IOException;
    }

    private final String instanceToken;
    private final VsockChannelSupplier channelSupplier;
    private final AutoCloseable closeHook;

    private final Object outputLock = new Object();
    private final Object stateLock = new Object();

    private final Set<StateListener> stateListeners = new CopyOnWriteArraySet<>();
    private final Set<FrameListener> frameListeners = new CopyOnWriteArraySet<>();
    private final Set<DisplayListener> displayListeners = new CopyOnWriteArraySet<>();

    private final AvfPingPongBuffer pingPongBuffer = new AvfPingPongBuffer();
    private final AtomicLong damageSkippedFramesCount = new AtomicLong(0);

    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private volatile boolean closed = false;
    private volatile boolean autoReconnect = false;

    private InputStream inStream;
    private OutputStream outStream;
    private AutoCloseable currentChannelHandle;
    private Thread readerThread;

    private DisplayMode currentMode = new DisplayMode(1920, 1080, 1.0, 160, 60);

    public AvfWaylandChannel(InputStream in, OutputStream out, String instanceToken) {
        this(in, out, instanceToken, null);
    }

    public AvfWaylandChannel(
            InputStream in, OutputStream out, String instanceToken, AutoCloseable closeHook) {
        this.inStream = Objects.requireNonNull(in, "in");
        this.outStream = Objects.requireNonNull(out, "out");
        this.instanceToken = Objects.requireNonNull(instanceToken, "instanceToken");
        this.closeHook = closeHook;
        this.channelSupplier = null;
    }

    public AvfWaylandChannel(
            AvfVirtualMachineApi.AvfVsockChannel channel, String instanceToken) {
        Objects.requireNonNull(channel, "channel");
        this.inStream = channel.inputStream();
        this.outStream = channel.outputStream();
        this.instanceToken = Objects.requireNonNull(instanceToken, "instanceToken");
        this.closeHook = channel;
        this.currentChannelHandle = channel;
        this.channelSupplier = null;
    }

    public AvfWaylandChannel(VsockChannelSupplier channelSupplier, String instanceToken) {
        this.channelSupplier = Objects.requireNonNull(channelSupplier, "channelSupplier");
        this.instanceToken = Objects.requireNonNull(instanceToken, "instanceToken");
        this.closeHook = null;
        this.autoReconnect = true;
    }

    public AvfWaylandChannel(
            AvfVirtualMachineEngine.AvfVmSession session, String instanceToken) {
        this(() -> session.openChannel(session.graphicsChannel() != null
                ? DEFAULT_PORT : DEFAULT_PORT), instanceToken);
    }

    /**
     * Returns the strategy-neutral capability matrix reflecting AVF Wayland graphics parity.
     */
    public static FullLinuxContract.CapabilityMatrix capabilityMatrix() {
        return FullLinuxContract.CapabilityMatrix.hardwareVirtualizationWithGraphics();
    }

    /**
     * Connects and authenticates the graphics channel over vsock.
     */
    public void connect() throws IOException {
        synchronized (stateLock) {
            if (closed) {
                throw new IOException("AvfWaylandChannel is closed");
            }
            if (state == ConnectionState.CONNECTED) {
                return;
            }

            try {
                if (channelSupplier != null) {
                    AvfVirtualMachineApi.AvfVsockChannel channel = channelSupplier.get();
                    this.currentChannelHandle = channel;
                    this.inStream = channel.inputStream();
                    this.outStream = channel.outputStream();
                }

                performHandshake();

                transitionTo(ConnectionState.CONNECTED, "Connected via vsock port " + DEFAULT_PORT);
                startReaderLoop();
            } catch (IOException e) {
                transitionTo(ConnectionState.ERROR, "Connection/Handshake failed: " + e.getMessage());
                throw e;
            }
        }
    }

    private void performHandshake() throws IOException {
        synchronized (outputLock) {
            String hello = AvfGraphicsVsockProtocol.formatHelloCommand(instanceToken);
            outStream.write(hello.getBytes(StandardCharsets.US_ASCII));
            outStream.flush();
        }

        String response = readAsciiLine(inStream);
        if (response == null || (!response.startsWith("ORCHESTELLA/READY") && !response.startsWith("DROIDLAYER/READY"))) {
            throw new IOException("Handshake rejected: " + response);
        }
    }

    private void startReaderLoop() {
        readerThread = new Thread(this::readerLoop, "avf-wayland-channel-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readerLoop() {
        byte[] magicBuf = new byte[4];
        byte[] headerRemaining = new byte[AvfGraphicsVsockProtocol.HEADER_SIZE - 4];
        byte[] fullHeader = new byte[AvfGraphicsVsockProtocol.HEADER_SIZE];

        while (!closed && state == ConnectionState.CONNECTED) {
            try {
                int firstByte = inStream.read();
                if (firstByte == -1) {
                    // EOF: guest closed transport
                    break;
                }
                magicBuf[0] = (byte) firstByte;
                AvfGraphicsVsockProtocol.readFully(inStream, magicBuf, 1, 3);

                int magic = ByteBuffer.wrap(magicBuf).order(ByteOrder.BIG_ENDIAN).getInt();
                if (magic == AvfGraphicsVsockProtocol.FRAME_MAGIC) {
                    // Frame Header
                    AvfGraphicsVsockProtocol.readFully(inStream, headerRemaining, 0, headerRemaining.length);
                    System.arraycopy(magicBuf, 0, fullHeader, 0, 4);
                    System.arraycopy(headerRemaining, 0, fullHeader, 4, headerRemaining.length);

                    AvfGraphicsVsockProtocol.FrameHeader header =
                            AvfGraphicsVsockProtocol.decodeFrameHeader(fullHeader);

                    if (!AvfGraphicsVsockProtocol.isValidFrameHeader(header)) {
                        // Malformed frame header received: protect memory and truncate/abort
                        transitionTo(ConnectionState.ERROR, "Malformed frame header received");
                        break;
                    }

                    int payloadLength = header.payloadLength();
                    if (AvfGraphicsVsockProtocol.isDamageEmpty(header)) {
                        // Static desktop optimization: dirty regions empty, skip memory copy and repaint
                        damageSkippedFramesCount.incrementAndGet();
                        continue;
                    }

                    // Ingest pixel stream into double-buffered Ping-Pong slot
                    pingPongBuffer.ensureCapacity(payloadLength);
                    byte[] activeBuf = pingPongBuffer.activeBuffer();
                    AvfGraphicsVsockProtocol.readFully(inStream, activeBuf, 0, payloadLength);

                    byte[] framePayload = (activeBuf.length == payloadLength)
                            ? activeBuf : Arrays.copyOf(activeBuf, payloadLength);

                    WaylandChannel.FrameData frameData = new WaylandChannel.FrameData(
                            header.width(),
                            header.height(),
                            header.stride(),
                            header.format(),
                            framePayload,
                            header.frameNumber(),
                            header.timestampNs());

                    // Ping-pong slot swap
                    pingPongBuffer.swap();

                    // Distribute to registered FrameListeners
                    for (FrameListener listener : frameListeners) {
                        try {
                            listener.onFrameAvailable(frameData);
                        } catch (Throwable ignored) {
                        }
                    }
                } else {
                    // ASCII control response line (starts with magicBuf)
                    StringBuilder sb = new StringBuilder();
                    for (byte b : magicBuf) {
                        if (b == '\n') break;
                        if (b != '\r') sb.append((char) b);
                    }
                    if (magicBuf[3] != '\n') {
                        int ch;
                        while ((ch = inStream.read()) != -1) {
                            if (ch == '\n') break;
                            if (ch != '\r') sb.append((char) ch);
                        }
                    }
                    // Received control line (e.g. DROIDLAYER/OK display)
                }
            } catch (EOFException | java.net.SocketException e) {
                // Connection ended
                break;
            } catch (IOException e) {
                if (!closed) {
                    transitionTo(ConnectionState.ERROR, "I/O error on frame stream: " + e.getMessage());
                }
                break;
            }
        }

        if (!closed) {
            handleUnexpectedDisconnect();
        }
    }

    private void handleUnexpectedDisconnect() {
        transitionTo(ConnectionState.DISCONNECTED, "Transport stream ended");
        if (autoReconnect && channelSupplier != null && !closed) {
            try {
                Thread.sleep(500);
                if (!closed) {
                    connect();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static String readAsciiLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                sb.append((char) b);
            }
        }
        return sb.length() == 0 && b == -1 ? null : sb.toString();
    }

    private void transitionTo(ConnectionState newState, String message) {
        synchronized (stateLock) {
            if (this.state == newState) {
                return;
            }
            this.state = newState;
            for (StateListener listener : stateListeners) {
                try {
                    listener.onStateChanged(newState, message);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // --- WaylandChannel Interface ---

    @Override
    public ConnectionState state() {
        return state;
    }

    @Override
    public void addStateListener(StateListener listener) {
        if (listener != null) {
            stateListeners.add(listener);
        }
    }

    @Override
    public void removeStateListener(StateListener listener) {
        if (listener != null) {
            stateListeners.remove(listener);
        }
    }

    @Override
    public void registerFrameListener(FrameListener listener) {
        if (listener != null) {
            frameListeners.add(listener);
        }
    }

    @Override
    public void unregisterFrameListener(FrameListener listener) {
        if (listener != null) {
            frameListeners.remove(listener);
        }
    }

    @Override
    public boolean sendInputEvent(InputEvent event) {
        if (event == null || !isConnected()) {
            return false;
        }
        try {
            String line = InputEventSerialization.serialize(event);
            synchronized (outputLock) {
                if (outStream == null || !isConnected()) {
                    return false;
                }
                outStream.write(line.getBytes(StandardCharsets.US_ASCII));
                outStream.flush();
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    // --- DisplayControl Interface ---

    /**
     * Returns the DisplayControl contract adapter for this channel.
     */
    public DisplayControl displayControl() {
        return this;
    }

    @Override
    public DisplayMode currentMode() {
        return currentMode;
    }

    @Override
    public boolean resizeDisplay(int width, int height) {
        if (width < AvfGraphicsVsockProtocol.MIN_DIMENSION || width > AvfGraphicsVsockProtocol.MAX_DIMENSION
                || height < AvfGraphicsVsockProtocol.MIN_DIMENSION || height > AvfGraphicsVsockProtocol.MAX_DIMENSION) {
            return false;
        }
        if (!isConnected()) {
            return false;
        }
        try {
            String command = AvfGraphicsVsockProtocol.formatResizeCommand(width, height);
            synchronized (outputLock) {
                if (outStream == null || !isConnected()) {
                    return false;
                }
                outStream.write(command.getBytes(StandardCharsets.US_ASCII));
                outStream.flush();
            }
            this.currentMode = currentMode.withDimensions(width, height);
            for (DisplayListener listener : displayListeners) {
                try {
                    listener.onModeChanged(currentMode);
                    listener.onDisplayResized(width, height);
                } catch (Throwable ignored) {
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean updateScale(double scale) {
        if (scale < AvfGraphicsVsockProtocol.MIN_SCALE || scale > AvfGraphicsVsockProtocol.MAX_SCALE
                || Double.isNaN(scale) || Double.isInfinite(scale)) {
            return false;
        }
        if (!isConnected()) {
            return false;
        }
        try {
            String command = AvfGraphicsVsockProtocol.formatModeCommand(
                    currentMode.width(), currentMode.height(), scale);
            synchronized (outputLock) {
                if (outStream == null || !isConnected()) {
                    return false;
                }
                outStream.write(command.getBytes(StandardCharsets.US_ASCII));
                outStream.flush();
            }
            this.currentMode = currentMode.withScale(scale);
            for (DisplayListener listener : displayListeners) {
                try {
                    listener.onModeChanged(currentMode);
                } catch (Throwable ignored) {
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean updateTargetFps(int targetFps) {
        if (targetFps < AvfGraphicsVsockProtocol.MIN_FPS || targetFps > AvfGraphicsVsockProtocol.MAX_FPS) {
            return false;
        }
        if (!isConnected()) {
            return false;
        }
        try {
            String command = AvfGraphicsVsockProtocol.formatFpsCommand(targetFps);
            synchronized (outputLock) {
                if (outStream == null || !isConnected()) {
                    return false;
                }
                outStream.write(command.getBytes(StandardCharsets.US_ASCII));
                outStream.flush();
            }
            this.currentMode = currentMode.withFps(targetFps);
            for (DisplayListener listener : displayListeners) {
                try {
                    listener.onModeChanged(currentMode);
                    listener.onFpsUpdated(targetFps);
                } catch (Throwable ignored) {
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void addDisplayListener(DisplayListener listener) {
        if (listener != null) {
            displayListeners.add(listener);
        }
    }

    @Override
    public void removeDisplayListener(DisplayListener listener) {
        if (listener != null) {
            displayListeners.remove(listener);
        }
    }

    /**
     * Total number of frames skipped due to zero damage dirty regions.
     */
    public long damageSkippedFramesCount() {
        return damageSkippedFramesCount.get();
    }

    @Override
    public void close() {
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            transitionTo(ConnectionState.DISCONNECTED, "Channel closed by host");

            if (readerThread != null) {
                readerThread.interrupt();
            }

            try {
                if (closeHook != null) {
                    closeHook.close();
                }
            } catch (Exception ignored) {
            }

            try {
                if (currentChannelHandle != null) {
                    currentChannelHandle.close();
                }
            } catch (Exception ignored) {
            }

            try {
                if (inStream != null) {
                    inStream.close();
                }
            } catch (Exception ignored) {
            }

            try {
                if (outStream != null) {
                    outStream.close();
                }
            } catch (Exception ignored) {
            }
        }
    }
}
