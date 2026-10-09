package io.github.nyzeep.orchestella.runtime.contract;

import java.util.Objects;

/**
 * Immutable strategy-neutral display mode specifying resolution, scaling, DPI, and target frame rate.
 *
 * <p>Conforms to ADR-0017 (Wayland graphics channel & display renegotiation).
 */
public final class DisplayMode {
    public static final int MIN_DIMENSION = 100;
    public static final int MAX_DIMENSION = 7680;
    public static final double MIN_SCALE = 0.1;
    public static final double MAX_SCALE = 5.0;
    public static final int MIN_FPS = 1;
    public static final int MAX_FPS = 240;

    private final int width;
    private final int height;
    private final double scale;
    private final int dpi;
    private final int targetFps;

    public DisplayMode(int width, int height, double scale, int dpi, int targetFps) {
        validateDimension(width, "width");
        validateDimension(height, "height");
        validateScale(scale);
        validateDpi(dpi);
        validateFps(targetFps);

        this.width = width;
        this.height = height;
        this.scale = scale;
        this.dpi = dpi;
        this.targetFps = targetFps;
    }

    public static void validateDimension(int value, String fieldName) {
        if (value < MIN_DIMENSION || value > MAX_DIMENSION) {
            throw new IllegalArgumentException(
                    fieldName + " must be between " + MIN_DIMENSION + " and " + MAX_DIMENSION + ": " + value);
        }
    }

    public static void validateScale(double scale) {
        if (Double.isNaN(scale) || Double.isInfinite(scale) || scale < MIN_SCALE || scale > MAX_SCALE) {
            throw new IllegalArgumentException(
                    "scale must be a finite number between " + MIN_SCALE + " and " + MAX_SCALE + ": " + scale);
        }
    }

    public static void validateDpi(int dpi) {
        if (dpi <= 0) {
            throw new IllegalArgumentException("dpi must be positive: " + dpi);
        }
    }

    public static void validateFps(int fps) {
        if (fps < MIN_FPS || fps > MAX_FPS) {
            throw new IllegalArgumentException(
                    "targetFps must be between " + MIN_FPS + " and " + MAX_FPS + ": " + fps);
        }
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public double scale() {
        return scale;
    }

    public int dpi() {
        return dpi;
    }

    public int targetFps() {
        return targetFps;
    }

    public DisplayMode withDimensions(int newWidth, int newHeight) {
        return new DisplayMode(newWidth, newHeight, this.scale, this.dpi, this.targetFps);
    }

    public DisplayMode withScale(double newScale) {
        return new DisplayMode(this.width, this.height, newScale, this.dpi, this.targetFps);
    }

    public DisplayMode withFps(int newFps) {
        return new DisplayMode(this.width, this.height, this.scale, this.dpi, newFps);
    }

    public DisplayMode withDpi(int newDpi) {
        return new DisplayMode(this.width, this.height, this.scale, newDpi, this.targetFps);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DisplayMode)) {
            return false;
        }
        DisplayMode that = (DisplayMode) o;
        return width == that.width
                && height == that.height
                && Double.compare(that.scale, scale) == 0
                && dpi == that.dpi
                && targetFps == that.targetFps;
    }

    @Override
    public int hashCode() {
        return Objects.hash(width, height, scale, dpi, targetFps);
    }

    @Override
    public String toString() {
        return "DisplayMode{"
                + "width=" + width
                + ", height=" + height
                + ", scale=" + scale
                + ", dpi=" + dpi
                + ", targetFps=" + targetFps
                + '}';
    }
}
