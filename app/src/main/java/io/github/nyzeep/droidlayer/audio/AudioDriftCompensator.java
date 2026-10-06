package io.github.nyzeep.droidlayer.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Pure JVM adaptive clock drift compensator for low-latency PCM audio streams.
 *
 * <p>Zero Android platform dependencies. Enforces fixed format:
 * <ul>
 *   <li>Sample Rate: 48,000 Hz</li>
 *   <li>Bit Depth: 16-bit signed PCM (s16le)</li>
 *   <li>Channels: 2 (stereo)</li>
 *   <li>Bytes per frame: 4 bytes (2 channels * 2 bytes)</li>
 *   <li>Constant stream throughput: 192,000 bytes/second</li>
 * </ul>
 *
 * <p>Operational behavior:
 * <ul>
 *   <li>Tracks buffer watermark (target latency window: 40ms ~ 80ms)</li>
 *   <li>When watermark continuously &gt; 120ms, executes micro-drop sampling
 *       (128 ~ 256 frames, smooth crossfade attenuation, inaudible) to prevent latency inflation</li>
 *   <li>When underrun occurs, applies smooth silence interpolation to eliminate pops and clicks</li>
 * </ul>
 */
public final class AudioDriftCompensator {

    public static final int SAMPLE_RATE_HZ = 48000;
    public static final int CHANNELS = 2;
    public static final int BYTES_PER_SAMPLE = 2;
    public static final int BYTES_PER_FRAME = 4; // 2 channels * 2 bytes
    public static final int BYTES_PER_SECOND = 192000;

    public static final double TARGET_MIN_WATERMARK_MS = 40.0;
    public static final double TARGET_MAX_WATERMARK_MS = 80.0;
    public static final double HIGH_WATERMARK_THRESHOLD_MS = 120.0;

    public static final int DEFAULT_DROP_FRAMES = 128; // 128 frames = 512 bytes (~2.67ms)
    public static final int CROSSFADE_FRAMES = 32;     // 32 frames = 128 bytes (~0.67ms)
    public static final int DEFAULT_BUFFER_CAPACITY_BYTES = 131072; // 128KB (~682ms)
    private static final int SUSTAINED_HIGH_THRESHOLD = 2;

    private final byte[] ringBuffer;
    private final int capacity;
    private final int dropFrames;
    private final int crossfadeFrames;

    private int readPos = 0;
    private int writePos = 0;
    private int availableBytes = 0;

    private int sustainedHighWatermarkCount = 0;
    private long droppedFramesCount = 0;
    private long underrunCount = 0;
    private long paddedFramesCount = 0;

    private short lastLeftSample = 0;
    private short lastRightSample = 0;

    public AudioDriftCompensator() {
        this(DEFAULT_BUFFER_CAPACITY_BYTES, DEFAULT_DROP_FRAMES, CROSSFADE_FRAMES);
    }

    public AudioDriftCompensator(int capacityBytes, int dropFrames, int crossfadeFrames) {
        if (capacityBytes <= 0 || capacityBytes % BYTES_PER_FRAME != 0) {
            throw new IllegalArgumentException("Capacity must be positive and frame-aligned (multiple of 4)");
        }
        if (dropFrames <= 0) {
            throw new IllegalArgumentException("dropFrames must be positive");
        }
        if (crossfadeFrames <= 0 || crossfadeFrames > dropFrames) {
            throw new IllegalArgumentException("crossfadeFrames must be positive and <= dropFrames");
        }
        this.capacity = capacityBytes;
        this.ringBuffer = new byte[capacityBytes];
        this.dropFrames = dropFrames;
        this.crossfadeFrames = crossfadeFrames;
    }

    /**
     * Writes incoming PCM bytes into the internal compensator ring buffer.
     *
     * @param data   raw PCM byte array
     * @param offset starting offset
     * @param length byte count (must be frame-aligned)
     * @return number of bytes accepted
     */
    public synchronized int write(byte[] data, int offset, int length) {
        if (data == null) {
            throw new NullPointerException("data buffer cannot be null");
        }
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IndexOutOfBoundsException("Invalid offset/length bounds");
        }
        if (length % BYTES_PER_FRAME != 0) {
            throw new IllegalArgumentException("Audio data must be frame-aligned (multiple of 4 bytes)");
        }
        if (length == 0) {
            return 0;
        }

        // Check high watermark before write to track sustained pressure
        double currentWatermarkMs = getWatermarkMsInternal();
        if (currentWatermarkMs > HIGH_WATERMARK_THRESHOLD_MS) {
            sustainedHighWatermarkCount++;
            if (sustainedHighWatermarkCount >= SUSTAINED_HIGH_THRESHOLD) {
                applyMicroDrop();
                sustainedHighWatermarkCount = 0;
            }
        } else {
            sustainedHighWatermarkCount = 0;
        }

