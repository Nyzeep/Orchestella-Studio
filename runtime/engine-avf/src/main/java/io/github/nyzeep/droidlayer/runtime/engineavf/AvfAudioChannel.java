package io.github.nyzeep.droidlayer.runtime.engineavf;

import io.github.nyzeep.droidlayer.runtime.contract.AudioStreamContract;
import io.github.nyzeep.droidlayer.runtime.contract.FullLinuxContract;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AVF hardware-virtualized implementation of {@link AudioStreamContract}.
 *
 * <p>Transports low-latency PCM audio streaming from guest PulseAudio over dedicated
 * vsock port 9104. Conforms to ADR-0002, ADR-0013, ADR-0018, and Ticket 48:
 * <ul>
 *   <li>Exposes streaming PCM read pipeline for host audio players;</li>
 *   <li>Enforces fixed 48,000 Hz 16-bit stereo format;</li>
 *   <li>Provides bounded backpressure buffering to prevent memory bloat;</li>
 *   <li>Detects silence and transitions to zero-CPU standby sleep;</li>
 *   <li>Supports adaptive reconnection upon guest or transport restarts;</li>
 *   <li>Declares {@link FullLinuxContract.Capability#AUDIO_OUTPUT} capability parity.</li>
 * </ul>
 */
public class AvfAudioChannel implements AudioStreamContract, AutoCloseable {
    public static final int AVF_AUDIO_VSOCK_PORT = AvfAudioVsockProtocol.AVF_AUDIO_VSOCK_PORT;
    public static final int DEFAULT_PORT = AVF_AUDIO_VSOCK_PORT;

    /** Supplier interface for opening guest-bound vsock audio sockets. */
    @FunctionalInterface
    public interface SocketSupplier {
        Socket get() throws IOException;
    }

    private final SocketSupplier socketSupplier;
    private final AutoCloseable closeHook;
    private final int port;

    private final Object stateLock = new Object();
    private final Set<StateListener> stateListeners = new CopyOnWriteArraySet<>();

    private final AudioRingBuffer ringBuffer;
    private final AudioPcmInputStream pcmInputStream;

    private volatile State state = State.IDLE;
    private volatile boolean closed = false;
    private volatile boolean autoReconnect = false;
    private volatile boolean isStandby = false;

    private int silenceStandbyThresholdFrames = AvfAudioVsockProtocol.SILENCE_THRESHOLD_FRAMES;
    private final AtomicLong silenceFramesDetected = new AtomicLong(0);
    private final AtomicLong backpressureDropCount = new AtomicLong(0);
    private final AtomicLong totalBytesIngested = new AtomicLong(0);

    private InputStream inStream;
    private OutputStream outStream;
    private AutoCloseable currentHandle;
    private Thread pumpThread;

    public AvfAudioChannel(InputStream in, OutputStream out) {
        this(in, out, null, DEFAULT_PORT);
    }

    public AvfAudioChannel(InputStream in, OutputStream out, AutoCloseable closeHook) {
        this(in, out, closeHook, DEFAULT_PORT);
    }

    public AvfAudioChannel(InputStream in, OutputStream out, AutoCloseable closeHook, int port) {
        this.inStream = Objects.requireNonNull(in, "in");
        this.outStream = Objects.requireNonNull(out, "out");
        this.closeHook = closeHook;
        this.socketSupplier = null;
        this.port = port;
        this.ringBuffer = new AudioRingBuffer(AvfAudioVsockProtocol.DEFAULT_BUFFER_CAPACITY, backpressureDropCount);
        this.pcmInputStream = new AudioPcmInputStream(ringBuffer);
    }

    public AvfAudioChannel(AvfVirtualMachineApi.AvfVsockChannel channel) {
        this(channel, DEFAULT_PORT);
    }

    public AvfAudioChannel(AvfVirtualMachineApi.AvfVsockChannel channel, int port) {
        Objects.requireNonNull(channel, "channel");
        this.inStream = channel.inputStream();
        this.outStream = channel.outputStream();
        this.closeHook = channel;
        this.currentHandle = channel;
        this.socketSupplier = null;
        this.port = port;
        this.ringBuffer = new AudioRingBuffer(AvfAudioVsockProtocol.DEFAULT_BUFFER_CAPACITY, backpressureDropCount);
        this.pcmInputStream = new AudioPcmInputStream(ringBuffer);
    }

    public AvfAudioChannel(SocketSupplier socketSupplier, int port) {
        this.socketSupplier = Objects.requireNonNull(socketSupplier, "socketSupplier");
        this.closeHook = null;
        this.port = port;
        this.ringBuffer = new AudioRingBuffer(AvfAudioVsockProtocol.DEFAULT_BUFFER_CAPACITY, backpressureDropCount);
        this.pcmInputStream = new AudioPcmInputStream(ringBuffer);
    }

    public AvfAudioChannel(AvfVirtualMachineEngine.AvfVmSession session) {
        this(session, session.vsockAudioPort());
    }

    public AvfAudioChannel(AvfVirtualMachineEngine.AvfVmSession session, int port) {
        this(() -> {
            AvfVirtualMachineApi.AvfVsockChannel ch = session.openChannel(port);
            return new Socket() {
                @Override
                public InputStream getInputStream() {
                    return ch.inputStream();
                }

                @Override
                public OutputStream getOutputStream() {
                    return ch.outputStream();
                }

                @Override
                public synchronized void close() throws IOException {
                    ch.close();
                }

                @Override
                public boolean isConnected() {
                    return true;
                }

                @Override
                public boolean isClosed() {
                    return false;
                }
            };
        }, port);
    }

    /**
     * Returns the strategy-neutral capability matrix reflecting AVF audio and peripheral parity.
     */
    public static FullLinuxContract.CapabilityMatrix capabilityMatrix() {
        return FullLinuxContract.CapabilityMatrix.hardwareVirtualizationWithPeripherals();
    }

    @Override
    public Format format() {
        return Format.DEFAULT;
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public void start() {
        synchronized (stateLock) {
            if (closed || state == State.RUNNING) {
                return;
            }
            if (state == State.PAUSED) {
                transitionTo(State.RUNNING);
                return;
            }

            transitionTo(State.STARTING);
            try {
                if (socketSupplier != null && (inStream == null || currentHandle == null)) {
                    connectSocket();
                }
                transitionTo(State.RUNNING);
                startPumpThread();
            } catch (IOException e) {
                transitionTo(State.ERROR);
            }
        }
    }

    private void connectSocket() throws IOException {
        if (socketSupplier == null) {
            return;
        }
        Socket socket = socketSupplier.get();
        this.currentHandle = socket;
        this.inStream = socket.getInputStream();
        this.outStream = socket.getOutputStream();
    }

    private void startPumpThread() {
        stopPumpThread();
        pumpThread = new Thread(this::pumpLoop, "avf-audio-channel-pump");
        pumpThread.setDaemon(true);
        pumpThread.start();
    }

    private void stopPumpThread() {
        if (pumpThread != null) {
            pumpThread.interrupt();
            try {
                pumpThread.join(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            pumpThread = null;
        }
    }

    private void pumpLoop() {
        byte[] readBuf = new byte[AvfAudioVsockProtocol.DEFAULT_CHUNK_SIZE];

        while (!closed && state != State.STOPPED) {
            if (state == State.PAUSED) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }

            try {
                if (inStream == null) {
                    if (autoReconnect && socketSupplier != null && !closed) {
                        tryReconnect();
                        continue;
                    }
                    break;
                }

                int count = inStream.read(readBuf);
                if (count < 0) {
                    // EOF on stream
                    if (autoReconnect && socketSupplier != null && !closed) {
                        tryReconnect();
                        continue;
                    }
                    break;
                }

                if (count > 0) {
                    processIncomingPcm(readBuf, count);
                }
            } catch (IOException e) {
                if (closed) {
                    break;
                }
                if (autoReconnect && socketSupplier != null) {
                    tryReconnect();
                } else {
                    transitionTo(State.ERROR);
                    break;
                }
            }
        }

        ringBuffer.close();
        if (!closed && state != State.ERROR) {
            transitionTo(State.STOPPED);
        }
    }

    private void tryReconnect() {
        try {
            if (currentHandle != null) {
                currentHandle.close();
            }
        } catch (Exception ignored) {
        }
        inStream = null;
        outStream = null;
        currentHandle = null;

        for (int i = 0; i < 10 && !closed; i++) {
            try {
                Thread.sleep(150);
                if (closed) {
                    return;
                }
                connectSocket();
                if (inStream != null) {
                    transitionTo(State.RUNNING);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        transitionTo(State.ERROR);
    }

    private void processIncomingPcm(byte[] buffer, int length) {
        int aligned = AvfAudioVsockProtocol.filterMalformedBytes(buffer, length);
        if (aligned <= 0) {
            return;
        }

        // Silence standby check
        boolean silent = AvfAudioVsockProtocol.isSilence(buffer, 0, aligned);
        if (silent) {
            int silentFrames = aligned / AvfAudioVsockProtocol.FRAME_SIZE_BYTES;
            long totalSilent = silenceFramesDetected.addAndGet(silentFrames);
            if (totalSilent >= silenceStandbyThresholdFrames) {
                isStandby = true;
            }
        } else {
            silenceFramesDetected.set(0);
            isStandby = false;
        }

        totalBytesIngested.addAndGet(aligned);
        ringBuffer.write(buffer, 0, aligned);
    }

    /**
     * Directly injects a PCM chunk into the channel pipeline, useful for testing
     * or instant resumption from standby.
     */
    public void feedPcmChunk(byte[] chunk) {
        if (chunk != null && chunk.length > 0) {
            processIncomingPcm(chunk, chunk.length);
        }
    }

    @Override
    public void pause() {
        synchronized (stateLock) {
            if (state == State.RUNNING) {
                transitionTo(State.PAUSED);
            }
        }
    }

    @Override
    public void stop() {
        synchronized (stateLock) {
            if (state == State.STOPPED) {
                return;
            }
            transitionTo(State.STOPPED);
            stopPumpThread();
            ringBuffer.close();
        }
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

    private void transitionTo(State newState) {
        synchronized (stateLock) {
            State old = this.state;
            if (old == newState) {
                return;
            }
            this.state = newState;
            for (StateListener listener : stateListeners) {
                try {
                    listener.onStateChanged(old, newState);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Returns an {@link InputStream} reading from the bounded backpressure audio pipeline.
     */
    public InputStream openStream() {
        return pcmInputStream;
    }

    /**
     * Alias for {@link #openStream()}.
     */
    public InputStream inputStream() {
        return openStream();
    }

    public boolean isConnected() {
        return inStream != null && (state == State.RUNNING || state == State.STARTING);
    }

    public boolean isStandby() {
        return isStandby;
    }

    public int vsockPort() {
        return port;
    }

    public void setAutoReconnect(boolean autoReconnect) {
        this.autoReconnect = autoReconnect;
    }

    public boolean autoReconnect() {
        return autoReconnect;
    }

    public void setBufferCapacity(int capacityBytes) {
        ringBuffer.setCapacity(capacityBytes);
    }

    public int bufferCapacity() {
        return ringBuffer.capacity();
    }

    public int bufferedBytes() {
        return ringBuffer.available();
    }

    public long backpressureDropCount() {
        return backpressureDropCount.get();
    }

    public long silenceFramesDetected() {
        return silenceFramesDetected.get();
    }

    public void setSilenceStandbyThresholdFrames(int thresholdFrames) {
        this.silenceStandbyThresholdFrames = thresholdFrames;
    }

    public long totalBytesIngested() {
        return totalBytesIngested.get();
    }

    @Override
    public void close() {
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            stop();

            try {
                if (closeHook != null) {
                    closeHook.close();
                }
            } catch (Exception ignored) {
            }

            try {
                if (currentHandle != null) {
                    currentHandle.close();
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

    // --- High-Performance Bounded Ring Buffer ---

    static final class AudioRingBuffer {
        private byte[] buffer;
        private int readPos = 0;
        private int writePos = 0;
        private int available = 0;
        private boolean isClosed = false;
        private final AtomicLong dropCounter;

        AudioRingBuffer(int capacity, AtomicLong dropCounter) {
            int cap = Math.min(Math.max(capacity, 1024), AvfAudioVsockProtocol.MAX_BACKPRESSURE_BUFFER);
            this.buffer = new byte[cap];
            this.dropCounter = dropCounter;
        }

        public synchronized void setCapacity(int newCapacity) {
            int cap = Math.min(Math.max(newCapacity, 1024), AvfAudioVsockProtocol.MAX_BACKPRESSURE_BUFFER);
            if (cap == buffer.length) {
                return;
            }
            byte[] newBuf = new byte[cap];
            int copyLen = Math.min(available, cap);
            for (int i = 0; i < copyLen; i++) {
                newBuf[i] = buffer[(readPos + i) % buffer.length];
            }
            this.buffer = newBuf;
            this.readPos = 0;
            this.writePos = copyLen;
            this.available = copyLen;
        }

        public synchronized int capacity() {
            return buffer.length;
        }

        public synchronized int available() {
            return available;
        }

        public synchronized void write(byte[] data, int offset, int length) {
            if (data == null || length <= 0 || isClosed) {
                return;
            }

            int cap = buffer.length;
            int free = cap - available;
            if (length > free) {
                // Backpressure: drop oldest unread bytes to preserve low-latency real-time stream
                int overflow = length - free;
                if (overflow > available) {
                    overflow = available;
                }
                readPos = (readPos + overflow) % cap;
                available -= overflow;
                if (dropCounter != null) {
                    dropCounter.addAndGet(overflow);
                }
            }

            int writeLen = Math.min(length, cap - available);
            for (int i = 0; i < writeLen; i++) {
                buffer[writePos] = data[offset + i];
                writePos = (writePos + 1) % cap;
            }
            available += writeLen;
            notifyAll();
        }

        public synchronized int read(byte[] dest, int offset, int length) throws IOException {
            if (dest == null || length <= 0) {
                return 0;
            }

            while (available == 0) {
                if (isClosed) {
                    return -1;
                }
                try {
                    wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Audio read interrupted", e);
                }
            }

            int toRead = Math.min(length, available);
            int cap = buffer.length;
            for (int i = 0; i < toRead; i++) {
                dest[offset + i] = buffer[readPos];
                readPos = (readPos + 1) % cap;
            }
            available -= toRead;
            return toRead;
        }

        public synchronized void close() {
            isClosed = true;
            notifyAll();
        }
    }

    static final class AudioPcmInputStream extends InputStream {
        private final AudioRingBuffer ringBuffer;

        AudioPcmInputStream(AudioRingBuffer ringBuffer) {
            this.ringBuffer = ringBuffer;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n == -1 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return ringBuffer.read(b, off, len);
        }

        @Override
        public int available() {
            return ringBuffer.available();
        }
    }
}
