package io.github.nyzeep.droidlayer.runtime.contract;

import java.util.Objects;

/**
 * Strategy-neutral contract for low-latency PCM audio stream output.
 *
 * <p>Conforms to ADR-0013 and ADR-0018: provides a platform-neutral audio specification
 * and stream control interface (start, pause, stop, state observing) for Linux guest audio
 * output streaming to host audio soundcards.
 *
 * <p>Fixed format specification:
 * <ul>
 *   <li>Sample Rate: 48,000 Hz</li>
 *   <li>Bit Depth: 16-bit signed little-endian PCM ("s16le")</li>
 *   <li>Channels: 2 (stereo)</li>
 *   <li>Frame Size: 4 bytes (2 channels * 2 bytes)</li>
 *   <li>Byte Rate: 192,000 bytes/second</li>
 *   <li>Default Transport Port: 9104 (PulseAudio TCP / vsock)</li>
 * </ul>
 */
public interface AudioStreamContract {
    int DEFAULT_SAMPLE_RATE_HZ = 48000;
    int DEFAULT_BITS_PER_SAMPLE = 16;
    int DEFAULT_CHANNELS = 2;
    int DEFAULT_BYTES_PER_SAMPLE = 2;
    int DEFAULT_FRAME_SIZE_BYTES = 4;
    int DEFAULT_BYTES_PER_SECOND = 192000;
    int DEFAULT_PORT = 9104;

    /**
     * Operational state of the audio stream pipeline.
     */
    enum State {
        IDLE,
        STARTING,
        RUNNING,
        PAUSED,
        STOPPED,
        ERROR
    }

    /**
     * Immutable audio format descriptor.
     */
    final class Format {
        public static final Format DEFAULT = new Format(
                DEFAULT_SAMPLE_RATE_HZ,
                DEFAULT_BITS_PER_SAMPLE,
                DEFAULT_CHANNELS,
                "s16le");

        private final int sampleRateHz;
        private final int bitsPerSample;
        private final int channels;
        private final String encoding;

        public Format(int sampleRateHz, int bitsPerSample, int channels, String encoding) {
            this.sampleRateHz = sampleRateHz;
            this.bitsPerSample = bitsPerSample;
            this.channels = channels;
            this.encoding = Objects.requireNonNull(encoding, "encoding");
        }

        public int sampleRateHz() {
            return sampleRateHz;
        }

        public int bitsPerSample() {
            return bitsPerSample;
        }

        public int channels() {
            return channels;
        }

        public String encoding() {
            return encoding;
        }

        public int frameSizeBytes() {
            return channels * (bitsPerSample / 8);
        }

        public int bytesPerSecond() {
            return sampleRateHz * frameSizeBytes();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Format)) {
                return false;
            }
            Format format = (Format) o;
            return sampleRateHz == format.sampleRateHz
                    && bitsPerSample == format.bitsPerSample
                    && channels == format.channels
                    && encoding.equals(format.encoding);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sampleRateHz, bitsPerSample, channels, encoding);
        }

        @Override
        public String toString() {
            return "Format{"
                    + "sampleRateHz=" + sampleRateHz
                    + ", bitsPerSample=" + bitsPerSample
                    + ", channels=" + channels
                    + ", encoding='" + encoding + '\''
                    + '}';
        }
    }

    /**
     * Observer for audio pipeline state transitions.
     */
    @FunctionalInterface
    interface StateListener {
        void onStateChanged(State oldState, State newState);
    }

    /**
     * Returns the active audio stream format.
     */
    Format format();

    /**
     * Returns the current lifecycle state of the audio stream.
     */
    State state();

    /**
     * Starts or resumes audio stream playback.
     */
    void start();

    /**
     * Pauses audio stream playback without releasing hardware resources.
     */
    void pause();

    /**
     * Stops audio stream playback and releases resources.
     */
    void stop();

    /**
     * Registers a listener to observe state transitions.
     */
    void addStateListener(StateListener listener);

    /**
     * Unregisters a previously registered state listener.
     */
    void removeStateListener(StateListener listener);

    /**
     * Validates whether a sample rate conforms to the fixed 48,000 Hz specification.
     */
    static boolean isValidSampleRate(int sampleRateHz) {
        return sampleRateHz == DEFAULT_SAMPLE_RATE_HZ;
    }

    /**
     * Validates whether a channel count conforms to stereo (2 channels).
     */
    static boolean isValidChannels(int channels) {
        return channels == DEFAULT_CHANNELS;
    }

    /**
     * Validates whether a bit depth conforms to 16-bit PCM.
     */
    static boolean isValidBitsPerSample(int bitsPerSample) {
        return bitsPerSample == DEFAULT_BITS_PER_SAMPLE;
    }

    /**
     * Computes the number of complete audio frames contained within a given byte count.
     */
    static int computeFrameCount(long byteCount) {
        if (byteCount <= 0) {
            return 0;
        }
        return (int) (byteCount / DEFAULT_FRAME_SIZE_BYTES);
    }

    /**
     * Computes the total byte count for a given number of audio frames.
     */
    static long computeByteCount(int frameCount) {
        if (frameCount <= 0) {
            return 0L;
        }
        return ((long) frameCount) * DEFAULT_FRAME_SIZE_BYTES;
    }

    /**
     * Computes the playback duration in milliseconds for a given byte count at 48kHz stereo 16-bit.
     */
    static double computeDurationMs(long byteCount) {
        if (byteCount <= 0) {
            return 0.0;
        }
        return (byteCount * 1000.0) / DEFAULT_BYTES_PER_SECOND;
    }

    /**
     * Computes the number of bytes corresponding to a playback duration in milliseconds,
     * aligned to a 4-byte frame boundary.
     */
    static long computeBytesForDurationMs(double durationMs) {
        if (durationMs <= 0.0) {
            return 0L;
        }
        long unaligned = Math.round((durationMs * DEFAULT_BYTES_PER_SECOND) / 1000.0);
        return unaligned & ~3L;
    }
}