        int bytesToWrite = Math.min(length, capacity - availableBytes);
        bytesToWrite = (bytesToWrite / BYTES_PER_FRAME) * BYTES_PER_FRAME;

        if (bytesToWrite <= 0) {
            // Buffer full: emergency micro drop to make space
            applyMicroDrop();
            bytesToWrite = Math.min(length, capacity - availableBytes);
            bytesToWrite = (bytesToWrite / BYTES_PER_FRAME) * BYTES_PER_FRAME;
            if (bytesToWrite <= 0) {
                return 0;
            }
        }

        int firstPart = Math.min(bytesToWrite, capacity - writePos);
        System.arraycopy(data, offset, ringBuffer, writePos, firstPart);

        int secondPart = bytesToWrite - firstPart;
        if (secondPart > 0) {
            System.arraycopy(data, offset + firstPart, ringBuffer, 0, secondPart);
        }

        writePos = (writePos + bytesToWrite) % capacity;
        availableBytes += bytesToWrite;

        // Check if write pushed watermark past high watermark
        if (getWatermarkMsInternal() > HIGH_WATERMARK_THRESHOLD_MS) {
            sustainedHighWatermarkCount++;
            if (sustainedHighWatermarkCount >= SUSTAINED_HIGH_THRESHOLD) {
                applyMicroDrop();
                sustainedHighWatermarkCount = 0;
            }
        }

