package io.github.nyzeep.droidlayer.peripherals;

import android.os.Build;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import io.github.nyzeep.droidlayer.runtime.contract.InputEvent;
import io.github.nyzeep.droidlayer.runtime.contract.PointerEvent;
import io.github.nyzeep.droidlayer.runtime.contract.WaylandChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * High-performance physical mouse relative pointer capture handler.
 *
 * <p>Conforms to ADR-0018 and Ticket 46:
 * <ul>
 *   <li>Encapsulates Android API 26+ {@code View.requestPointerCapture()} and {@code View.releasePointerCapture()};</li>
 *   <li>Hides system mouse cursor in capture mode (setting {@code PointerIcon.TYPE_NULL}) to eliminate double cursors;</li>
 *   <li>Extracts raw relative displacement vectors (deltaX, deltaY) from captured MotionEvents;</li>
 *   <li>Maps physical mouse button masks and scrolls to strategy-neutral {@link PointerEvent}s dispatched to Wayland;</li>
 *   <li>Detects Ctrl + Alt combination to release pointer capture deterministically;</li>
 *   <li>100% pure JVM testable via {@link PointerCaptureViewSeam} and {@link EventDispatcher}.</li>
 * </ul>
 */
public class PointerCaptureHandler {

    /** Seam interface abstracting View pointer capture and cursor icon management. */
    public interface PointerCaptureViewSeam {
        void requestPointerCapture();
        void releasePointerCapture();
        boolean hasPointerCapture();
        void setPointerIconNull();
        void restorePointerIcon();
    }

    /** Seam interface for dispatching strategy-neutral input events to the graphics channel. */
    @FunctionalInterface
    public interface EventDispatcher {
        boolean dispatchEvent(InputEvent event);
    }

    /** Listener notified when pointer capture state changes. */
    @FunctionalInterface
    public interface CaptureStateListener {
        void onCaptureStateChanged(boolean isCaptured);
    }

    private PointerCaptureViewSeam viewSeam;
    private EventDispatcher eventDispatcher;
    private boolean isCaptured = false;
    private final List<CaptureStateListener> listeners = new ArrayList<>();

    /**
     * Constructs a handler with explicit seams (pure JVM test friendly).
     */
    public PointerCaptureHandler(PointerCaptureViewSeam viewSeam, EventDispatcher eventDispatcher) {
        this.viewSeam = viewSeam;
        this.eventDispatcher = eventDispatcher;
    }

    /**
     * Constructs a handler for an Android View and strategy-neutral WaylandChannel.
     */
    public PointerCaptureHandler(View view, WaylandChannel waylandChannel) {
        attach(view, waylandChannel);
    }

    /**
     * Attaches an Android View and WaylandChannel to this handler.
     */
    public void attach(View view, WaylandChannel waylandChannel) {
        if (view != null) {
            this.viewSeam = new AndroidViewPointerCaptureSeam(view);
        }
        if (waylandChannel != null) {
            this.eventDispatcher = waylandChannel::sendInputEvent;
        }
    }

    /**
     * Detaches this handler, releasing any active capture.
     */
    public void detach() {
        if (isCaptured) {
            releaseCapture();
        }
        this.viewSeam = null;
        this.eventDispatcher = null;
    }

    /**
     * Requests unconstrained pointer capture on the attached view and hides the system cursor.
     */
    public void requestCapture() {
        if (viewSeam != null) {
            viewSeam.requestPointerCapture();
            viewSeam.setPointerIconNull();
        }
        setCapturedState(true);
    }

    /**
     * Releases pointer capture and restores the default system cursor icon.
     */
    public void releaseCapture() {
        if (viewSeam != null) {
            viewSeam.releasePointerCapture();
            viewSeam.restorePointerIcon();
        }
        setCapturedState(false);
    }

    public boolean isCaptured() {
        if (viewSeam != null) {
            return isCaptured && viewSeam.hasPointerCapture();
        }
        return isCaptured;
    }

    private void setCapturedState(boolean captured) {
        if (this.isCaptured != captured) {
            this.isCaptured = captured;
            for (CaptureStateListener listener : listeners) {
                listener.onCaptureStateChanged(captured);
            }
        }
    }

