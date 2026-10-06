package io.github.nyzeep.droidlayer.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;

import java.util.Objects;

/**
 * Android framework implementation of {@link AudioFocusSeam} using {@link AudioManager}.
 */
public final class AndroidAudioFocusAdapter implements AudioFocusSeam {

    private final AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private AudioManager.OnAudioFocusChangeListener frameworkListener;

    public AndroidAudioFocusAdapter(Context context) {
        Objects.requireNonNull(context, "context");
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (this.audioManager == null) {
            throw new IllegalStateException("AudioManager not available");
        }
    }

    public AndroidAudioFocusAdapter(AudioManager audioManager) {
        this.audioManager = Objects.requireNonNull(audioManager, "audioManager");
    }

    @Override
    public int requestFocus(FocusChangeListener listener) {
        frameworkListener = focusChange -> {
            if (listener != null) {
                listener.onFocusChange(focusChange);
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();

            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(frameworkListener)
                    .build();

            int result = audioManager.requestAudioFocus(focusRequest);
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? REQUEST_GRANTED : REQUEST_FAILED;
        } else {
            int result = audioManager.requestAudioFocus(
                    frameworkListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? REQUEST_GRANTED : REQUEST_FAILED;
        }
    }

    @Override
    public int abandonFocus(FocusChangeListener listener) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            int result = audioManager.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
            frameworkListener = null;
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? REQUEST_GRANTED : REQUEST_FAILED;
        } else if (frameworkListener != null) {
            int result = audioManager.abandonAudioFocus(frameworkListener);
            frameworkListener = null;
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? REQUEST_GRANTED : REQUEST_FAILED;
        }
        return REQUEST_GRANTED;
    }
}
