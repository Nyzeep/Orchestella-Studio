package io.github.nyzeep.orchestella;

import android.app.Activity;
import android.view.View;
import io.github.nyzeep.orchestella.audio.AndroidAudioFocusAdapter;
import io.github.nyzeep.orchestella.audio.AndroidAudioTrackAdapter;
import io.github.nyzeep.orchestella.audio.AudioDriftCompensator;
import io.github.nyzeep.orchestella.audio.AudioFocusSeam;
import io.github.nyzeep.orchestella.audio.AudioSourceSeam;
import io.github.nyzeep.orchestella.audio.AudioTrackSeam;
import io.github.nyzeep.orchestella.audio.FallbackAudioSource;
import io.github.nyzeep.orchestella.audio.LocalSocketAudioSource;
import io.github.nyzeep.orchestella.audio.LowLatencyAudioPlayer;
import io.github.nyzeep.orchestella.audio.TcpAudioSource;
import io.github.nyzeep.orchestella.clipboard.ClipboardBridgeManager;
import io.github.nyzeep.orchestella.peripherals.FileDropTargetListener;
import io.github.nyzeep.orchestella.runtime.contract.AudioStreamContract;
import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import java.io.IOException;
import java.util.function.Function;

/**
 * Peripheral controller split out of DesktopActivity (G-03): audio assembly
 * (FallbackAudioSource → LowLatencyAudioPlayer) with its foreground/pause/release
 * lifecycle, the clipboard bridge with its single serveSession thread, and the
 * file-drop staging targets.
 */
public final class DesktopPeripheralController {

    /** Foreground audio decision, extracted from the player state machine for JVM tests. */
    public enum AudioForegroundAction { RESUME, START, NONE }

    private final Activity activity;
    private final ExecutionSession session;
    private final BridgeClient bridgeClient;
    private final String bridgeToken;
    private final Function<ExecutionSession, DesktopControlClient> controlClientResolver;
    private LowLatencyAudioPlayer audioPlayer;
    private ClipboardBridgeManager clipboardManager;
    private FileDropTargetListener fileDropTargetListener;

    public DesktopPeripheralController(Activity activity, ExecutionSession session, BridgeClient bridgeClient,
            String bridgeToken, Function<ExecutionSession, DesktopControlClient> controlClientResolver) {
        this.activity = activity;
        this.session = session;
        this.bridgeClient = bridgeClient;
        this.bridgeToken = bridgeToken;
        this.controlClientResolver = controlClientResolver;
        assembleAudio();
        assembleClipboardAndServe();
    }

    /** PAUSED resumes and IDLE starts; every other state keeps its current playback. */
    public static AudioForegroundAction foregroundAudioActionFor(AudioStreamContract.State state) {
        if (state == AudioStreamContract.State.PAUSED) return AudioForegroundAction.RESUME;
        if (state == AudioStreamContract.State.IDLE) return AudioForegroundAction.START;
        return AudioForegroundAction.NONE;
    }

    /**
     * Clipboard fallback send: no client or an IO failure reports false; the guest's
     * acceptance verdict passes through unchanged.
     */
    static boolean sendClipboardThrough(ExecutionSession session,
            Function<ExecutionSession, DesktopControlClient> controlClientResolver, String text) {
        DesktopControlClient client = session != null ? controlClientResolver.apply(session) : null;
        if (client == null) return false;
        try {
            return client.clipboardPut(text).accepted();
        } catch (IOException ignored) {
            return false;
        }
    }

    /** Serve gate: exactly one serve thread per session, and only with a bridge token. */
    static boolean shouldStartBridgeServe(ExecutionSession session, String bridgeToken) {
        return session != null && bridgeToken != null && session.markBridgeServing();
    }

