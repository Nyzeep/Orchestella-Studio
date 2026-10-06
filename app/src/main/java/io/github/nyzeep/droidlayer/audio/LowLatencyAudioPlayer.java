package io.github.nyzeep.droidlayer.audio;

import io.github.nyzeep.droidlayer.runtime.contract.AudioStreamContract;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Low-latency streaming PCM audio player implementation for Android host.
 *
 * <p>Conforms to {@link AudioStreamContract}, ADR-0013, and ADR-0018.
 * Features:
 * <ul>
 *   <li>Fixed 48kHz 16-bit stereo PCM stream pipeline</li>
 *   <li>Integrated {@link AudioDriftCompensator} for 40ms~80ms buffer watermark control</li>
 *   <li>Integrated {@link AudioFocusHandler} for focus ducking, muting, and recovery</li>
 *   <li>Dedicated background worker thread with 100% clean shutdown on release</li>
 * </ul>
 */
public final class LowLatencyAudioPlayer implements AudioStreamContract {

    public static final int CHUNK_SIZE_BYTES = 4096; // 1024 frames = ~21.3ms

    private final AudioSourceSeam audioSource;
    private final AudioTrackSeam audioTrack;
    private final AudioDriftCompensator compensator;
    private final AudioFocusHandler focusHandler;
    private final CopyOnWriteArrayList<StateListener> stateListeners = new CopyOnWriteArrayList<>();

