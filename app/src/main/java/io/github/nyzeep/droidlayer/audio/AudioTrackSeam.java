package io.github.nyzeep.droidlayer.audio;

/**
 * Seam interface abstracting Android AudioTrack operations for pure JVM testing.
 */
public interface AudioTrackSeam {

    int STATE_UNINITIALIZED = 0;
    int STATE_INITIALIZED = 1;

    int PLAYSTATE_STOPPED = 1;
    int PLAYSTATE_PAUSED = 2;
    int PLAYSTATE_PLAYING = 3;

    int WRITE_BLOCKING = 0;
    int WRITE_NON_BLOCKING = 1;

    int SUCCESS = 0;
    int ERROR = -1;
    int ERROR_BAD_VALUE = -2;
    int ERROR_INVALID_OPERATION = -3;

    int getState();

    int getPlayState();

    void play() throws IllegalStateException;

    void pause() throws IllegalStateException;

    void stop() throws IllegalStateException;

    void flush();

    void release();

    int write(byte[] audioData, int offsetInBytes, int sizeInBytes);

    int write(byte[] audioData, int offsetInBytes, int sizeInBytes, int writeMode);

    int setVolume(float gain);

    int getUnderrunCount();

    int getBufferSizeInFrames();
}
