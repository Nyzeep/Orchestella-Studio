package io.github.nyzeep.orchestella;

import java.util.Objects;

/**
 * Calculates aspect-ratio-preserving geometry and coordinate mappings between
 * the guest desktop framebuffer and the host SurfaceView viewport.
 *
 * <p>Conforms to ADR-0017 and Ticket 39. Supports FIT_CENTER (letterbox / pillarbox),
 * FILL_STRETCH, and CENTER_CROP scaling policies.
 */
public final class ViewportAspectCalculator {

    public enum ScaleType {
        /** Preserve aspect ratio, fitting entirely within viewport with black bars if needed. */
        FIT_CENTER,
        /** Non-uniform scaling to fill the entire viewport without black bars. */
        FILL_STRETCH,
        /** Preserve aspect ratio, scaling up to fill entire viewport, cropping excess dimensions. */
        CENTER_CROP
    }

    /**
     * Immutable calculation result representing destination geometry and forward/inverse coordinate mappings.
     */
    public static final class ViewportMapping {
        private final int contentWidth;
        private final int contentHeight;
        private final int viewportWidth;
        private final int viewportHeight;
        private final int destX;
        private final int destY;
        private final int destWidth;
        private final int destHeight;
        private final double scaleX;
        private final double scaleY;
        private final ScaleType scaleType;

        public ViewportMapping(
                int contentWidth,
                int contentHeight,
                int viewportWidth,
                int viewportHeight,
                int destX,
                int destY,
                int destWidth,
                int destHeight,
                double scaleX,
                double scaleY,
                ScaleType scaleType) {
            this.contentWidth = contentWidth;
            this.contentHeight = contentHeight;
            this.viewportWidth = viewportWidth;
            this.viewportHeight = viewportHeight;
            this.destX = destX;
            this.destY = destY;
            this.destWidth = destWidth;
            this.destHeight = destHeight;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.scaleType = Objects.requireNonNull(scaleType, "scaleType");
        }

        public int contentWidth() {
            return contentWidth;
        }

        public int contentHeight() {
            return contentHeight;
        }

        public int viewportWidth() {
            return viewportWidth;
        }

        public int viewportHeight() {
            return viewportHeight;
        }

        public int destX() {
            return destX;
        }

        public int destY() {
            return destY;
        }

        public int destWidth() {
            return destWidth;
        }

        public int destHeight() {
            return destHeight;
        }

        public double scaleX() {
            return scaleX;
        }

        public double scaleY() {
            return scaleY;
        }

        public ScaleType scaleType() {
            return scaleType;
        }

        /** Returns true if top and bottom letterbox bars are present. */
        public boolean isLetterbox() {
            return destY > 0;
        }

        /** Returns true if left and right pillarbox bars are present. */
        public boolean isPillarbox() {
            return destX > 0;
        }

        /** Returns true if content precisely matches viewport aspect ratio with zero padding. */
        public boolean isExactFit() {
            return destX == 0 && destY == 0 && destWidth == viewportWidth && destHeight == viewportHeight;
        }

        /** Checks if a touch/pointer coordinate in viewport space falls within the active content rectangle. */
        public boolean isInsideContent(float vx, float vy) {
            return vx >= destX && vx < (destX + destWidth) && vy >= destY && vy < (destY + destHeight);
        }

        /** Maps viewport coordinates (vx, vy) to guest content coordinates [cx, cy]. */
        public float[] mapViewportToContent(float vx, float vy) {
            float cx = (vx - destX) * (float) contentWidth / (float) destWidth;
            float cy = (vy - destY) * (float) contentHeight / (float) destHeight;
            return new float[]{cx, cy};
        }

        /** Maps guest content coordinates (cx, cy) to host viewport coordinates [vx, vy]. */
        public float[] mapContentToViewport(float cx, float cy) {
            float vx = destX + (cx * (float) destWidth / (float) contentWidth);
            float vy = destY + (cy * (float) destHeight / (float) contentHeight);
            return new float[]{vx, vy};
        }

