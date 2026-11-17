package io.github.nyzeep.orchestella.ui.design.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.design.tokens.OeMotion
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeColors
import io.github.nyzeep.orchestella.ui.theme.ThemeStore
import kotlin.math.roundToInt

/**
 * XML twin of [OeSlider]: the same 16dp gradient track, 4s glow, seeded dots
 * and 24dp white thumb, drawn in a plain [View] so the four legacy layouts can
 * drop `SeekBar` / Material `Slider` without a Compose host.
 *
 * Two call-site shapes are supported on purpose:
 *  - integer `max` / `progress`, matching the three SeekBar layouts;
 *  - float `valueFrom` / `valueTo` / `stepSize` / `value`, matching the setup
 *    wizard layouts that used Material `Slider`.
 *
 * The glow animator stops whenever the view is detached, hidden, its window is
 * invisible, or the platform's animators are disabled — a list scrolled out of
 * view must not keep burning frames.
 */
class OeSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Float listener, same shape as Material's `Slider.OnChangeListener`. */
    fun interface OnValueChangeListener {
        fun onValueChange(slider: OeSliderView, value: Float, fromUser: Boolean)
    }

    /** Integer listener, same shape as `SeekBar.OnSeekBarChangeListener`. */
    fun interface OnProgressChangeListener {
        fun onProgressChanged(slider: OeSliderView?, progress: Int, fromUser: Boolean)
        fun onStartTrackingTouch(slider: OeSliderView?) {}
        fun onStopTrackingTouch(slider: OeSliderView?) {}
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val trackHeightPx = dp(OeSliderSpec.TrackHeightDp)
    private val thumbRadiusPx = dp(OeSliderSpec.ThumbDiameterDp / 2f)
    private val dotRadiusPx = dp(OeSliderSpec.DotDiameterDp / 2f)
    private val bandPx = dp(OeSliderSpec.GlowBandDp)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val trackPath = Path()
    private val trackRect = RectF()
    private val matrix = Matrix()
    private val jitter = OeSliderSpec.dotJitter(OeSliderSpec.DotSeed, OeSliderSpec.DotCount)

    private var fillShader: Shader? = null
    private var glowShader: Shader? = null
    private var shadowShader: Shader? = null

    private var primary = 0
    private var accent = 0
    private var surfaceHigh = 0

    private var phase = 0f
    private var glowAnimator: ValueAnimator? = null
    private var scaleAnimator: ValueAnimator? = null
    private var pressedScale = 1f

    private var progressListener: OnProgressChangeListener? = null
    private var valueListener: OnValueChangeListener? = null

    /** SeekBar-compatible upper bound. */
    var max: Int = 100
        set(value) {
            field = value.coerceAtLeast(0)
            valueTo = field.toFloat()
        }

    /** SeekBar-compatible integer progress. Programmatic sets report `fromUser = false`. */
    var progress: Int
        get() = value.roundToInt()
        set(newValue) {
            setValueInternal(newValue.toFloat(), fromUser = false)
        }

    private var currentValue = 0f

    /** Float value, used by the wizard sliders. Programmatic sets stay silent, like Material Slider. */
    var value: Float
        get() = currentValue
        set(newValue) {
            setValueInternal(newValue, fromUser = false, notify = false)
        }

    private var rangeStart = 0f
    private var rangeEnd = 100f

    var valueFrom: Float
        get() = rangeStart
        set(newValue) {
            rangeStart = newValue
            // XML inflates valueFrom before valueTo; never leave an empty range.
            if (rangeEnd < rangeStart) rangeEnd = rangeStart
            clampToRange()
        }

    var valueTo: Float
        get() = rangeEnd
        set(newValue) {
            rangeEnd = newValue
            if (rangeStart > rangeEnd) rangeStart = rangeEnd
            clampToRange()
        }

    /** 0 means continuous; otherwise values snap to `valueFrom + n * stepSize`. */
    var stepSize: Float = 0f
        set(newValue) {
            field = newValue.coerceAtLeast(0f)
            setValueInternal(currentValue, fromUser = false, notify = false)
        }

    init {
        isClickable = true
        isFocusable = true
        attrs?.let(::readAttributes)
        resolveThemeColors()
        // Static frame when animators are off; reduced-motion screenshots stay stable.
        phase = 0.35f
    }

    private fun readAttributes(attrs: AttributeSet) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.OeSliderView)
        try {
            val hasMax = a.hasValue(R.styleable.OeSliderView_android_max)
            val hasProgress = a.hasValue(R.styleable.OeSliderView_android_progress)
            val hasFrom = a.hasValue(R.styleable.OeSliderView_android_valueFrom)
            val hasTo = a.hasValue(R.styleable.OeSliderView_android_valueTo)
            if (hasMax) max = a.getInt(R.styleable.OeSliderView_android_max, 100)
            if (hasFrom || hasTo) {
                val from = a.getFloat(R.styleable.OeSliderView_android_valueFrom, 0f)
                val to = a.getFloat(R.styleable.OeSliderView_android_valueTo, 100f)
                valueFrom = from
                valueTo = to
            }
            stepSize = a.getFloat(R.styleable.OeSliderView_android_stepSize, 0f)
            val rawValue = when {
                a.hasValue(R.styleable.OeSliderView_android_value) ->
                    a.getFloat(R.styleable.OeSliderView_android_value, valueFrom)
                hasProgress -> a.getInt(R.styleable.OeSliderView_android_progress, 0).toFloat()
                else -> valueFrom
            }
            value = rawValue
        } finally {
            a.recycle()
        }
    }

    private fun resolveThemeColors() {
        val theme: AppTheme = ThemeStore(context).resolve(context)
        primary = theme.primary
        accent = theme.accent
        surfaceHigh = ThemeColors.surfaceHigh(theme)
        fillShader = null
        if (width > 0) buildShaders()
        invalidate()
    }

    private fun buildShaders() {
        if (width <= 0) return
        fillShader = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            intArrayOf(primary, accent),
            null,
            Shader.TileMode.CLAMP,
        )
        glowShader = LinearGradient(
            0f, 0f, bandPx, 0f,
            intArrayOf(0x00FFFFFF, 0xFFFFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        shadowShader = RadialGradient(
            thumbRadiusPx, thumbRadiusPx, thumbRadiusPx * 1.35f,
            intArrayOf(0x47000000, 0x00000000),
            null,
            Shader.TileMode.CLAMP,
        )
    }

    fun setOnProgressChangeListener(listener: OnProgressChangeListener?) {
        progressListener = listener
    }

    fun addOnChangeListener(listener: OnValueChangeListener?) {
        valueListener = listener
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val top = (h - trackHeightPx) / 2f
        trackRect.set(0f, top, w.toFloat(), top + trackHeightPx)
        trackPath.reset()
        trackPath.addRoundRect(trackRect, trackHeightPx / 2f, trackHeightPx / 2f, Path.Direction.CW)
        buildShaders()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (dp(48f) + 0.5f).toInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(maxOf(desiredHeight, suggestedMinimumHeight), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0) return
        val alpha = if (isEnabled) 1f else OeSliderSpec.DisabledAlpha
        val centerY = height / 2f
        val fraction = OeSliderSpec.fractionOf(value, valueFrom..valueTo)
        val filledRight = fraction * width

        // Unfilled capsule.
        trackPaint.color = surfaceHigh
        trackPaint.alpha = (255 * alpha).toInt()
        canvas.drawPath(trackPath, trackPaint)

        // Theme-derived fill.
        fillShader?.let { shader ->
            fillPaint.shader = shader
            fillPaint.alpha = (255 * alpha).toInt()
            val right = filledRight.coerceAtLeast(trackHeightPx)
            canvas.drawRoundRect(0f, trackRect.top, right, trackRect.bottom, trackHeightPx / 2f, trackHeightPx / 2f, fillPaint)
        }

        // Glow + dots, clipped to the capsule. The shader is built once; only the
        // canvas translation and paint alpha change per frame.
        val save = canvas.save()
        canvas.clipPath(trackPath)
        val glowLeft = phase * (width + bandPx) - bandPx
        matrix.setTranslate(glowLeft, 0f)
        glowShader?.setLocalMatrix(matrix)
        glowShader?.let { shader ->
            glowPaint.shader = shader
            glowPaint.alpha = (OeSliderSpec.glowAlpha(phase) * 255 * alpha).toInt()
            canvas.drawRect(glowLeft, trackRect.top, glowLeft + bandPx, trackRect.bottom, glowPaint)
        }
        dotPaint.color = 0xFFFFFFFF.toInt()
        dotPaint.alpha = (OeSliderSpec.DotAlpha * 255 * alpha).toInt()
        val maxJitter = ((trackHeightPx - 2f * dotRadiusPx) / 2f) * 0.6f
        repeat(OeSliderSpec.DotCount) { index ->
            val dotX = OeSliderSpec.dotPhase(phase, index) * width
            val dotY = centerY + jitter[index] * maxJitter
            canvas.drawCircle(dotX, dotY, dotRadiusPx, dotPaint)
        }
        canvas.restoreToCount(save)

        // Thumb: soft 2dp shadow + pressed scale.
        val thumbX = filledRight
        shadowShader?.let { shader ->
            shadowPaint.shader = shader
            shadowPaint.alpha = (255 * alpha).toInt()
            canvas.save()
            canvas.translate(thumbX - thumbRadiusPx, centerY + dp(1f) - thumbRadiusPx)
            canvas.drawCircle(thumbRadiusPx, thumbRadiusPx, thumbRadiusPx * 1.35f * pressedScale, shadowPaint)
            canvas.restore()
        }
        thumbPaint.color = 0xFFFFFFFF.toInt()
        thumbPaint.alpha = (255 * alpha).toInt()
        canvas.drawCircle(thumbX, centerY, thumbRadiusPx * pressedScale, thumbPaint)
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                animatePressed(true)
                updateFromTouch(event.x, fromUser = true)
                progressListener?.onStartTrackingTouch(this)
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                updateFromTouch(event.x, fromUser = true)
                return true
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                animatePressed(false)
                updateFromTouch(event.x, fromUser = true)
                progressListener?.onStopTrackingTouch(this)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromTouch(x: Float, fromUser: Boolean) {
        if (width <= 0) return
        val fraction = (x / width).coerceIn(0f, 1f)
        val target = OeSliderSpec.valueAt(fraction, valueFrom..valueTo)
        setValueInternal(target, fromUser)
    }

    private fun setValueInternal(raw: Float, fromUser: Boolean, notify: Boolean = true) {
        val snapped = snap(raw)
        if (snapped == currentValue) return
        val previousStep = stepIndex(currentValue)
        currentValue = snapped
        invalidate()
        val nextStep = stepIndex(currentValue)
        if (fromUser && stepSize > 0f && nextStep != previousStep) {
            performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.SEGMENT_TICK
                } else {
                    HapticFeedbackConstants.CLOCK_TICK
                },
            )
        }
        if (notify) {
            progressListener?.onProgressChanged(this, progress, fromUser)
            valueListener?.onValueChange(this, value, fromUser)
        }
    }

    private fun clampToRange() {
        when {
            currentValue < rangeStart -> setValueInternal(rangeStart, fromUser = false, notify = false)
            currentValue > rangeEnd -> setValueInternal(rangeEnd, fromUser = false, notify = false)
        }
        invalidate()
    }

    private fun snap(raw: Float): Float {
        val clamped = raw.coerceIn(rangeStart, rangeEnd)
        if (stepSize <= 0f) return clamped
        val steps = ((clamped - rangeStart) / stepSize).roundToInt()
        return (rangeStart + steps * stepSize).coerceIn(rangeStart, rangeEnd)
    }

    private fun stepIndex(candidate: Float): Int =
        if (stepSize <= 0f) 0 else ((candidate - rangeStart) / stepSize).roundToInt()

    private fun animatePressed(pressed: Boolean) {
        scaleAnimator?.cancel()
        val target = if (pressed) OeSliderSpec.PressedScale else 1f
        scaleAnimator = ValueAnimator.ofFloat(pressedScale, target).apply {
            duration = OeMotion.PRESS_MS.toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pressedScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startGlowIfVisible() {
        if (!shouldAnimateGlow() || !ValueAnimator.areAnimatorsEnabled()) {
            stopGlow()
            return
        }
        if (glowAnimator?.isRunning == true) return
        glowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OeSliderSpec.GlowPeriodMillis.toLong()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            setFloatValues(0f, 1f)
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopGlow() {
        glowAnimator?.cancel()
        glowAnimator = null
    }

    /** Test seam for the "invisible slider must not animate" rule. */
    internal fun shouldAnimateGlow(): Boolean = isAttachedToWindow &&
        visibility == VISIBLE &&
        windowVisibility == VISIBLE &&
        isEnabled

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resolveThemeColors()
        startGlowIfVisible()
    }

    override fun onDetachedFromWindow() {
        stopGlow()
        scaleAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) startGlowIfVisible() else stopGlow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startGlowIfVisible() else stopGlow()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (enabled) startGlowIfVisible() else stopGlow()
        invalidate()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.SeekBar::class.java.name
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
            valueFrom,
            valueTo,
            value,
        )
        if (isEnabled) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id && isEnabled) {
            val target = arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)
            if (target != null) {
                val before = value
                updateFromTouch(
                    OeSliderSpec.fractionOf(target, valueFrom..valueTo) * width,
                    fromUser = false,
                )
                if (value != before) {
                    valueListener?.onValueChange(this, value, false)
                }
                return true
            }
        }
        return super.performAccessibilityAction(action, arguments)
    }
}
