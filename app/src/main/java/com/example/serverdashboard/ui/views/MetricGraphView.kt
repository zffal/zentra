package com.example.serverdashboard.ui.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.serverdashboard.R
import com.example.serverdashboard.data.model.BarItem
import com.example.serverdashboard.data.model.GraphStyle
import com.example.serverdashboard.ui.util.Formatters
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-performance, customizable metric graph component supporting:
 * 1. SEMI_CIRCLE gauge
 * 2. RING circular gauge
 * 3. LINE historical trend
 * 4. BAR comparison view
 */
class MetricGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    // Paints
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.5f * density
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private val barTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val primaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }

    private val pointHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    init {
        try {
            androidx.core.content.res.ResourcesCompat.getFont(context, R.font.jetbrains_mono_bold)?.let {
                primaryTextPaint.typeface = it
            }
            androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_semibold)?.let {
                labelTextPaint.typeface = it
            }
            androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_regular)?.let {
                subTextPaint.typeface = it
            }
        } catch (e: Throwable) {
            // Graceful fallback to system default typefaces
        }
        refreshThemeColors()
    }

    fun refreshThemeColors() {
        trackPaint.color = ContextCompat.getColor(context, R.color.surface_2)
        gridPaint.color = ContextCompat.getColor(context, R.color.separator)
        barTrackPaint.color = ContextCompat.getColor(context, R.color.surface_2)
        primaryTextPaint.color = ContextCompat.getColor(context, R.color.label_primary)
        labelTextPaint.color = ContextCompat.getColor(context, R.color.label_secondary)
        subTextPaint.color = ContextCompat.getColor(context, R.color.label_tertiary)
        val accentColor = ContextCompat.getColor(context, R.color.accent)
        progressPaint.color = accentColor
        linePaint.color = accentColor
        barFillPaint.color = accentColor
        pointHaloPaint.color = adjustAlpha(accentColor, 0.28f)
        invalidate()
    }

    // State
    private var graphStyle: GraphStyle = GraphStyle.SEMI_CIRCLE
    private var currentValue: Float = 0f
    private var maxValue: Float = 100f
    private var formattedValue: String = "0"
    private var secondaryLabel: String = ""
    private var isRate: Boolean = false
    private val historyPoints = mutableListOf<Float>()
    private val barItems = mutableListOf<BarItem>()

    // Reusable paths & rects
    private val arcRect = RectF()
    private val linePath = Path()
    private val fillPath = Path()
    private val barRect = RectF()

    fun setStyle(style: GraphStyle) {
        if (graphStyle != style) {
            graphStyle = style
            invalidate()
        }
    }

    fun setData(
        value: Float,
        max: Float,
        formatted: String,
        secondary: String = "",
        rate: Boolean = false,
        history: List<Float> = emptyList(),
        bars: List<BarItem> = emptyList()
    ) {
        currentValue = if (value.isFinite() && !value.isNaN() && value >= 0f) value else 0f
        maxValue = if (max.isFinite() && !max.isNaN() && max > 0f) max else 100f
        formattedValue = formatted
        secondaryLabel = secondary
        isRate = rate

        historyPoints.clear()
        for (p in history) {
            val safe = if (p.isFinite() && !p.isNaN() && p >= 0f) p else 0f
            historyPoints.add(safe)
        }

        barItems.clear()
        for (b in bars) {
            val safeValue = if (b.value.isFinite() && !b.value.isNaN() && b.value >= 0f) b.value else 0f
            val safeMax = if (b.maxValue.isFinite() && !b.maxValue.isNaN() && b.maxValue > 0f) b.maxValue else 100f
            barItems.add(b.copy(value = safeValue, maxValue = safeMax))
        }

        // Accent color styling based on percentage thresholds if not rate
        val isUnavailable = formatted.equals("Unavailable", ignoreCase = true)
        val accentColor = when {
            isUnavailable -> ContextCompat.getColor(context, R.color.label_tertiary)
            !isRate && (currentValue / maxValue) >= 0.90f -> ContextCompat.getColor(context, R.color.critical)
            !isRate && (currentValue / maxValue) >= 0.80f -> ContextCompat.getColor(context, R.color.warning)
            else -> ContextCompat.getColor(context, R.color.accent)
        }
        progressPaint.color = accentColor
        linePaint.color = accentColor
        barFillPaint.color = accentColor
        pointHaloPaint.color = adjustAlpha(accentColor, 0.28f)

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        when (graphStyle) {
            GraphStyle.SEMI_CIRCLE -> drawSemiCircleGauge(canvas, w, h)
            GraphStyle.RING -> drawRingGauge(canvas, w, h)
            GraphStyle.LINE -> drawLineGraph(canvas, w, h)
            GraphStyle.BAR -> drawBarGraph(canvas, w, h)
        }
    }

    // ==========================================
    // 1. Semi-Circle Gauge (180° Arc)
    // ==========================================
    private fun drawSemiCircleGauge(canvas: Canvas, w: Float, h: Float) {
        val stroke = 9.5f * density
        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke

        val padding = stroke / 2f + 8f * density
        val diameter = (w - (padding * 2f)).coerceAtMost((h - padding) * 1.8f)
        val radius = diameter / 2f
        val cx = w / 2f
        val cy = h - 16f * density

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // 180° Background track
        canvas.drawArc(arcRect, 180f, 180f, false, trackPaint)

        val isUnavailable = formattedValue.equals("Unavailable", ignoreCase = true)

        // Swept progress arc
        val fraction = (currentValue / maxValue).coerceIn(0f, 1f)
        val sweepAngle = fraction * 180f
        if (!isUnavailable && sweepAngle > 0.5f) {
            canvas.drawArc(arcRect, 180f, sweepAngle, false, progressPaint)
        }

        // Center values
        primaryTextPaint.textSize = (h * 0.22f).coerceIn(16f * density, 26f * density)
        val primaryY = cy - (radius * 0.28f)
        canvas.drawText(formattedValue, cx, primaryY, primaryTextPaint)

        if (secondaryLabel.isNotEmpty()) {
            subTextPaint.textSize = 10.5f * density
            canvas.drawText(secondaryLabel, cx, primaryY + 14f * density, subTextPaint)
        }

        // Min & Max label ticks at bottom ends
        labelTextPaint.textSize = 9.5f * density
        labelTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("0", cx - radius + 2f, cy + 12f * density, labelTextPaint)

        labelTextPaint.textAlign = Paint.Align.RIGHT
        val maxLabel = if (isUnavailable) "—" else if (isRate) Formatters.formatSpeed(maxValue.toLong()) else "100%"
        canvas.drawText(maxLabel, cx + radius - 2f, cy + 12f * density, labelTextPaint)
        labelTextPaint.textAlign = Paint.Align.CENTER
    }

    // ==========================================
    // 2. Ring Gauge (360° Circular Ring)
    // ==========================================
    private fun drawRingGauge(canvas: Canvas, w: Float, h: Float) {
        val stroke = 9.5f * density
        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke

        val size = (w.coerceAtMost(h) - (stroke + 12f * density)).coerceAtLeast(10f)
        val cx = w / 2f
        val cy = h / 2f
        val radius = size / 2f

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Full circular track
        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)

        val isUnavailable = formattedValue.equals("Unavailable", ignoreCase = true)

        // Swept progress
        val fraction = (currentValue / maxValue).coerceIn(0f, 1f)
        val sweep = fraction * 360f
        if (!isUnavailable && sweep > 0.5f) {
            canvas.drawArc(arcRect, -90f, sweep, false, progressPaint)
        }

        // Center primary value
        primaryTextPaint.textSize = (radius * 0.44f).coerceIn(14f * density, 24f * density)
        val textOffset = if (secondaryLabel.isNotEmpty()) 4f * density else 0f
        canvas.drawText(formattedValue, cx, cy + (primaryTextPaint.textSize * 0.35f) - textOffset, primaryTextPaint)

        if (secondaryLabel.isNotEmpty()) {
            subTextPaint.textSize = 10f * density
            canvas.drawText(secondaryLabel, cx, cy + (primaryTextPaint.textSize * 0.9f), subTextPaint)
        }
    }

    // ==========================================
    // 3. Line Graph (Historical Trend)
    // ==========================================
    private fun drawLineGraph(canvas: Canvas, w: Float, h: Float) {
        val padH = 10f * density
        val padV = 8f * density
        val bottomY = h - padV
        val usableW = (w - (padH * 2f)).coerceAtLeast(1f)
        val usableH = (h - (padV * 2f)).coerceAtLeast(1f)

        // Subtle baseline and midpoint grid line
        canvas.drawLine(padH, bottomY, w - padH, bottomY, gridPaint)
        canvas.drawLine(padH, bottomY - (usableH / 2f), w - padH, bottomY - (usableH / 2f), gridPaint)

        val isUnavailable = formattedValue.equals("Unavailable", ignoreCase = true)
        val points = if (historyPoints.isNotEmpty()) historyPoints else listOf(currentValue)
        if (isUnavailable || points.size < 2) {
            // Draw single point or baseline with label
            primaryTextPaint.textSize = 16f * density
            canvas.drawText(formattedValue, w / 2f, h / 2f, primaryTextPaint)
            if (secondaryLabel.isNotEmpty()) {
                subTextPaint.textSize = 10.5f * density
                canvas.drawText(secondaryLabel, w / 2f, h / 2f + 14f * density, subTextPaint)
            }
            return
        }

        // Dynamic local maximum for rate lines
        val effectiveMax = if (isRate) {
            val maxSample = points.maxOrNull() ?: 0f
            maxSample.coerceAtLeast(1024f) * 1.15f
        } else {
            100f
        }

        linePath.reset()
        fillPath.reset()

        val stepX = usableW / (points.size - 1).toFloat()
        val coords = mutableListOf<PointF>()
        for (i in points.indices) {
            val raw = points[i]
            val safe = if (raw.isFinite() && !raw.isNaN() && raw >= 0f) raw else 0f
            val norm = (safe / effectiveMax).coerceIn(0f, 1f)
            val px = padH + (i * stepX)
            val py = bottomY - (norm * usableH)
            if (px.isFinite() && !px.isNaN() && py.isFinite() && !py.isNaN()) {
                coords.add(PointF(px, py))
            }
        }

        if (coords.size < 2) return

        linePath.moveTo(coords[0].x, coords[0].y)
        fillPath.moveTo(coords[0].x, bottomY)
        fillPath.lineTo(coords[0].x, coords[0].y)

        for (i in 1 until coords.size) {
            val prev = coords[i - 1]
            val curr = coords[i]
            val cpx = (prev.x + curr.x) / 2f
            if (cpx.isFinite() && !cpx.isNaN() && prev.y.isFinite() && !prev.y.isNaN() && curr.y.isFinite() && !curr.y.isNaN() && curr.x.isFinite() && !curr.x.isNaN()) {
                linePath.cubicTo(cpx, prev.y, cpx, curr.y, curr.x, curr.y)
                fillPath.cubicTo(cpx, prev.y, cpx, curr.y, curr.x, curr.y)
            } else {
                linePath.lineTo(curr.x, curr.y)
                fillPath.lineTo(curr.x, curr.y)
            }
        }

        fillPath.lineTo(coords.last().x, bottomY)
        fillPath.close()

        // Gradient shader for fill
        val activeColor = linePaint.color
        fillPaint.shader = LinearGradient(
            0f, padV, 0f, bottomY,
            adjustAlpha(activeColor, 0.35f),
            adjustAlpha(activeColor, 0.02f),
            Shader.TileMode.CLAMP
        )

        try {
            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(linePath, linePaint)
        } catch (e: Throwable) {
            // Skia fallback safety
        }

        // Draw last point indicator with glowing halo
        val last = coords.last()
        if (last.x.isFinite() && last.y.isFinite()) {
            canvas.drawCircle(last.x, last.y, 6.5f * density, pointHaloPaint)
            canvas.drawCircle(last.x, last.y, 3.5f * density, linePaint)
        }

        // Top right peak indicator
        labelTextPaint.textSize = 9.5f * density
        labelTextPaint.textAlign = Paint.Align.RIGHT
        val peakLabel = if (isRate) Formatters.formatSpeed(effectiveMax.toLong()) else "100%"
        canvas.drawText("Max: $peakLabel", w - padH, padV + 8f * density, labelTextPaint)
        labelTextPaint.textAlign = Paint.Align.CENTER
    }

    // ==========================================
    // 4. Bar Graph (Comparison / Interval View)
    // ==========================================
    private fun drawBarGraph(canvas: Canvas, w: Float, h: Float) {
        val padH = 12f * density
        val padV = 8f * density
        val labelAreaH = 16f * density
        val bottomY = h - padV - labelAreaH
        val usableW = (w - (padH * 2f)).coerceAtLeast(1f)
        val usableH = (bottomY - padV).coerceAtLeast(1f)

        val isUnavailable = formattedValue.equals("Unavailable", ignoreCase = true)
        if (isUnavailable) {
            primaryTextPaint.textSize = 16f * density
            canvas.drawText(formattedValue, w / 2f, h / 2f, primaryTextPaint)
            if (secondaryLabel.isNotEmpty()) {
                subTextPaint.textSize = 10.5f * density
                canvas.drawText(secondaryLabel, w / 2f, h / 2f + 14f * density, subTextPaint)
            }
            return
        }

        val items: List<BarItem> = if (barItems.isNotEmpty()) {
            barItems
        } else if (historyPoints.size >= 4) {
            // Partition history points into 6 equal chunks for interval comparison
            val chunkCount = 6.coerceAtMost(historyPoints.size)
            val chunkSize = (historyPoints.size / chunkCount).coerceAtLeast(1)
            (0 until chunkCount).map { idx ->
                val slice = historyPoints.drop(idx * chunkSize).take(chunkSize)
                val avg = if (slice.isNotEmpty()) slice.average().toFloat() else 0f
                BarItem("T${idx + 1}", avg, maxValue)
            }
        } else {
            listOf(BarItem("Current", currentValue, maxValue))
        }

        if (items.isEmpty()) return

        val count = items.size
        val totalSlotW = usableW / count
        val barW = (totalSlotW * 0.58f).coerceIn(6f * density, 24f * density)
        val cornerRadius = 3f * density

        for (i in 0 until count) {
            val item = items[i]
            val slotCenter = padH + (i * totalSlotW) + (totalSlotW / 2f)
            val barLeft = slotCenter - (barW / 2f)
            val barRight = slotCenter + (barW / 2f)

            // Bar background slot
            barRect.set(barLeft, padV, barRight, bottomY)
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barTrackPaint)

            // Active bar
            val safeVal = if (item.value.isFinite() && item.value >= 0f) item.value else 0f
            val safeMax = if (item.maxValue.isFinite() && item.maxValue > 0f) item.maxValue else 100f
            val norm = (safeVal / safeMax).coerceIn(0f, 1f)
            val barTop = bottomY - (norm * usableH)

            if (norm > 0.02f) {
                barRect.set(barLeft, barTop, barRight, bottomY)
                canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barFillPaint)
            }

            // Small label below bar
            labelTextPaint.textSize = 9.5f * density
            canvas.drawText(item.label, slotCenter, h - padV - 2f, labelTextPaint)
        }
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = Math.round(Color.alpha(color) * factor).coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
