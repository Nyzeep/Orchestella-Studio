package io.github.nyzeep.orchestella.runtime.contract.display;

import io.github.nyzeep.orchestella.runtime.contract.DisplayMode;

/**
 * Single source of truth for desktop viewport pixel budgets and aspect-preserving scaling.
 *
 * <p>Unifies host viewport constraints with {@link RfbProtocol} and the guest agent limits
 * ({@code MAX_EDGE = 4096}, {@code MAX_PIXELS = 4_194_304}, {@code MIN_EDGE = 100}) while
 * enforcing even-pixel alignment for compositor and encoder compatibility.
 */
public final class DisplayViewportBudget {

    public static final int MIN_EDGE = DisplayMode.MIN_DIMENSION;
    public static final int MAX_EDGE = 4096;
    public static final long MAX_PIXELS = 4_194_304L;
    public static final long MAX_BYTES = MAX_PIXELS * 4L;

    /**
     * Immutable budgeted viewport dimensions and scaling factors.
     */
    public record FittedViewport(
            int width,
            int height,
            double displayScale,
            double effectiveScale,
            boolean downscaled) {

        public FittedViewport {
            if (!isWithinBudget(width, height)) {
                throw new IllegalArgumentException(
                        "Fitted viewport exceeds budget: " + width + "x" + height);
            }
            if (!Double.isFinite(displayScale) || displayScale <= 0.0
                    || !Double.isFinite(effectiveScale) || effectiveScale <= 0.0) {
                throw new IllegalArgumentException(
                        "Fitted viewport scales must be finite and positive: displayScale="
                                + displayScale + ", effectiveScale=" + effectiveScale);
            }
        }
    }

    private DisplayViewportBudget() {}

    /**
     * Returns true if {@code width x height} satisfies edge bounds and total pixel budget.
     */
    public static boolean isWithinBudget(int width, int height) {
        return width >= MIN_EDGE
                && width <= MAX_EDGE
                && height >= MIN_EDGE
                && height <= MAX_EDGE
                && (long) width * height <= MAX_PIXELS;
    }

    /**
     * Clamps {@code displayScale} into [{@link DisplayMode#MIN_SCALE}, {@link DisplayMode#MAX_SCALE}].
     * Non-finite inputs fall back to {@code 1.0}.
     */
    public static double clampScale(double displayScale) {
        if (!Double.isFinite(displayScale)) {
            return 1.0;
        }
        return Math.max(DisplayMode.MIN_SCALE, Math.min(DisplayMode.MAX_SCALE, displayScale));
    }

    /**
     * Fits raw dimensions into the display budget using default {@code 1.0} display scale.
     */
    public static FittedViewport fitWithinBudget(int rawWidth, int rawHeight) {
        return fitWithinBudget(rawWidth, rawHeight, 1.0);
    }

    /**
     * Fits raw dimensions into the display budget while preserving aspect ratio and even alignment.
     */
    public static FittedViewport fitWithinBudget(int rawWidth, int rawHeight, float displayScale) {
        return fitWithinBudget(rawWidth, rawHeight, (double) displayScale);
    }

    /**
     * Fits raw dimensions into the display budget while preserving aspect ratio and even alignment.
     */
    public static FittedViewport fitWithinBudget(int rawWidth, int rawHeight, double displayScale) {
        if (rawWidth <= 0 || rawHeight <= 0) {
            throw new IllegalArgumentException(
                    "Raw viewport dimensions must be positive: " + rawWidth + "x" + rawHeight);
        }

        double clampedScale = clampScale(displayScale);
        double w = rawWidth;
        double h = rawHeight;

        if (w < MIN_EDGE || h < MIN_EDGE) {
            double upFactor = Math.max((double) MIN_EDGE / w, (double) MIN_EDGE / h);
            w *= upFactor;
            h *= upFactor;
        }

        double downFactor = Math.min(
                1.0,
                Math.min(
                        Math.min((double) MAX_EDGE / w, (double) MAX_EDGE / h),
                        Math.sqrt((double) MAX_PIXELS / (w * h))));
        if (downFactor < 1.0) {
            w *= downFactor;
            h *= downFactor;
        }

        int targetW = alignEvenClamped((int) Math.floor(w));
        int targetH = alignEvenClamped((int) Math.floor(h));

        while ((long) targetW * targetH > MAX_PIXELS) {
            if (targetW >= targetH && targetW - 2 >= MIN_EDGE) {
                targetW -= 2;
            } else if (targetH - 2 >= MIN_EDGE) {
                targetH -= 2;
            } else {
                break;
            }
        }

        boolean downscaled = targetW < rawWidth || targetH < rawHeight;
        double ratio = Math.min((double) targetW / rawWidth, (double) targetH / rawHeight);
        double effectiveScale = downscaled ? clampScale(clampedScale * ratio) : clampedScale;

        return new FittedViewport(targetW, targetH, clampedScale, effectiveScale, downscaled);
    }

    private static int alignEvenClamped(int value) {
        int clamped = Math.max(MIN_EDGE, Math.min(MAX_EDGE, value));
        int even = clamped & ~1;
        return Math.max(MIN_EDGE, even);
    }
}
