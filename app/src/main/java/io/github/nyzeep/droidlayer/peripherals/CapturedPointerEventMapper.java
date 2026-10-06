package io.github.nyzeep.droidlayer.peripherals;

import io.github.nyzeep.droidlayer.runtime.contract.PointerEvent;

/**
 * Strategy-neutral mapper for captured Android MotionEvents and physical mouse pointer states.
 *
 * <p>Conforms to ADR-0018:
 * <ul>
 *   <li>Translates Android button state bits to PointerEvent physical mouse button masks;</li>
 *   <li>Maps relative pointer displacement vectors (deltaX, deltaY) to {@link PointerEvent#relativeMotion};</li>
 *   <li>Identifies Ctrl + Alt release shortcuts deterministically.</li>
 * </ul>
 */
public final class CapturedPointerEventMapper {

    // Android MotionEvent button constants
    public static final int ANDROID_BUTTON_PRIMARY = 1;
    public static final int ANDROID_BUTTON_SECONDARY = 2;
    public static final int ANDROID_BUTTON_TERTIARY = 4;

    // Android KeyEvent / MotionEvent meta state constants
    public static final int META_ALT_ON = 0x02;
    public static final int META_ALT_LEFT_ON = 0x10;
    public static final int META_ALT_RIGHT_ON = 0x20;
    public static final int META_CTRL_ON = 0x1000;
    public static final int META_CTRL_LEFT_ON = 0x2000;
    public static final int META_CTRL_RIGHT_ON = 0x4000;

    private CapturedPointerEventMapper() {}

    /**
     * Maps Android MotionEvent button state mask to PointerEvent button mask.
     *
     * @param androidButtonState button state from {@code MotionEvent.getButtonState()}
     * @return combined PointerEvent button mask
     */
    public static int mapButtonMask(int androidButtonState) {
        int mask = 0;
        if ((androidButtonState & ANDROID_BUTTON_PRIMARY) != 0) {
            mask |= PointerEvent.BUTTON_PRIMARY;
        }
        if ((androidButtonState & ANDROID_BUTTON_SECONDARY) != 0) {
            mask |= PointerEvent.BUTTON_SECONDARY;
        }
        if ((androidButtonState & ANDROID_BUTTON_TERTIARY) != 0) {
            mask |= PointerEvent.BUTTON_TERTIARY;
        }
        return mask;
    }

    /**
     * Maps relative displacement and button state into a strategy-neutral {@link PointerEvent}.
     *
     * @param deltaX horizontal relative motion (AXIS_RELATIVE_X)
     * @param deltaY vertical relative motion (AXIS_RELATIVE_Y)
     * @param androidButtonState physical button mask from Android MotionEvent
     * @param timestampNs event timestamp in nanoseconds
     * @return relative motion PointerEvent
     */
    public static PointerEvent mapRelativeMotion(float deltaX, float deltaY, int androidButtonState, long timestampNs) {
        int buttonMask = mapButtonMask(androidButtonState);
        return PointerEvent.relativeMotion(deltaX, deltaY, buttonMask, timestampNs);
    }

    /**
     * Detects whether Ctrl and Alt are both held via boolean flags.
     */
    public static boolean isReleaseShortcut(boolean ctrlPressed, boolean altPressed) {
        return ctrlPressed && altPressed;
    }

    /**
     * Detects whether Ctrl and Alt are both held via metaState bitmask.
     */
    public static boolean isReleaseShortcut(int metaState) {
        boolean ctrl = (metaState & (META_CTRL_ON | META_CTRL_LEFT_ON | META_CTRL_RIGHT_ON)) != 0;
        boolean alt = (metaState & (META_ALT_ON | META_ALT_LEFT_ON | META_ALT_RIGHT_ON)) != 0;
        return ctrl && alt;
    }
}
