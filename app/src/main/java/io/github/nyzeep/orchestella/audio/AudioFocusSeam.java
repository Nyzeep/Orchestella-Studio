package io.github.nyzeep.orchestella.audio;

/**
 * Seam interface abstracting Android audio focus management for pure JVM testability.
 */
public interface AudioFocusSeam {

    int FOCUS_GAIN = 1;
    int FOCUS_LOSS = -1;
    int FOCUS_LOSS_TRANSIENT = -2;
    int FOCUS_LOSS_TRANSIENT_CAN_DUCK = 3;

    int REQUEST_FAILED = 0;
    int REQUEST_GRANTED = 1;

    @FunctionalInterface
    interface FocusChangeListener {
        void onFocusChange(int focusChange);
    }

    int requestFocus(FocusChangeListener listener);

    int abandonFocus(FocusChangeListener listener);
}
