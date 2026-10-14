package io.github.nyzeep.orchestella.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Minimal ring progress gauge for the hardware dashboard (memory / storage /
 * battery). One ring + a centered value label; colors come from the active theme
 * so the gauge follows user theming.
 */
public class RingProgressView extends View {

    private final Paint ringTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringValue = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint captionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();

    private float progress = 0f;
    private float cy = 0f;
    private String label = "";
    private String caption = "";
    private int ringColor = 0xFFBD93F9;
    private int trackColor = 0x33FFFFFF;
    private int labelColor = 0xFFF8F8F2;
    private int captionColor = 0xFF9399B2;

    public RingProgressView(Context context) {
        super(context);
        init();
    }

    public RingProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        ringTrack.setStyle(Paint.Style.STROKE);
        ringValue.setStyle(Paint.Style.STROKE);
        ringValue.setStrokeCap(Paint.Cap.SQUARE);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
        captionPaint.setTextAlign(Paint.Align.CENTER);
        captionPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
    }

    public void configure(int color, int labelColor, int captionColor, float density) {
        this.ringColor = color;
        this.labelColor = labelColor;
        this.captionColor = captionColor;
        float strokeWidth = 5.5f * density;
        ringTrack.setStrokeWidth(strokeWidth);
        ringValue.setStrokeWidth(strokeWidth);
        labelPaint.setTextSize(13f * density);
        labelPaint.setFakeBoldText(true);
        captionPaint.setTextSize(9f * density);
        trackColor = (color & 0x00FFFFFF) | 0x2E000000;
        invalidate();
    }

    /** @param fraction 0..1 */
    public void setProgress(float fraction, String label, String caption) {
        this.progress = Math.max(0f, Math.min(1f, fraction));
        this.label = label;
        this.caption = caption;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float size = Math.min(width, height);
        float stroke = ringValue.getStrokeWidth();
        float radius = (size - stroke) / 2f;
        float cx = width / 2f;
        cy = height / 2f;

        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
        ringTrack.setColor(trackColor);
        canvas.drawArc(arcBounds, 0f, 360f, false, ringTrack);
        ringValue.setColor(ringColor);
        if (progress > 0.005f) {
            // 3° tail gap keeps the round cap from swallowing the start marker
            float sweep = Math.max(6f, 360f * progress - 3f);
            canvas.drawArc(arcBounds, -90f, sweep, false, ringValue);
        }

        float baseline = cy - radius * 0.06f;
        labelPaint.setColor(labelColor);
        canvas.drawText(label, cx, baseline, labelPaint);
        captionPaint.setColor(captionColor);
        canvas.drawText(caption, cx, baseline + radius * 0.52f, captionPaint);
    }
}