    public void addCaptureStateListener(CaptureStateListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeCaptureStateListener(CaptureStateListener listener) {
        listeners.remove(listener);
    }

    /**
     * Processes Android captured pointer MotionEvents.
     *
     * @param event Android MotionEvent
     * @return true if handled, false otherwise
     */
    public boolean onCapturedPointerEvent(MotionEvent event) {
        if (event == null) {
            return false;
        }

        // Release defense: detect Ctrl + Alt pressed simultaneously
        if (CapturedPointerEventMapper.isReleaseShortcut(event.getMetaState())) {
            releaseCapture();
            return true;
        }

        int action = event.getActionMasked();
        long timestampNs = event.getEventTime() * 1_000_000L;

        if (action == MotionEvent.ACTION_SCROLL) {
            float vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            float hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
            return onCapturedScroll(hScroll, vScroll, timestampNs);
        }

        float deltaX = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X);
        float deltaY = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
        int buttonState = event.getButtonState();

        return onCapturedMotion(deltaX, deltaY, buttonState, timestampNs);
    }

    /**
     * Handles keyboard key events to intercept release shortcuts (Ctrl + Alt).
     */
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event == null) {
            return false;
        }
        if (CapturedPointerEventMapper.isReleaseShortcut(event.getMetaState())) {
            releaseCapture();
            return true;
        }
        return false;
    }

    /**
     * Pure JVM seam for relative motion.
     */
    public boolean onCapturedMotion(float deltaX, float deltaY, int buttonState, long timestampNs) {
        PointerEvent pointerEvent = CapturedPointerEventMapper.mapRelativeMotion(deltaX, deltaY, buttonState, timestampNs);
        return dispatchInternal(pointerEvent);
    }

    /**
     * Pure JVM seam for scroll motion.
     */
    public boolean onCapturedScroll(float deltaX, float deltaY, long timestampNs) {
        PointerEvent scrollEvent = PointerEvent.scroll(0.0, 0.0, deltaX, deltaY, timestampNs);
        return dispatchInternal(scrollEvent);
    }

    /**
     * Pure JVM seam for key event shortcut checking.
     */
    public boolean onKey(int keyCode, int metaState, long timestampNs) {
        if (CapturedPointerEventMapper.isReleaseShortcut(metaState)) {
            releaseCapture();
            return true;
        }
        return false;
    }

    private boolean dispatchInternal(InputEvent event) {
        if (eventDispatcher != null) {
            return eventDispatcher.dispatchEvent(event);
        }
        return false;
    }

    /**
     * Android View pointer capture seam implementation.
     */
    public static final class AndroidViewPointerCaptureSeam implements PointerCaptureViewSeam {
        private final View view;
        private PointerIcon defaultIcon;

        public AndroidViewPointerCaptureSeam(View view) {
            this.view = Objects.requireNonNull(view, "view");
        }

        @Override
        public void requestPointerCapture() {
            if (view != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    view.requestPointerCapture();
                } catch (Throwable ignored) {}
            }
        }

        @Override
        public void releasePointerCapture() {
            if (view != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    view.releasePointerCapture();
                } catch (Throwable ignored) {}
            }
        }

        @Override
        public boolean hasPointerCapture() {
            if (view != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    return view.hasPointerCapture();
                } catch (Throwable ignored) {
                    return false;
                }
            }
            return false;
        }

        @Override
        public void setPointerIconNull() {
            if (view != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    if (defaultIcon == null) {
                        defaultIcon = view.getPointerIcon();
                    }
                    view.setPointerIcon(PointerIcon.getSystemIcon(view.getContext(), PointerIcon.TYPE_NULL));
                } catch (Throwable ignored) {}
            }
        }

        @Override
        public void restorePointerIcon() {
            if (view != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    if (defaultIcon != null) {
                        view.setPointerIcon(defaultIcon);
                    } else {
                        view.setPointerIcon(PointerIcon.getSystemIcon(view.getContext(), PointerIcon.TYPE_DEFAULT));
                    }
                } catch (Throwable ignored) {}
            }
        }
    }
}