        return bytesToWrite;
    }

    /**
     * Reads PCM audio bytes from the compensator into the target buffer.
     * If an underrun occurs, smoothly pads with interpolated silence to prevent pops/clicks.
     *
     * @param buffer target buffer
     * @param offset destination offset
     * @param length requested byte count (must be frame-aligned)
     * @return number of bytes filled into the buffer (always equals length on success)
     */
    public synchronized int read(byte[] buffer, int offset, int length) {
        if (buffer == null) {
            throw new NullPointerException("buffer cannot be null");
        }
        if (offset < 0 || length < 0 || offset + length > buffer.length) {
            throw new IndexOutOfBoundsException("Invalid offset/length bounds");
        }
        if (length % BYTES_PER_FRAME != 0) {
            throw new IllegalArgumentException("Length must be frame-aligned (multiple of 4 bytes)");
        }
        if (length == 0) {
            return 0;
        }

        int availableToRead = Math.min(length, availableBytes);
        availableToRead = (availableToRead / BYTES_PER_FRAME) * BYTES_PER_FRAME;

        if (availableToRead > 0) {
            int firstPart = Math.min(availableToRead, capacity - readPos);
            System.arraycopy(ringBuffer, readPos, buffer, offset, firstPart);

            int secondPart = availableToRead - firstPart;
            if (secondPart > 0) {
                System.arraycopy(ringBuffer, 0, buffer, offset + firstPart, secondPart);
            }

            readPos = (readPos + availableToRead) % capacity;
            availableBytes -= availableToRead;

            // Update last played sample
            int lastFrameOffset = offset + availableToRead - BYTES_PER_FRAME;
            lastLeftSample = (short) ((buffer[lastFrameOffset] & 0xFF) | (buffer[lastFrameOffset + 1] << 8));
            lastRightSample = (short) ((buffer[lastFrameOffset + 2] & 0xFF) | (buffer[lastFrameOffset + 3] << 8));
        }

        if (availableToRead < length) {
            // Buffer underrun: pad remaining bytes with smooth silence fade-out
            underrunCount++;
            int missingBytes = length - availableToRead;
            int missingFrames = missingBytes / BYTES_PER_FRAME;
            paddedFramesCount += missingFrames;

            int padOffset = offset + availableToRead;
            applySmoothSilencePadding(buffer, padOffset, missingFrames);
        }

        return length;
    }

    /**
     * Smoothly ramps down the last known audio sample to zero to eliminate DC pop/click.
     */
    private void applySmoothSilencePadding(byte[] target, int offset, int framesToPad) {
        int rampFrames = Math.min(framesToPad, crossfadeFrames);
        ByteBuffer wrap = ByteBuffer.wrap(target, offset, framesToPad * BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < rampFrames; i++) {
            float fadeFactor = 1.0f - ((float) (i + 1) / (float) rampFrames);
            short left = (short) Math.round(lastLeftSample * fadeFactor);
            short right = (short) Math.round(lastRightSample * fadeFactor);
            wrap.putShort(left);
            wrap.putShort(right);
        }

        // Fill remaining frames with true zero silence
        int remainingFrames = framesToPad - rampFrames;
        if (remainingFrames > 0) {
            Arrays.fill(target, offset + rampFrames * BYTES_PER_FRAME, offset + framesToPad * BYTES_PER_FRAME, (byte) 0);
        }

        lastLeftSample = 0;
        lastRightSample = 0;
    }

    /**
     * Performs micro-drop sampling (drops {@link #dropFrames} frames) with linear crossfade
     * attenuation between the pre-drop and post-drop segments to guarantee click-free transition.
     */
    private void applyMicroDrop() {
        int dropBytes = dropFrames * BYTES_PER_FRAME;
        if (availableBytes < dropBytes + crossfadeFrames * BYTES_PER_FRAME) {
            return;
        }

        // Crossfade first crossfadeFrames of post-drop with existing head
        int xfadeBytes = crossfadeFrames * BYTES_PER_FRAME;
        byte[] preFade = new byte[xfadeBytes];
        byte[] postFade = new byte[xfadeBytes];

        copyFromRing(readPos, preFade, 0, xfadeBytes);
        int postDropPos = (readPos + dropBytes) % capacity;
        copyFromRing(postDropPos, postFade, 0, xfadeBytes);

        // Linear blend
        ByteBuffer preBuf = ByteBuffer.wrap(preFade).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer postBuf = ByteBuffer.wrap(postFade).order(ByteOrder.LITTLE_ENDIAN);
        byte[] blended = new byte[xfadeBytes];
        ByteBuffer blendBuf = ByteBuffer.wrap(blended).order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < crossfadeFrames; i++) {
            float alpha = (float) i / (float) crossfadeFrames;
            short preL = preBuf.getShort();
            short preR = preBuf.getShort();
            short postL = postBuf.getShort();
            short postR = postBuf.getShort();

            short outL = (short) Math.round((1.0f - alpha) * preL + alpha * postL);
            short outR = (short) Math.round((1.0f - alpha) * preR + alpha * postR);

            blendBuf.putShort(outL);
            blendBuf.putShort(outR);
        }

        // Write blended back into ring buffer at postDropPos
        copyToRing(blended, 0, postDropPos, xfadeBytes);

        // Advance read pointer by dropBytes
        readPos = postDropPos;
        availableBytes -= dropBytes;
        droppedFramesCount += dropFrames;
    }

    private void copyFromRing(int srcRingPos, byte[] dst, int dstOffset, int len) {
        int firstPart = Math.min(len, capacity - srcRingPos);
        System.arraycopy(ringBuffer, srcRingPos, dst, dstOffset, firstPart);
        int secondPart = len - firstPart;
        if (secondPart > 0) {
            System.arraycopy(ringBuffer, 0, dst, dstOffset + firstPart, secondPart);
        }
    }

    private void copyToRing(byte[] src, int srcOffset, int dstRingPos, int len) {
        int firstPart = Math.min(len, capacity - dstRingPos);
        System.arraycopy(src, srcOffset, ringBuffer, dstRingPos, firstPart);
        int secondPart = len - firstPart;
        if (secondPart > 0) {
            System.arraycopy(src, srcOffset + firstPart, ringBuffer, 0, secondPart);
        }
    }

    private double getWatermarkMsInternal() {
        return (availableBytes * 1000.0) / BYTES_PER_SECOND;
    }

    public synchronized double getWatermarkMs() {
        return getWatermarkMsInternal();
    }

    public synchronized int getAvailableBytes() {
        return availableBytes;
    }

    public synchronized boolean isWithinTargetWindow() {
        double ms = getWatermarkMsInternal();
        return ms >= TARGET_MIN_WATERMARK_MS && ms <= TARGET_MAX_WATERMARK_MS;
    }

    public synchronized long getDroppedFramesCount() {
        return droppedFramesCount;
    }

    public synchronized long getUnderrunCount() {
        return underrunCount;
    }

    public synchronized long getPaddedFramesCount() {
        return paddedFramesCount;
    }

    public synchronized int getCapacityBytes() {
        return capacity;
    }

    public synchronized void reset() {
        readPos = 0;
        writePos = 0;
        availableBytes = 0;
        sustainedHighWatermarkCount = 0;
        droppedFramesCount = 0;
        underrunCount = 0;
        paddedFramesCount = 0;
        lastLeftSample = 0;
        lastRightSample = 0;
        Arrays.fill(ringBuffer, (byte) 0);
    }

    public static long durationMsToBytes(double durationMs) {
        if (durationMs <= 0.0) {
            return 0L;
        }
        long unaligned = Math.round((durationMs * BYTES_PER_SECOND) / 1000.0);
        return unaligned & ~3L;
    }

    public static double bytesToDurationMs(long byteCount) {
        if (byteCount <= 0) {
            return 0.0;
        }
        return (byteCount * 1000.0) / BYTES_PER_SECOND;
    }
}
