package io.github.nyzeep.orchestella;

/**
 * Single source of truth for "fill the target, never distort" background images.
 *
 * <p>A cover crop scales the source by one factor on both axes ({@code scaleX == scaleY}) until it
 * covers the target, then shifts the overflow off-screen according to an anchor. The source is never
 * stretched non-uniformly, so photos, wallpapers and terminal backgrounds keep their proportions on
 * any screen ratio. {@link CoverCropDrawable} consumes this geometry; nothing else should re-derive
 * the matrix, and the arithmetic lives here so it stays unit-testable on a plain JVM.
 */
public final class CoverCropGeometry {

    /** Where the overflow is discarded when the scaled source is larger than the target. */
    public enum Anchor {
        /** Equal overflow on both sides; the source center stays at the target center. */
        CENTER,
        /** Overflow is pushed to the bottom; the source top edge stays visible (good for status-bar art). */
        TOP_CENTER
    }

    /** Immutable result: one uniform scale plus the destination rect and the source rect left visible. */
    public static final class Placement {
        private final float scale;
        private final float drawLeft;
        private final float drawTop;
        private final float drawWidth;
        private final float drawHeight;
        private final int cropLeft;
        private final int cropTop;
        private final int cropWidth;
        private final int cropHeight;

        Placement(float scale, float drawLeft, float drawTop, float drawWidth, float drawHeight,
                int cropLeft, int cropTop, int cropWidth, int cropHeight) {
            this.scale = scale;
            this.drawLeft = drawLeft;
            this.drawTop = drawTop;
            this.drawWidth = drawWidth;
            this.drawHeight = drawHeight;
            this.cropLeft = cropLeft;
            this.cropTop = cropTop;
            this.cropWidth = cropWidth;
            this.cropHeight = cropHeight;
        }

        /** Uniform scale applied to the source on both axes. */
        public float scale() {
            return scale;
        }

        /** Left edge of the scaled source in target coordinates; negative when it overflows. */
        public float drawLeft() {
            return drawLeft;
        }

        /** Top edge of the scaled source in target coordinates; negative when it overflows. */
        public float drawTop() {
            return drawTop;
        }

        /** Width of the scaled source in target coordinates; {@code >= targetWidth}. */
        public float drawWidth() {
            return drawWidth;
        }

        /** Height of the scaled source in target coordinates; {@code >= targetHeight}. */
        public float drawHeight() {
            return drawHeight;
        }

        /** Source-space x of the first visible column. */
        public int cropLeft() {
            return cropLeft;
        }

        /** Source-space y of the first visible row. */
        public int cropTop() {
            return cropTop;
        }

        /** Number of visible source columns; never exceeds the source width. */
        public int cropWidth() {
            return cropWidth;
        }

        /** Number of visible source rows; never exceeds the source height. */
        public int cropHeight() {
            return cropHeight;
        }

        /**
         * True when the scaled source covers the whole target, i.e. no letterbox or pillarbox bar.
         * Degenerate inputs return a zero-size placement and report {@code false}.
         */
        public boolean coversTarget() {
            return drawWidth > 0f && drawHeight > 0f && scale > 0f;
        }
    }

    private CoverCropGeometry() {}

    /** Computes a centered cover placement. */
    public static Placement center(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        return compute(sourceWidth, sourceHeight, targetWidth, targetHeight, Anchor.CENTER);
    }

    /**
     * Computes the uniform cover placement of a {@code sourceWidth x sourceHeight} image into a
     * {@code targetWidth x targetHeight} slot.
     *
     * <p>Degenerate input (any dimension {@code <= 0}) yields a zero-size placement instead of
     * throwing or producing NaN, so callers can keep drawing through transient zero-size layouts.
     */
    public static Placement compute(int sourceWidth, int sourceHeight,
            int targetWidth, int targetHeight, Anchor anchor) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return new Placement(0f, 0f, 0f, 0f, 0f, 0, 0, 0, 0);
        }
        Anchor mode = anchor == null ? Anchor.CENTER : anchor;
        float scale = Math.max(targetWidth / (float) sourceWidth,
                targetHeight / (float) sourceHeight);
        float drawWidth = sourceWidth * scale;
        float drawHeight = sourceHeight * scale;
        float drawLeft = (targetWidth - drawWidth) / 2f;
        float drawTop = mode == Anchor.TOP_CENTER ? 0f : (targetHeight - drawHeight) / 2f;

        // The visible source rect is the target window mapped back through the inverse scale.
        int cropLeft = clamp(Math.round(-drawLeft / scale), 0, sourceWidth);
        int cropTop = clamp(Math.round(-drawTop / scale), 0, sourceHeight);
        int cropWidth = clamp(Math.round(targetWidth / scale), 0, sourceWidth - cropLeft);
        int cropHeight = clamp(Math.round(targetHeight / scale), 0, sourceHeight - cropTop);
        return new Placement(scale, drawLeft, drawTop, drawWidth, drawHeight,
                cropLeft, cropTop, cropWidth, cropHeight);
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }
}
