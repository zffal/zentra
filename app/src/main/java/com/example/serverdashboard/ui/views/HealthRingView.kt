package com.example.serverdashboard.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.example.serverdashboard.R

/**
 * HealthRingView: Custom Canvas view rendering the Status Island health ring.
 * Features:
 * - 28dp diameter.
 * - Circular track at 10% label opacity.
 * - Live CPU % arc with smooth animation and semantic status colors.
 * - Center dot: pulsing green when online, amber when stale/reconnecting, solid red when offline.
 * - Strict isFinite() guards on all Canvas math.
 */
class HealthRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class HealthStatus {
        ONLINE,
        STALE_RECONNECTING,
        OFFLINE
    }

    private val density = resources.displayMetrics.density
    private val strokeWidth = 2.5f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@HealthRingView.strokeWidth
        strokeCap = Paint.Cap.ROUND
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@HealthRingView.strokeWidth
        strokeCap = Paint.Cap.ROUND
    }

    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pulseRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }

    private val arcBounds = RectF()

    private var currentCpuPercent: Float = 0f
    private var targetCpuPercent: Float = 0f
    private var healthStatus: HealthStatus = HealthStatus.ONLINE

    private var pulseRadiusFraction: Float = 0f
    private var pulseAlphaFraction: Float = 1f

    private var cpuAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    init {
        refreshColors()
        setupPulseAnimator()
    }

    fun refreshColors() {
        val labelPrimary = ContextCompat.getColor(context, R.color.label_primary)
        // Track = 10% label color
        trackPaint.color = adjustAlpha(labelPrimary, 0.10f)

        updateArcColor()
        updateCenterDotColor()
        invalidate()
    }

    private fun updateArcColor() {
        val successColor = ContextCompat.getColor(context, R.color.status_success)
        val warningColor = ContextCompat.getColor(context, R.color.status_warning)
        val criticalColor = ContextCompat.getColor(context, R.color.status_critical)

        arcPaint.color = when {
            healthStatus == HealthStatus.OFFLINE -> criticalColor
            currentCpuPercent >= 90f -> criticalColor
            currentCpuPercent >= 70f -> warningColor
            else -> successColor
        }
    }

    private fun updateCenterDotColor() {
        val successColor = ContextCompat.getColor(context, R.color.status_success)
        val warningColor = ContextCompat.getColor(context, R.color.status_warning)
        val criticalColor = ContextCompat.getColor(context, R.color.status_critical)

        val dotColor = when (healthStatus) {
            HealthStatus.ONLINE -> successColor
            HealthStatus.STALE_RECONNECTING -> warningColor
            HealthStatus.OFFLINE -> criticalColor
        }
        centerDotPaint.color = dotColor
        pulseRingPaint.color = dotColor
    }

    fun setCpuPercent(percent: Float, animate: Boolean = true) {
        val safePercent = if (percent.isFinite() && !percent.isNaN()) percent.coerceIn(0f, 100f) else 0f
        targetCpuPercent = safePercent

        if (!animate || areAnimationsDisabled()) {
            currentCpuPercent = safePercent
            updateArcColor()
            invalidate()
            return
        }

        cpuAnimator?.cancel()
        cpuAnimator = ValueAnimator.ofFloat(currentCpuPercent, targetCpuPercent).apply {
            duration = 320
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedValue as? Float ?: return@addUpdateListener
                if (v.isFinite()) {
                    currentCpuPercent = v
                    updateArcColor()
                    invalidate()
                }
            }
            start()
        }
    }

    fun setHealthStatus(status: HealthStatus) {
        if (healthStatus != status) {
            healthStatus = status
            updateArcColor()
            updateCenterDotColor()
            if (status == HealthStatus.ONLINE) {
                if (pulseAnimator?.isStarted != true && !areAnimationsDisabled()) {
                    pulseAnimator?.start()
                }
            } else {
                pulseAnimator?.cancel()
            }
            invalidate()
        }
    }

    private fun setupPulseAnimator() {
        if (areAnimationsDisabled()) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                pulseRadiusFraction = fraction
                pulseAlphaFraction = (1f - fraction).coerceIn(0f, 1f)
                if (healthStatus == HealthStatus.ONLINE) {
                    invalidate()
                }
            }
            start()
        }
    }

    private fun areAnimationsDisabled(): Boolean {
        return try {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
            scale <= 0.05f
        } catch (_: Throwable) {
            false
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultSize = (28f * density).toInt()
        val resolve = { spec: Int ->
            val mode = MeasureSpec.getMode(spec)
            val size = MeasureSpec.getSize(spec)
            when (mode) {
                MeasureSpec.EXACTLY -> size
                MeasureSpec.AT_MOST -> minOf(defaultSize, size)
                else -> defaultSize
            }
        }
        val w = resolve(widthMeasureSpec)
        val h = resolve(heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || !w.isFinite() || !h.isFinite()) return

        val padding = strokeWidth / 2f + 1f * density
        val cx = w / 2f
        val cy = h / 2f
        val radius = (minOf(w, h) / 2f - padding).coerceAtLeast(1f)

        if (!cx.isFinite() || !cy.isFinite() || !radius.isFinite()) return

        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // 1. Draw circular background track (360 degrees)
        canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint)

        // 2. Draw live CPU percentage arc
        if (currentCpuPercent > 0.5f && currentCpuPercent.isFinite()) {
            val sweep = ((currentCpuPercent / 100f) * 360f).coerceIn(1f, 360f)
            if (sweep.isFinite()) {
                canvas.drawArc(arcBounds, -90f, sweep, false, arcPaint)
            }
        }

        // 3. Draw Center Dot & Pulsing Ring
        val dotRadius = 3f * density
        if (healthStatus == HealthStatus.ONLINE && pulseAnimator?.isRunning == true) {
            // Pulsing ring expands from dotRadius to radius - 1dp
            val maxPulseRadius = (radius - 2f * density).coerceAtLeast(dotRadius + 1f)
            val currentPulseRadius = dotRadius + (maxPulseRadius - dotRadius) * pulseRadiusFraction
            val currentAlpha = (pulseAlphaFraction * 180f).toInt().coerceIn(0, 255)

            if (currentPulseRadius.isFinite()) {
                pulseRingPaint.alpha = currentAlpha
                canvas.drawCircle(cx, cy, currentPulseRadius, pulseRingPaint)
            }
        }

        // Solid center dot
        canvas.drawCircle(cx, cy, dotRadius, centerDotPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (healthStatus == HealthStatus.ONLINE && pulseAnimator?.isStarted != true && !areAnimationsDisabled()) {
            pulseAnimator?.start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cpuAnimator?.cancel()
        pulseAnimator?.cancel()
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = Math.round(Color.alpha(color) * factor).coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