        @Override
        public String toString() {
            return "ViewportMapping{"
                    + "content=" + contentWidth + "x" + contentHeight
                    + ", viewport=" + viewportWidth + "x" + viewportHeight
                    + ", dest=[" + destX + "," + destY + ", " + destWidth + "x" + destHeight + "]"
                    + ", scaleType=" + scaleType
                    + '}';
        }
    }

    private ViewportAspectCalculator() {}

    /**
     * Calculates the default {@link ScaleType#FIT_CENTER} aspect-ratio-preserving mapping.
     */
    public static ViewportMapping calculate(
            int contentWidth, int contentHeight, int viewportWidth, int viewportHeight) {
        return calculate(contentWidth, contentHeight, viewportWidth, viewportHeight, ScaleType.FIT_CENTER);
    }

    /**
     * Calculates the mapping according to the specified {@link ScaleType}.
     */
    public static ViewportMapping calculate(
            int contentWidth,
            int contentHeight,
            int viewportWidth,
            int viewportHeight,
            ScaleType scaleType) {
        if (contentWidth <= 0 || contentHeight <= 0) {
            throw new IllegalArgumentException(
                    "Content dimensions must be positive: " + contentWidth + "x" + contentHeight);
        }
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            throw new IllegalArgumentException(
                    "Viewport dimensions must be positive: " + viewportWidth + "x" + viewportHeight);
        }
        Objects.requireNonNull(scaleType, "scaleType");

        switch (scaleType) {
            case FILL_STRETCH: {
                double sx = (double) viewportWidth / contentWidth;
                double sy = (double) viewportHeight / contentHeight;
                return new ViewportMapping(
                        contentWidth, contentHeight, viewportWidth, viewportHeight,
                        0, 0, viewportWidth, viewportHeight,
                        sx, sy, scaleType);
            }
            case CENTER_CROP: {
                double sx = (double) viewportWidth / contentWidth;
                double sy = (double) viewportHeight / contentHeight;
                double scale = Math.max(sx, sy);
                int dw = (int) Math.round(contentWidth * scale);
                int dh = (int) Math.round(contentHeight * scale);
                int dx = (viewportWidth - dw) / 2;
                int dy = (viewportHeight - dh) / 2;
                return new ViewportMapping(
                        contentWidth, contentHeight, viewportWidth, viewportHeight,
                        dx, dy, dw, dh,
                        scale, scale, scaleType);
            }
            case FIT_CENTER:
            default: {
                double contentAspect = (double) contentWidth / (double) contentHeight;
                double viewportAspect = (double) viewportWidth / (double) viewportHeight;

                int dw;
                int dh;
                int dx;
                int dy;
                double scale;

                if (Math.abs(contentAspect - viewportAspect) < 1e-6) {
                    dw = viewportWidth;
                    dh = viewportHeight;
                    dx = 0;
                    dy = 0;
                    scale = (double) viewportWidth / contentWidth;
                } else if (contentAspect > viewportAspect) {
                    // Content is wider than viewport -> letterbox (bars on top and bottom)
                    dw = viewportWidth;
                    scale = (double) viewportWidth / contentWidth;
                    dh = (int) Math.round(contentHeight * scale);
                    dx = 0;
                    dy = (viewportHeight - dh) / 2;
                } else {
                    // Content is narrower than viewport -> pillarbox (bars on left and right)
                    dh = viewportHeight;
                    scale = (double) viewportHeight / contentHeight;
                    dw = (int) Math.round(contentWidth * scale);
                    dx = (viewportWidth - dw) / 2;
                    dy = 0;
                }

                return new ViewportMapping(
                        contentWidth, contentHeight, viewportWidth, viewportHeight,
                        dx, dy, dw, dh,
                        scale, scale, ScaleType.FIT_CENTER);
            }
        }
    }
}
