package io.github.nyzeep.orchestella.runtime.contract;

/**
 * Strategy-neutral observer interface for display resolution, scale, and frame rate events.
 *
 * <p>Conforms to ADR-0017 and Ticket 38.
 */
public interface DisplayListener {
    /**
     * Invoked when the overall display mode (dimensions, scale, or FPS) has changed.
     *
     * @param mode the new active display mode
     */
    void onModeChanged(DisplayMode mode);

    /**
     * Invoked when the display surface has been resized.
     *
     * @param width  the new width in pixels
     * @param height the new height in pixels
     */
    void onDisplayResized(int width, int height);

    /**
     * Invoked when the display target or observed frame rate is updated.
     *
     * @param currentFps the updated frame rate
     */
    void onFpsUpdated(int currentFps);
}