    private volatile State currentState = State.IDLE;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);

    private Thread workerThread;
    private final Object stateLock = new Object();

    public LowLatencyAudioPlayer(
            AudioSourceSeam audioSource,
            AudioTrackSeam audioTrack,
            AudioDriftCompensator compensator,
            AudioFocusSeam focusSeam) {
        this.audioSource = Objects.requireNonNull(audioSource, "audioSource");
        this.audioTrack = Objects.requireNonNull(audioTrack, "audioTrack");
        this.compensator = Objects.requireNonNull(compensator, "compensator");
        Objects.requireNonNull(focusSeam, "focusSeam");

        this.focusHandler = new AudioFocusHandler(focusSeam, new AudioFocusHandler.Listener() {
            @Override
            public void onVolumeChanged(float volume) {
                LowLatencyAudioPlayer.this.audioTrack.setVolume(volume);
            }

            @Override
            public void onPlaybackCommand(boolean resume, boolean pause) {
                if (pause) {
                    pauseInternal(false);
                } else if (resume) {
                    resumeInternal();
                }
            }

            @Override
            public void onFocusLostPermanently() {
                pauseInternal(false);
            }
        });
    }

    public LowLatencyAudioPlayer(
            AudioSourceSeam audioSource,
            AudioTrackSeam audioTrack,
            AudioDriftCompensator compensator,
            AudioFocusHandler focusHandler) {
        this.audioSource = Objects.requireNonNull(audioSource, "audioSource");
        this.audioTrack = Objects.requireNonNull(audioTrack, "audioTrack");
        this.compensator = Objects.requireNonNull(compensator, "compensator");
        this.focusHandler = Objects.requireNonNull(focusHandler, "focusHandler");
    }

    @Override
    public Format format() {
        return Format.DEFAULT;
    }

    @Override
    public State state() {
        return currentState;
    }

    @Override
    public void start() {
        synchronized (stateLock) {
            if (currentState == State.RUNNING) {
                return;
            }
            if (currentState == State.PAUSED) {
                resume();
                return;
            }

            transitionState(State.STARTING);

            boolean focusGranted = focusHandler.requestFocus();
            if (!focusGranted) {
                // Focus not granted immediately; still prepare pipeline but muted
                audioTrack.setVolume(0.0f);
            }

            isRunning.set(true);
            isPaused.set(false);

            try {
                audioTrack.play();
            } catch (Exception e) {
                transitionState(State.ERROR);
                isRunning.set(false);
                return;
            }

            startWorkerThread();
            transitionState(State.RUNNING);
        }
    }

    @Override
    public void pause() {
        pauseInternal(true);
    }

    private void pauseInternal(boolean userInitiated) {
        synchronized (stateLock) {
            if (currentState != State.RUNNING) {
                return;
            }
            transitionState(State.PAUSED);
            isPaused.set(true);
            try {
                audioTrack.pause();
            } catch (Exception ignored) {
            }
        }
    }

    public void resume() {
        resumeInternal();
    }

    private void resumeInternal() {
        synchronized (stateLock) {
            if (currentState != State.PAUSED) {
                return;
            }

            transitionState(State.RUNNING);
            isPaused.set(false);
            focusHandler.requestFocus();

            try {
                audioTrack.play();
                audioTrack.setVolume(focusHandler.getCurrentVolume());
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void stop() {
        synchronized (stateLock) {
            if (currentState == State.STOPPED) {
                return;
            }

            isRunning.set(false);
            isPaused.set(false);

            stopWorkerThread();

            try {
                audioTrack.stop();
                audioTrack.flush();
            } catch (Exception ignored) {
            }

            compensator.reset();
            transitionState(State.STOPPED);
        }
    }

    /**
     * Completely shuts down the player, releases hardware audio handles,
     * abandons audio focus, and closes input audio streams.
     */
    public void release() {
        synchronized (stateLock) {
            stop();

            try {
                audioTrack.release();
            } catch (Exception ignored) {
            }

            try {
                audioSource.close();
            } catch (IOException ignored) {
            }

            focusHandler.abandonFocus();
            stateListeners.clear();
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

    public boolean isWorkerRunning() {
        return workerThread != null && workerThread.isAlive();
    }

    public AudioDriftCompensator getCompensator() {
        return compensator;
    }

    public AudioFocusHandler getFocusHandler() {
        return focusHandler;
    }

    public AudioTrackSeam getAudioTrack() {
        return audioTrack;
    }

    private void startWorkerThread() {
        stopWorkerThread();
        workerThread = new Thread(this::audioPumpLoop, "DroidLayer-AudioPlayerWorker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private void stopWorkerThread() {
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            workerThread = null;
        }
    }

    private void audioPumpLoop() {
        InputStream stream = null;
        try {
            stream = audioSource.openStream();
        } catch (IOException e) {
            if (isRunning.get()) {
                transitionState(State.ERROR);
            }
            return;
        }

        byte[] inputBuffer = new byte[CHUNK_SIZE_BYTES];
        byte[] outputBuffer = new byte[CHUNK_SIZE_BYTES];

        while (isRunning.get() && !Thread.currentThread().isInterrupted()) {
            if (isPaused.get() || !focusHandler.isPlaybackAllowed()) {
                try {
                    Thread.sleep(15);
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }

            try {
                int bytesRead = stream.read(inputBuffer, 0, inputBuffer.length);
                if (bytesRead < 0) {
                    // Stream reached end of file / disconnect
                    break;
                }

                if (bytesRead > 0) {
                    // Align to frame boundary
                    int alignedBytes = (bytesRead / AudioDriftCompensator.BYTES_PER_FRAME) * AudioDriftCompensator.BYTES_PER_FRAME;
                    if (alignedBytes > 0) {
                        compensator.write(inputBuffer, 0, alignedBytes);
                    }
                }

                // Read compensated audio to write into AudioTrack
                int compensatedBytes = compensator.read(outputBuffer, 0, outputBuffer.length);
                if (compensatedBytes > 0 && isRunning.get() && !isPaused.get()) {
                    audioTrack.write(outputBuffer, 0, compensatedBytes, AudioTrackSeam.WRITE_BLOCKING);
                }

            } catch (IOException e) {
                if (isRunning.get()) {
                    transitionState(State.ERROR);
                }
                break;
            }
        }
    }

    private void transitionState(State newState) {
        State oldState = this.currentState;
        if (oldState == newState) {
            return;
        }
        this.currentState = newState;
        for (StateListener listener : stateListeners) {
            try {
                listener.onStateChanged(oldState, newState);
            } catch (Exception ignored) {
            }
        }
    }
}
