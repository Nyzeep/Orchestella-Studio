package io.github.nyzeep.orchestella.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;

import java.util.Objects;

/**
 * Production adapter wrapping Android framework {@link AudioTrack}.
 */
public final class AndroidAudioTrackAdapter implements AudioTrackSeam {

    private final AudioTrack audioTrack;

    public AndroidAudioTrackAdapter() {
        this(createDefaultAudioTrack());
    }

    public AndroidAudioTrackAdapter(AudioTrack audioTrack) {
        this.audioTrack = Objects.requireNonNull(audioTrack, "audioTrack");
    }

    public static AudioTrack createDefaultAudioTrack() {
        int sampleRate = AudioDriftCompensator.SAMPLE_RATE_HZ;
        int channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

        int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        // Double minimum buffer size, clamped to at least 8KB
        int bufferSize = Math.max(minBufferSize * 2, 8192);

        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(audioFormat)
                .build();

        return new AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    @Override
    public int getState() {
        return audioTrack.getState();
    }

    @Override
    public int getPlayState() {
        return audioTrack.getPlayState();
    }

    @Override
    public void play() {
        audioTrack.play();
    }

    @Override
    public void pause() {
        audioTrack.pause();
    }

    @Override
    public void stop() {
        audioTrack.stop();
    }

    @Override
    public void flush() {
        audioTrack.flush();
    }

    @Override
    public void release() {
        audioTrack.release();
    }

    @Override
    public int write(byte[] audioData, int offsetInBytes, int sizeInBytes) {
        return audioTrack.write(audioData, offsetInBytes, sizeInBytes);
    }

    @Override
    public int write(byte[] audioData, int offsetInBytes, int sizeInBytes, int writeMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return audioTrack.write(audioData, offsetInBytes, sizeInBytes, writeMode);
        } else {
            return audioTrack.write(audioData, offsetInBytes, sizeInBytes);
        }
    }

    @Override
    public int setVolume(float gain) {
        return audioTrack.setVolume(gain);
    }

    @Override
    public int getUnderrunCount() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return audioTrack.getUnderrunCount();
        }
        return 0;
    }

    @Override
    public int getBufferSizeInFrames() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return audioTrack.getBufferSizeInFrames();
        }
        return 0;
    }
}
