package io.github.nyzeep.orchestella.audio;

import io.github.nyzeep.orchestella.runtime.contract.AudioStreamContract;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    private volatile boolean released = false;

    private Thread workerThread;
    private final AtomicLong workerGeneration = new AtomicLong(0);
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
        Thread previous = null;
        boolean needsReset = false;
        synchronized (stateLock) {
            if (released || (currentState == State.RUNNING && isWorkerRunning())) {
                return;
            }
            if (currentState == State.PAUSED) {
                resume();
                return;
            }
            if (currentState == State.ERROR || currentState == State.STOPPED || currentState == State.RUNNING) {
                isRunning.set(false);
                isPaused.set(false);
                previous = detachWorkerLocked();
                needsReset = true;
            }
        }
        if (needsReset) {
            stopWorker(previous, true);
        }

        synchronized (stateLock) {
            if (released || (currentState == State.RUNNING && isWorkerRunning())) {
                return;
            }
            if (needsReset) {
                compensator.reset();
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

            // RUNNING must be published before the worker exists: the worker can hit a
            // null/failing stream immediately and call transitionState(ERROR), and an
            // unconditional RUNNING write after startWorkerThread() would overwrite it.
            transitionState(State.RUNNING);
            startWorkerThread(workerGeneration.incrementAndGet());
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
                // pause() only throws for an already-invalid track (never while audibly
                // playing), so keeping the PAUSED state here matches what is heard.
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
            } catch (Exception playFailure) {
                // The state machine already claims RUNNING, so swallowing a failed play()
                // would leave the UI reporting playback while the track stays silent.
                // Roll the observable state back to PAUSED and record the failure.
                isPaused.set(true);
                transitionState(State.PAUSED);
                android.util.Log.w("AudioPlayer", "resume play failed: "
                        + playFailure.getClass().getSimpleName());
            }
        }
    }

    @Override
    public void stop() {
        Thread previous;
        synchronized (stateLock) {
            if (currentState == State.STOPPED) {
                return;
            }
            isRunning.set(false);
            isPaused.set(false);
            previous = detachWorkerLocked();
        }

        stopWorker(previous, true);

        synchronized (stateLock) {
            if (currentState == State.STOPPED) {
                return;
            }
            try {
                audioTrack.stop();
                audioTrack.flush();
            } catch (Exception ignored) {
                // stop()/flush() only fail on an already-invalid track; the write worker is
                // stopped above, so the track drains to silence either way and STOPPED below
                // is the accurate terminal state.
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
        Thread previous;
        synchronized (stateLock) {
            released = true;
            isRunning.set(false);
            isPaused.set(false);
            previous = detachWorkerLocked();
        }

        try {
            audioSource.close();
        } catch (IOException ignored) {
            // Closing the input source before joining wakes any blocked stream read immediately.
        }
        stopWorker(previous, false);

        synchronized (stateLock) {
            if (currentState != State.STOPPED) {
                try {
                    audioTrack.stop();
                    audioTrack.flush();
                } catch (Exception ignored) {
                    // Best-effort track teardown during release.
                }
                compensator.reset();
                transitionState(State.STOPPED);
            }

            try {
                audioTrack.release();
            } catch (Exception ignored) {
                // release() is best-effort teardown during shutdown; nothing further uses
                // the track after this point.
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

    private void startWorkerThread(long generation) {
        workerThread = new Thread(() -> audioPumpLoop(generation), "Orchestella-AudioPlayerWorker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private Thread detachWorkerLocked() {
        workerGeneration.incrementAndGet();
        Thread previous = workerThread;
        workerThread = null;
        return previous;
    }

    private void stopWorker(Thread worker, boolean resetSource) {
        if (resetSource) {
            audioSource.resetConnection();
        }
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void enterWorkerErrorIfCurrent(long generation) {
        if (isRunning.get() && generation == workerGeneration.get()) {
            isRunning.set(false);
            transitionState(State.ERROR);
        }
    }

    private void audioPumpLoop(long generation) {
        InputStream stream = null;
        try {
            stream = audioSource.openStream();
        } catch (IOException e) {
            enterWorkerErrorIfCurrent(generation);
            return;
        }

        if (stream == null) {
            enterWorkerErrorIfCurrent(generation);
            return;
        }

        byte[] inputBuffer = new byte[CHUNK_SIZE_BYTES];
        byte[] outputBuffer = new byte[CHUNK_SIZE_BYTES];

        try {
            while (isRunning.get()
                    && generation == workerGeneration.get()
                    && !Thread.currentThread().isInterrupted()) {
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
                        // Stream reached EOF while playback remained active (e.g. relay disconnect
                        // or consumer replacement); enter ERROR so onForeground() can reattach.
                        enterWorkerErrorIfCurrent(generation);
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
                    enterWorkerErrorIfCurrent(generation);
                    break;
                }
            }
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Best-effort close when the worker exits or enters ERROR for recovery.
            }
        }
    }

    private void transitionState(State newState) {
        synchronized (stateLock) {
            State oldState = this.currentState;
            if (oldState == newState) {
                return;
            }
            // Terminal states (ERROR/STOPPED) are final: late worker transitions must
            // never resurrect them into an active state. Only a fresh start() may
            // leave a terminal state, via STARTING.
            boolean terminal = oldState == State.ERROR || oldState == State.STOPPED;
            if (terminal && (newState == State.RUNNING || newState == State.PAUSED)) {
                return;
            }
            if (oldState == State.STOPPED && newState == State.ERROR) {
                return;
            }
            this.currentState = newState;
            for (StateListener listener : stateListeners) {
                try {
                    listener.onStateChanged(oldState, newState);
                } catch (Exception ignored) {
                    // Observer isolation: one broken state listener must not break the state
                    // machine or the notifications to the other listeners.
                }
            }
        }
    }
}
