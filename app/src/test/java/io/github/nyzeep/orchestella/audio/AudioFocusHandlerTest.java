package io.github.nyzeep.orchestella.audio;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JVM unit tests for {@link AudioFocusHandler} and {@link AudioFocusSeam}.
 *
 * <p>Validates focus states:
 * <ul>
 *   <li>GAIN: restores full volume (1.0f) and resumes pipeline</li>
 *   <li>LOSS / LOSS_TRANSIENT: mutes (0.0f) and pauses reading pipeline</li>
 *   <li>LOSS_TRANSIENT_CAN_DUCK: ducks volume to 0.25f (25%)</li>
 * </ul>
 */
public class AudioFocusHandlerTest {

    private FakeAudioFocusSeam fakeSeam;
    private AudioFocusHandler handler;

    private AtomicReference<Float> reportedVolume;
    private AtomicBoolean pauseCalled;
    private AtomicBoolean resumeCalled;
    private AtomicBoolean permanentLossCalled;

    @Before
    public void setUp() {
        fakeSeam = new FakeAudioFocusSeam();
        reportedVolume = new AtomicReference<>(1.0f);
        pauseCalled = new AtomicBoolean(false);
        resumeCalled = new AtomicBoolean(false);
        permanentLossCalled = new AtomicBoolean(false);

        handler = new AudioFocusHandler(fakeSeam, new AudioFocusHandler.Listener() {
            @Override
            public void onVolumeChanged(float volume) {
                reportedVolume.set(volume);
            }

            @Override
            public void onPlaybackCommand(boolean resume, boolean pause) {
                if (resume) {
                    resumeCalled.set(true);
                }
                if (pause) {
                    pauseCalled.set(true);
                }
            }

            @Override
            public void onFocusLostPermanently() {
                permanentLossCalled.set(true);
            }
        });
    }

    @Test
    public void testRequestFocusGrantedSetsGainState() {
        fakeSeam.setGrantFocus(true);
        boolean granted = handler.requestFocus();

        assertTrue(granted);
        assertTrue(handler.isPlaybackAllowed());
        assertFalse(handler.isDucked());
        assertFalse(handler.isMuted());
        assertEquals(1.0f, handler.getCurrentVolume(), 0.001);
        assertEquals(1.0f, reportedVolume.get(), 0.001);
    }

    @Test
    public void testRequestFocusDenied() {
        fakeSeam.setGrantFocus(false);
        boolean granted = handler.requestFocus();

        assertFalse(granted);
        assertFalse(handler.isPlaybackAllowed());
        assertEquals(0.0f, handler.getCurrentVolume(), 0.001);
    }

    @Test
    public void testFocusLossTransientMutesAndPauses() {
        fakeSeam.setGrantFocus(true);
        handler.requestFocus();

        // Simulate incoming phone call or transient audio interruption
        fakeSeam.dispatchFocusChange(AudioFocusSeam.FOCUS_LOSS_TRANSIENT);

        assertFalse(handler.isPlaybackAllowed());
        assertTrue(handler.isMuted());
        assertFalse(handler.isDucked());
        assertEquals(0.0f, handler.getCurrentVolume(), 0.001);
        assertEquals(0.0f, reportedVolume.get(), 0.001);
        assertTrue(pauseCalled.get());
        assertFalse(permanentLossCalled.get());
    }

    @Test
    public void testFocusGainAfterTransientLossResumes() {
        fakeSeam.setGrantFocus(true);
        handler.requestFocus();
        fakeSeam.dispatchFocusChange(AudioFocusSeam.FOCUS_LOSS_TRANSIENT);

        pauseCalled.set(false);
        // Interruption ended, focus returned
        fakeSeam.dispatchFocusChange(AudioFocusSeam.FOCUS_GAIN);

        assertTrue(handler.isPlaybackAllowed());
        assertFalse(handler.isMuted());
        assertEquals(1.0f, handler.getCurrentVolume(), 0.001);
        assertEquals(1.0f, reportedVolume.get(), 0.001);
        assertTrue(resumeCalled.get());
    }

    @Test
    public void testFocusLossTransientCanDuckLowersVolume() {
        fakeSeam.setGrantFocus(true);
        handler.requestFocus();

        // Notification chime or navigation prompt
        fakeSeam.dispatchFocusChange(AudioFocusSeam.FOCUS_LOSS_TRANSIENT_CAN_DUCK);

        assertTrue("Playback remains active during ducking", handler.isPlaybackAllowed());
        assertTrue(handler.isDucked());
        assertFalse(handler.isMuted());
        assertEquals(0.25f, handler.getCurrentVolume(), 0.001);
        assertEquals(0.25f, reportedVolume.get(), 0.001);
        assertFalse(pauseCalled.get());
    }

    @Test
    public void testFocusGainAfterDuckRestoresFullVolume() {
        fakeSeam.setGrantFocus(true);
        handler.requestFocus();
        fakeSeam.dispatchFocusChange(AudioFocusSeam.FOCUS_LOSS_TRANSIENT_CAN_DUCK);
        assertEquals(0.25f, handler.getCurrentVolume(), 0.001);

        fakeSeam.dispatchFocusChange(AudioFocusSeam.FOCUS_GAIN);
        assertEquals(1.0f, handler.getCurrentVolume(), 0.001);
        assertEquals(1.0f, reportedVolume.get(), 0.001);
        assertFalse(handler.isDucked());
    }

    @Test
    public void testPermanentLossMutesPausesAndNotifiesPermanent() {
        fakeSeam.setGrantFocus(true);
        handler.requestFocus();

        // Another app started playback permanently
        fakeSeam.dispatchFocusChange(AudioFocusSeam.FOCUS_LOSS);

        assertFalse(handler.isPlaybackAllowed());
        assertTrue(handler.isMuted());
        assertEquals(0.0f, handler.getCurrentVolume(), 0.001);
        assertTrue(pauseCalled.get());
        assertTrue(permanentLossCalled.get());
    }

    @Test
    public void testAbandonFocusCleansUp() {
        fakeSeam.setGrantFocus(true);
        handler.requestFocus();
        handler.abandonFocus();

        assertFalse(handler.isPlaybackAllowed());
        assertEquals(0.0f, handler.getCurrentVolume(), 0.001);
        assertTrue(fakeSeam.isAbandoned());
    }

    /**
     * In-memory fake seam for AudioFocus.
     */
    private static class FakeAudioFocusSeam implements AudioFocusSeam {
        private boolean grantFocus = true;
        private boolean abandoned = false;
        private FocusChangeListener activeListener;

        void setGrantFocus(boolean grant) {
            this.grantFocus = grant;
        }

        boolean isAbandoned() {
            return abandoned;
        }

        void dispatchFocusChange(int focusChange) {
            if (activeListener != null) {
                activeListener.onFocusChange(focusChange);
            }
        }

        @Override
        public int requestFocus(FocusChangeListener listener) {
            this.activeListener = listener;
            this.abandoned = false;
            return grantFocus ? REQUEST_GRANTED : REQUEST_FAILED;
        }

        @Override
        public int abandonFocus(FocusChangeListener listener) {
            if (this.activeListener == listener) {
                this.activeListener = null;
            }
            this.abandoned = true;
            return REQUEST_GRANTED;
        }
    }
}
