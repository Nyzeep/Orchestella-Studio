package io.github.nyzeep.droidlayer.audio;

import io.github.nyzeep.droidlayer.runtime.contract.AudioStreamContract;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * JVM unit tests for {@link LowLatencyAudioPlayer} using test seams.
 */
public class LowLatencyAudioPlayerTest {

    private FakeAudioSource fakeSource;
    private FakeAudioTrackSeam fakeTrack;
    private AudioDriftCompensator compensator;
    private FakeAudioFocusSeam fakeFocusSeam;
    private AudioFocusHandler focusHandler;
    private LowLatencyAudioPlayer player;

    @Before
    public void setUp() {
        fakeSource = new FakeAudioSource();
        fakeTrack = new FakeAudioTrackSeam();
        compensator = new AudioDriftCompensator();
        fakeFocusSeam = new FakeAudioFocusSeam();

        // Create player with seams
        player = new LowLatencyAudioPlayer(
                fakeSource,
                fakeTrack,
                compensator,
                fakeFocusSeam
        );
    }

    @After
    public void tearDown() {
        if (player != null) {
            player.release();
        }
    }

    @Test
    public void testInitialStateIsIdleAndFormatMatchesContract() {
        assertEquals(AudioStreamContract.State.IDLE, player.state());
        assertNotNull(player.format());
        assertEquals(AudioStreamContract.DEFAULT_SAMPLE_RATE_HZ, player.format().sampleRateHz());
        assertEquals(AudioStreamContract.DEFAULT_CHANNELS, player.format().channels());
        assertEquals(AudioStreamContract.DEFAULT_BITS_PER_SAMPLE, player.format().bitsPerSample());
    }

    @Test
    public void testStartTransitionsToRunningAndPlaysTrack() throws Exception {
        CountDownLatch stateLatch = new CountDownLatch(1);
        List<AudioStreamContract.State> states = new CopyOnWriteArrayList<>();

        player.addStateListener((oldState, newState) -> {
            states.add(newState);
            if (newState == AudioStreamContract.State.RUNNING) {
                stateLatch.countDown();
            }
        });

        player.start();
        assertTrue("Player should transition to RUNNING", stateLatch.await(2, TimeUnit.SECONDS));
        assertEquals(AudioStreamContract.State.RUNNING, player.state());
        assertEquals(AudioTrackSeam.PLAYSTATE_PLAYING, fakeTrack.getPlayState());
    }

    @Test
    public void testWorkerThreadPumpsAudioDataToTrack() throws Exception {
        // Feed 19200 bytes (100ms) of dummy PCM
        byte[] testData = new byte[19200];
        for (int i = 0; i < testData.length; i++) {
            testData[i] = (byte) (i % 127);
        }
        fakeSource.setAudioData(testData);

        player.start();

        // Wait for worker thread to write audio to track
        boolean dataWritten = fakeTrack.waitForBytesWritten(19200, 3, TimeUnit.SECONDS);
        assertTrue("Audio track should receive written bytes", dataWritten);
        assertTrue(fakeTrack.getTotalBytesWritten() >= 19200);
    }

    @Test
    public void testPauseAndResumeLifecycle() throws Exception {
        player.start();
        assertEquals(AudioStreamContract.State.RUNNING, player.state());

        player.pause();
        assertEquals(AudioStreamContract.State.PAUSED, player.state());
        assertEquals(AudioTrackSeam.PLAYSTATE_PAUSED, fakeTrack.getPlayState());

        player.resume();
        assertEquals(AudioStreamContract.State.RUNNING, player.state());
        assertEquals(AudioTrackSeam.PLAYSTATE_PLAYING, fakeTrack.getPlayState());
    }

    @Test
    public void testStopReleasesWorkerAndTransitionsToStopped() {
        player.start();
        assertEquals(AudioStreamContract.State.RUNNING, player.state());

        player.stop();
        assertEquals(AudioStreamContract.State.STOPPED, player.state());
        assertEquals(AudioTrackSeam.PLAYSTATE_STOPPED, fakeTrack.getPlayState());
        assertFalse("Worker thread should terminate on stop", player.isWorkerRunning());
    }

    @Test
    public void testReleaseEnsuresHardwareReleaseAndFocusAbandon() {
        player.start();
        player.release();

        assertTrue(fakeTrack.isReleased());
        assertTrue(fakeFocusSeam.isAbandoned());
        assertTrue(fakeSource.isClosed());
        assertFalse(player.isWorkerRunning());
    }

    @Test
    public void testFocusLossTransientPausesAndGainResumes() throws Exception {
        player.start();
        assertEquals(AudioStreamContract.State.RUNNING, player.state());

        // Simulate incoming call
        fakeFocusSeam.dispatchFocusChange(AudioFocusSeam.FOCUS_LOSS_TRANSIENT);
        assertEquals(0.0f, fakeTrack.getCurrentVolume(), 0.001);

        // Call ended
        fakeFocusSeam.dispatchFocusChange(AudioFocusSeam.FOCUS_GAIN);
        assertEquals(1.0f, fakeTrack.getCurrentVolume(), 0.001);
    }

