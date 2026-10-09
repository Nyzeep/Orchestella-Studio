package io.github.nyzeep.orchestella.runtime.contract;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests strategy-neutral audio stream contract constants, mathematical constraints,
 * frame calculations, and controller lifecycle.
 */
public final class AudioStreamContractTest {
    public static void main(String[] arguments) {
        verifiesStandardAudioFormatConstants();
        validatesAudioParameters();
        verifiesFrameAndByteMath();
        verifiesDurationCalculations();
        verifiesStreamLifecycleAndListener();
        System.out.println("AUDIO_STREAM_CONTRACT_TEST_OK");
    }

    private static void verifiesStandardAudioFormatConstants() {
        assertEquals(48000, AudioStreamContract.DEFAULT_SAMPLE_RATE_HZ, "fixed 48kHz sample rate");
        assertEquals(16, AudioStreamContract.DEFAULT_BITS_PER_SAMPLE, "16-bit PCM");
        assertEquals(2, AudioStreamContract.DEFAULT_CHANNELS, "stereo 2 channels");
        assertEquals(2, AudioStreamContract.DEFAULT_BYTES_PER_SAMPLE, "2 bytes per sample");
        assertEquals(4, AudioStreamContract.DEFAULT_FRAME_SIZE_BYTES, "frame size = 2 * 2 = 4 bytes");
        assertEquals(192000, AudioStreamContract.DEFAULT_BYTES_PER_SECOND, "192,000 bytes/sec throughput");
        assertEquals(9104, AudioStreamContract.DEFAULT_PORT, "default tcp/vsock port 9104");

        AudioStreamContract.Format defaultFormat = AudioStreamContract.Format.DEFAULT;
        assertEquals(48000, defaultFormat.sampleRateHz(), "format sample rate");
        assertEquals(16, defaultFormat.bitsPerSample(), "format bits per sample");
        assertEquals(2, defaultFormat.channels(), "format channels");
        assertEquals(4, defaultFormat.frameSizeBytes(), "format frame size");
        assertEquals(192000, defaultFormat.bytesPerSecond(), "format bytes per sec");
        assertEquals("s16le", defaultFormat.encoding(), "format encoding s16le");
    }

    private static void validatesAudioParameters() {
        assertTrue(AudioStreamContract.isValidSampleRate(48000), "48kHz is valid");
        assertFalse(AudioStreamContract.isValidSampleRate(44100), "44.1kHz is invalid");
        assertFalse(AudioStreamContract.isValidSampleRate(96000), "96kHz is invalid");
        assertFalse(AudioStreamContract.isValidSampleRate(0), "0Hz is invalid");

        assertTrue(AudioStreamContract.isValidChannels(2), "stereo 2 channels is valid");
        assertFalse(AudioStreamContract.isValidChannels(1), "mono 1 channel is invalid");
        assertFalse(AudioStreamContract.isValidChannels(6), "5.1 channels is invalid");

        assertTrue(AudioStreamContract.isValidBitsPerSample(16), "16-bit is valid");
        assertFalse(AudioStreamContract.isValidBitsPerSample(8), "8-bit is invalid");
        assertFalse(AudioStreamContract.isValidBitsPerSample(24), "24-bit is invalid");
        assertFalse(AudioStreamContract.isValidBitsPerSample(32), "32-bit is invalid");
    }

    private static void verifiesFrameAndByteMath() {
        assertEquals(1024, AudioStreamContract.computeFrameCount(4096), "4096 bytes = 1024 frames");
        assertEquals(0, AudioStreamContract.computeFrameCount(3), "partial frame is truncated to 0");
        assertEquals(1, AudioStreamContract.computeFrameCount(4), "4 bytes = 1 frame");

        assertEquals(4096L, AudioStreamContract.computeByteCount(1024), "1024 frames = 4096 bytes");
        assertEquals(0L, AudioStreamContract.computeByteCount(0), "0 frames = 0 bytes");
    }

    private static void verifiesDurationCalculations() {
        // 192,000 bytes = 1 second = 1000 ms
        assertEquals(1000.0, AudioStreamContract.computeDurationMs(192000), "192KB = 1000ms");
        // 20 ms = 0.02s * 192,000 = 3840 bytes
        assertEquals(20.0, AudioStreamContract.computeDurationMs(3840), "3840 bytes = 20ms");

        assertEquals(192000L, AudioStreamContract.computeBytesForDurationMs(1000.0), "1000ms = 192000 bytes");
        assertEquals(3840L, AudioStreamContract.computeBytesForDurationMs(20.0), "20ms = 3840 bytes");

        // Frame alignment check (must be multiple of 4)
        long unalignedBytes = AudioStreamContract.computeBytesForDurationMs(1.5);
        assertEquals(0L, unalignedBytes % 4L, "computed bytes must be 4-byte frame aligned");
    }

    private static void verifiesStreamLifecycleAndListener() {
        MockAudioStreamController controller = new MockAudioStreamController();
        List<String> transitions = new ArrayList<>();

        controller.addStateListener((oldState, newState) ->
                transitions.add(oldState.name() + "->" + newState.name()));

        assertEquals(AudioStreamContract.State.IDLE, controller.state(), "initial state IDLE");

        controller.start();
        assertEquals(AudioStreamContract.State.RUNNING, controller.state(), "running state");

        controller.pause();
        assertEquals(AudioStreamContract.State.PAUSED, controller.state(), "paused state");

        controller.start();
        assertEquals(AudioStreamContract.State.RUNNING, controller.state(), "resumed running state");

        controller.stop();
        assertEquals(AudioStreamContract.State.STOPPED, controller.state(), "stopped state");

        assertEquals(4, transitions.size(), "4 state transitions recorded");
        assertEquals("IDLE->RUNNING", transitions.get(0), "first transition");
        assertEquals("RUNNING->PAUSED", transitions.get(1), "second transition");
        assertEquals("PAUSED->RUNNING", transitions.get(2), "third transition");
        assertEquals("RUNNING->STOPPED", transitions.get(3), "fourth transition");
    }

    private static final class MockAudioStreamController implements AudioStreamContract {
        private State state = State.IDLE;
        private final List<StateListener> listeners = new ArrayList<>();

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
            transitionTo(State.RUNNING);
        }

        @Override
        public void pause() {
            transitionTo(State.PAUSED);
        }

        @Override
        public void stop() {
            transitionTo(State.STOPPED);
        }

        @Override
        public void addStateListener(StateListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeStateListener(StateListener listener) {
            listeners.remove(listener);
        }

        private void transitionTo(State newState) {
            State oldState = this.state;
            this.state = newState;
            for (StateListener listener : listeners) {
                listener.onStateChanged(oldState, newState);
            }
        }
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertFalse(boolean condition, String description) {
        assertTrue(!condition, description);
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
