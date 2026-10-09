package io.github.nyzeep.orchestella.audio;

import java.util.Objects;

/**
 * Audio focus policy handler and state adapter.
 *
 * <p>Translates Android focus change events to deterministic player state actions:
 * <ul>
 *   <li>GAIN: Restores full volume (1.0f) and signals playback resume</li>
 *   <li>LOSS: Mutes (0.0f), signals playback pause, and flags permanent loss</li>
 *   <li>LOSS_TRANSIENT: Mutes (0.0f) and signals playback pause</li>
 *   <li>LOSS_TRANSIENT_CAN_DUCK: Lowers volume to 0.25f (ducking)</li>
 * </ul>
 */
public final class AudioFocusHandler implements AudioFocusSeam.FocusChangeListener {

    public static final float VOLUME_FULL = 1.0f;
    public static final float VOLUME_DUCK = 0.25f;
    public static final float VOLUME_MUTE = 0.0f;

    public interface Listener {
        void onVolumeChanged(float volume);

        void onPlaybackCommand(boolean resume, boolean pause);

        void onFocusLostPermanently();
    }

    private final AudioFocusSeam seam;
    private final Listener listener;

    private float currentVolume = VOLUME_MUTE;
    private boolean playbackAllowed = false;
    private boolean ducked = false;
    private boolean muted = true;
    private int lastFocusChange = AudioFocusSeam.FOCUS_LOSS;

    public AudioFocusHandler(AudioFocusSeam seam, Listener listener) {
        this.seam = Objects.requireNonNull(seam, "seam");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public synchronized boolean requestFocus() {
        int result = seam.requestFocus(this);
        if (result == AudioFocusSeam.REQUEST_GRANTED) {
            playbackAllowed = true;
            ducked = false;
            muted = false;
            currentVolume = VOLUME_FULL;
            lastFocusChange = AudioFocusSeam.FOCUS_GAIN;
            listener.onVolumeChanged(VOLUME_FULL);
            return true;
        } else {
            playbackAllowed = false;
            muted = true;
            ducked = false;
            currentVolume = VOLUME_MUTE;
            return false;
        }
    }

    public synchronized void abandonFocus() {
        playbackAllowed = false;
        muted = true;
        ducked = false;
        currentVolume = VOLUME_MUTE;
        seam.abandonFocus(this);
    }

    @Override
    public synchronized void onFocusChange(int focusChange) {
        lastFocusChange = focusChange;
        switch (focusChange) {
            case AudioFocusSeam.FOCUS_GAIN:
                playbackAllowed = true;
                ducked = false;
                muted = false;
                currentVolume = VOLUME_FULL;
                listener.onVolumeChanged(VOLUME_FULL);
                listener.onPlaybackCommand(true, false);
                break;

            case AudioFocusSeam.FOCUS_LOSS_TRANSIENT:
                playbackAllowed = false;
                ducked = false;
                muted = true;
                currentVolume = VOLUME_MUTE;
                listener.onVolumeChanged(VOLUME_MUTE);
                listener.onPlaybackCommand(false, true);
                break;

            case AudioFocusSeam.FOCUS_LOSS_TRANSIENT_CAN_DUCK:
                playbackAllowed = true;
                ducked = true;
                muted = false;
                currentVolume = VOLUME_DUCK;
                listener.onVolumeChanged(VOLUME_DUCK);
                listener.onPlaybackCommand(false, false);
                break;

            case AudioFocusSeam.FOCUS_LOSS:
                playbackAllowed = false;
                ducked = false;
                muted = true;
                currentVolume = VOLUME_MUTE;
                listener.onVolumeChanged(VOLUME_MUTE);
                listener.onPlaybackCommand(false, true);
                listener.onFocusLostPermanently();
                break;

            default:
                break;
        }
    }

    public synchronized float getCurrentVolume() {
        return currentVolume;
    }

    public synchronized boolean isPlaybackAllowed() {
        return playbackAllowed;
    }

    public synchronized boolean isDucked() {
        return ducked;
    }

    public synchronized boolean isMuted() {
        return muted;
    }

    public synchronized int getLastFocusChange() {
        return lastFocusChange;
    }
}