    @Test
    public void testFocusDuckingReducesVolume() {
        player.start();
        fakeFocusSeam.dispatchFocusChange(AudioFocusSeam.FOCUS_LOSS_TRANSIENT_CAN_DUCK);

        assertEquals(0.25f, fakeTrack.getCurrentVolume(), 0.001);

        fakeFocusSeam.dispatchFocusChange(AudioFocusSeam.FOCUS_GAIN);
        assertEquals(1.0f, fakeTrack.getCurrentVolume(), 0.001);
    }

    @Test
    public void testStreamErrorTransitionsToErrorState() throws Exception {
        fakeSource.setThrowOnRead(true);

        CountDownLatch errorLatch = new CountDownLatch(1);
        player.addStateListener((oldState, newState) -> {
            if (newState == AudioStreamContract.State.ERROR) {
                errorLatch.countDown();
            }
        });

        player.start();
        assertTrue("Player should transition to ERROR on read failure", errorLatch.await(2, TimeUnit.SECONDS));
        assertEquals(AudioStreamContract.State.ERROR, player.state());
    }

    // --- Test Doubles & Fakes ---

    private static class FakeAudioSource implements AudioSourceSeam {
        private byte[] audioData = new byte[0];
        private boolean throwOnRead = false;
        private boolean closed = false;

        void setAudioData(byte[] data) {
            this.audioData = data;
        }

        void setThrowOnRead(boolean throwOnRead) {
            this.throwOnRead = throwOnRead;
        }

        @Override
        public InputStream openStream() throws IOException {
            if (throwOnRead) {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("Simulated network stream failure");
                    }
                };
            }
            return new ByteArrayInputStream(audioData) {
                @Override
                public int read(byte[] b, int off, int len) {
                    if (throwOnRead) {
                        try {
                            throw new IOException("Simulated network stream failure");
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    return super.read(b, off, len);
                }
            };
        }

        @Override
        public boolean isConnected() {
            return !closed;
        }

        @Override
        public void close() {
            this.closed = true;
        }

        boolean isClosed() {
            return closed;
        }
    }

    private static class FakeAudioTrackSeam implements AudioTrackSeam {
        private int playState = PLAYSTATE_STOPPED;
        private boolean released = false;
        private float volume = 1.0f;
        private final AtomicInteger bytesWritten = new AtomicInteger(0);

        @Override
        public int getState() {
            return STATE_INITIALIZED;
        }

        @Override
        public int getPlayState() {
            return playState;
        }

        @Override
        public void play() {
            playState = PLAYSTATE_PLAYING;
        }

        @Override
        public void pause() {
            playState = PLAYSTATE_PAUSED;
        }

        @Override
        public void stop() {
            playState = PLAYSTATE_STOPPED;
        }

        @Override
        public void flush() {
        }

        @Override
        public void release() {
            playState = PLAYSTATE_STOPPED;
            released = true;
        }

        @Override
        public int write(byte[] audioData, int offsetInBytes, int sizeInBytes) {
            return write(audioData, offsetInBytes, sizeInBytes, WRITE_BLOCKING);
        }

        @Override
        public int write(byte[] audioData, int offsetInBytes, int sizeInBytes, int writeMode) {
            if (released) {
                return ERROR_INVALID_OPERATION;
            }
            bytesWritten.addAndGet(sizeInBytes);
            return sizeInBytes;
        }

        @Override
        public int setVolume(float gain) {
            this.volume = gain;
            return SUCCESS;
        }

        @Override
        public int getUnderrunCount() {
            return 0;
        }

        @Override
        public int getBufferSizeInFrames() {
            return 2048;
        }

        float getCurrentVolume() {
            return volume;
        }

        int getTotalBytesWritten() {
            return bytesWritten.get();
        }

        boolean isReleased() {
            return released;
        }

        boolean waitForBytesWritten(int minBytes, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
            while (System.currentTimeMillis() < deadline) {
                if (bytesWritten.get() >= minBytes) {
                    return true;
                }
                Thread.sleep(20);
            }
            return bytesWritten.get() >= minBytes;
        }
    }

    private static class FakeAudioFocusSeam implements AudioFocusSeam {
        private boolean abandoned = false;
        private FocusChangeListener activeListener;

        void dispatchFocusChange(int focusChange) {
            if (activeListener != null) {
                activeListener.onFocusChange(focusChange);
            }
        }

        @Override
        public int requestFocus(FocusChangeListener listener) {
            this.activeListener = listener;
            this.abandoned = false;
            return REQUEST_GRANTED;
        }

        @Override
        public int abandonFocus(FocusChangeListener listener) {
            if (this.activeListener == listener) {
                this.activeListener = null;
            }
            this.abandoned = true;
            return REQUEST_GRANTED;
        }

        boolean isAbandoned() {
            return abandoned;
        }
    }
}
