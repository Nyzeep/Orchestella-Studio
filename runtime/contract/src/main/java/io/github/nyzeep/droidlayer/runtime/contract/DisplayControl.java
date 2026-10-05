package io.github.nyzeep.droidlayer.runtime.contract;

/**
 * Strategy-neutral contract for display mode negotiation, dynamic resizing, and frame rate control.
 *
 * <p>Conforms to ADR-0002 and ADR-0017: decouples UI viewport rendering and screen rotation from
 * underlying virtualization engines (QEMU vs AVF).
 */
public interface DisplayControl {
    /**
     * Returns the currently active display mode.
     *
     * @return current display mode
     */
    DisplayMode currentMode();

    /**
     * Requests a dynamic resolution change for the display surface.
     *
     * @param width  new width in pixels (100..7680)
     * @param height new height in pixels (100..7680)
     * @return true if accepted and dispatched to the guest/compositor, false otherwise
     */
    boolean resizeDisplay(int width, int height);

    /**
     * Requests updating the display UI scaling factor.
     *
     * @param scale scaling multiplier (0.1..5.0)
     * @return true if accepted, false otherwise
     */
    boolean updateScale(double scale);

    /**
     * Requests updating the target display rendering frame rate.
     *
     * @param targetFps target frames per second (1..240)
     * @return true if accepted, false otherwise
     */
    boolean updateTargetFps(int targetFps);

    /**
     * Registers a listener to observe display changes and frame rate updates.
     *
     * @param listener observer to register
     */
    void addDisplayListener(DisplayListener listener);

    /**
     * Unregisters a previously registered display listener.
     *
     * @param listener observer to remove
     */
    void removeDisplayListener(DisplayListener listener);
}
