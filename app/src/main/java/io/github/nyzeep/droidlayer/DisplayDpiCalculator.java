package io.github.nyzeep.droidlayer;

import io.github.nyzeep.droidlayer.runtime.contract.DisplayMode;

/**
 * Strategy-neutral High-DPI display scale calculator.
 *
 * <p>Conforms to ADR-0017 and Phase 4.6 (High-DPI desktop scaling):
 * Evaluates Android display density (densityDpi) and screen resolution to select
 * an optimal Wayland desktop scale (1.0x, 1.5x, 2.0x), eliminating "ant-sized fonts"
 * and microscopic UI controls on modern high-resolution mobile screens.
 */
public final class DisplayDpiCalculator {

    public static final double DEFAULT_SCALE = 1.0;
    public static final double HIGH_DPI_SCALE = 2.0;
    public static final double MEDIUM_HIGH_DPI_SCALE = 1.5;

    public static final int HIGH_DPI_THRESHOLD = 400;
    public static final int MEDIUM_DPI_THRESHOLD = 280;

    public static final int LARGE_SCREEN_DIMENSION = 2400;

    private DisplayDpiCalculator() {}

    /**
     * Calculates the recommended Wayland display scaling factor based on display density and resolution.
     *
     * @param densityDpi display density in DPI (dots per inch)
     * @param width      viewport or screen width in pixels
     * @param height     viewport or screen height in pixels
     * @return recommended scaling multiplier clamped to [DisplayMode.MIN_SCALE, DisplayMode.MAX_SCALE]
     */
    public static double calculateScale(int densityDpi, int width, int height) {
        if (densityDpi <= 0 || width <= 0 || height <= 0) {
            return DEFAULT_SCALE;
        }

        int maxDimension = Math.max(width, height);

        // High-DPI mobile screens: >=400 dpi or large high-res screens (>=2400px and >=280 dpi) -> 2.0x
        if (densityDpi >= HIGH_DPI_THRESHOLD || (maxDimension >= LARGE_SCREEN_DIMENSION && densityDpi >= MEDIUM_DPI_THRESHOLD)) {
            return clampScale(HIGH_DPI_SCALE);
        }

        // Medium-High DPI screens: >=280 dpi -> 1.5x
        if (densityDpi >= MEDIUM_DPI_THRESHOLD) {
            return clampScale(MEDIUM_HIGH_DPI_SCALE);
        }

        return clampScale(DEFAULT_SCALE);
    }

    /**
     * Clamps a raw scaling factor to valid DisplayMode bounds.
     *
     * @param scale raw scaling factor
     * @return bounded scaling factor
     */
    public static double clampScale(double scale) {
        if (Double.isNaN(scale) || Double.isInfinite(scale)) {
            return DEFAULT_SCALE;
        }
        return Math.max(DisplayMode.MIN_SCALE, Math.min(DisplayMode.MAX_SCALE, scale));
    }
}