    // 1. LowLatencyAudioPlayer assembly (unchanged from the pre-split activity).
    private void assembleAudio() {
        try {
            AudioSourceSeam audioSource = new FallbackAudioSource(
                    new LocalSocketAudioSource(LocalSocketAudioSource.DEFAULT_SOCKET_NAME),
                    new TcpAudioSource(TcpAudioSource.DEFAULT_HOST, TcpAudioSource.DEFAULT_PORT)
            );
            AudioTrackSeam audioTrack = new AndroidAudioTrackAdapter();
            AudioDriftCompensator compensator = new AudioDriftCompensator();
            AudioFocusSeam focusSeam = new AndroidAudioFocusAdapter(activity);
            audioPlayer = new LowLatencyAudioPlayer(audioSource, audioTrack, compensator, focusSeam);
        } catch (Exception ignored) {
            audioPlayer = null;
        }
    }

    // 2. ClipboardBridgeManager assembly plus the single serveSession thread.
    private void assembleClipboardAndServe() {
        if (bridgeClient != null) {
            clipboardManager = new ClipboardBridgeManager(activity, bridgeClient);
        } else {
            clipboardManager = new ClipboardBridgeManager(
                    new ClipboardBridgeManager.AndroidSystemClipboard(activity),
                    text -> sendClipboardThrough(session, controlClientResolver, text)
            );
        }
        if (session != null) {
            session.registerClipboardBridge(clipboardManager);
            if (bridgeToken != null && session.markBridgeServing()) {
                new Thread(() -> {
                    try {
                        AndroidNotificationAdapter notificationAdapter =
                                new AndroidNotificationAdapter(activity);
                        AndroidUrlOpenerAdapter urlOpenerAdapter =
                                new AndroidUrlOpenerAdapter(activity);
                        new BridgeConnector().serveSession(
                                session, bridgeToken,
                                0, 0, new BridgeConnector.Events() {
                                    @Override public void onReady() {}
                                    @Override public void onPong() {}
                                    @Override public void onResize(int c, int r) {}
                                },
                                notificationAdapter, urlOpenerAdapter, clipboardManager);
                    } catch (Exception failure) {
                        // The desktop bridge has no failure callback (Events carries onReady/onPong/
                        // onResize only), so a dead serve loop would otherwise be indistinguishable
                        // from a guest that simply never calls. Log the ending, keep the semantics.
                        android.util.Log.w("DesktopBridge", "desktop bridge serving ended: "
                                + failure.getClass().getSimpleName());
                    }
                }, "desktop-bridge-serve").start();
            }
        }
    }

    // 4. FileDropTargetListener assembly (numbering follows the pre-split activity comments).
    public void attachFileDropTargets(View desktopRoot, View surface) {
        fileDropTargetListener = new FileDropTargetListener(activity, bridgeClient);
        if (desktopRoot != null) {
            fileDropTargetListener.attach(desktopRoot);
        }
        if (surface != null) {
            fileDropTargetListener.attach(surface);
        }
    }

    public void onWindowFocusChanged(boolean hasFocus) {
        if (clipboardManager != null) {
            clipboardManager.onWindowFocusChanged(hasFocus);
        }
    }

    /** Foreground audio entry: PAUSED resumes, IDLE starts. */
    public void onForeground() {
        if (audioPlayer != null) {
            AudioForegroundAction action = foregroundAudioActionFor(audioPlayer.state());
            if (action == AudioForegroundAction.RESUME) {
                audioPlayer.resume();
            } else if (action == AudioForegroundAction.START) {
                audioPlayer.start();
            }
        }
    }

    /** Background audio exit: playback pauses regardless of state. */
    public void onBackground() {
        if (audioPlayer != null) {
            audioPlayer.pause();
        }
    }

    /** Clipboard detach and audio release at activity teardown. */
    public void onActivityDestroyed() {
        if (audioPlayer != null) {
            audioPlayer.release();
            audioPlayer = null;
        }
        if (clipboardManager != null) {
            clipboardManager.detach();
            clipboardManager = null;
        }
    }
}
