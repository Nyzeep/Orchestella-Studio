package io.github.nyzeep.orchestella;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;

/**
 * The only background-image drawable in the app: covers the view with a uniformly scaled bitmap and
 * crops the overflow, so a photo is filled edge to edge on any screen ratio without being stretched.
 * Geometry comes from {@link CoverCropGeometry}; this class only owns the matrix and the mold.
 *
 * <p>The mold (largest bounds seen) mirrors the pre-IME behaviour: under adjustResize the view shrinks
 * when the keyboard opens, and bounds only ever grow, so the picture keeps its size instead of being
 * squashed, and closing the IME does not flicker through a re-scale.
 */
public final class CoverCropDrawable extends BitmapDrawable {

    private final Matrix matrix = new Matrix();
    private final CoverCropGeometry.Anchor anchor;
    private int moldWidth;
    private int moldHeight;

    public CoverCropDrawable(Resources res, Bitmap bitmap) {
        this(res, bitmap, CoverCropGeometry.Anchor.CENTER);
    }

    public CoverCropDrawable(Resources res, Bitmap bitmap, CoverCropGeometry.Anchor anchor) {
        super(res, bitmap);
        this.anchor = anchor == null ? CoverCropGeometry.Anchor.CENTER : anchor;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        if (bounds == null || bounds.isEmpty()) return;
        if (moldWidth > 0 && bounds.width() <= moldWidth && bounds.height() <= moldHeight) return;
        moldWidth = Math.max(moldWidth, bounds.width());
        moldHeight = Math.max(moldHeight, bounds.height());
        rebuild();
    }

    @Override
    public void draw(Canvas canvas) {
        Bitmap bitmap = getBitmap();
        Rect bounds = getBounds();
        if (bitmap == null || bounds.isEmpty()) {
            return;
        }
        if (moldWidth <= 0 || moldHeight <= 0) {
            onBoundsChange(bounds);
        }
        canvas.save();
        canvas.translate(bounds.left, bounds.top);
        canvas.drawBitmap(bitmap, matrix, getPaint());
        canvas.restore();
    }

    private void rebuild() {
        Bitmap bitmap = getBitmap();
        if (bitmap == null || moldWidth <= 0 || moldHeight <= 0) return;
        CoverCropGeometry.Placement placement = CoverCropGeometry.compute(
                bitmap.getWidth(), bitmap.getHeight(), moldWidth, moldHeight, anchor);
        if (!placement.coversTarget()) return;
        matrix.setScale(placement.scale(), placement.scale());
        matrix.postTranslate(placement.drawLeft(), placement.drawTop());
        invalidateSelf();
    }
}
