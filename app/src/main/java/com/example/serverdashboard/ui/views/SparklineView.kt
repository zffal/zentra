package com.example.serverdashboard.ui.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.serverdashboard.R

class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
    }

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val path = Path()
    private val fillPath = Path()
    private val points = mutableListOf<Float>()
    private var maxCapacity = 100f
    private var lineColor = 0

    var showFill: Boolean = true
    var showBaseline: Boolean = true
    var showEndDot: Boolean = true

    fun setStrokeWidth(dp: Float) {
        linePaint.strokeWidth = dp * resources.displayMetrics.density
        invalidate()
    }

    init {
        refreshThemeColors()
    }

    fun refreshThemeColors() {
        val accent = ContextCompat.getColor(context, R.color.accent)
        val grid = ContextCompat.getColor(context, R.color.separator)
        lineColor = accent
        linePaint.color = accent
        gridPaint.color = grid
        haloPaint.color = adjustAlpha(accent, 0.28f)
        dotPaint.color = accent
        invalidate()
    }

    fun setLineColor(color: Int) {
        lineColor = color
        linePaint.color = color
        haloPaint.color = adjustAlpha(color, 0.28f)
        dotPaint.color = color
        invalidate()
    }

    fun setMaxCapacity(max: Float) {
        maxCapacity = if (max.isFinite() && max > 0f) max else 100f
        invalidate()
    }

    fun setData(data: List<Float>) {
        points.clear()
        for (v in data) {
            val safe = if (v.isFinite() && !v.isNaN()) v else 0f
            points.add(safe)
        }
        invalidate()
    }

    fun addPoint(value: Float, maxPoints: Int = 30) {
        val safe = if (value.isFinite() && !value.isNaN()) value else 0f
        points.add(safe)
        while (points.size > maxPoints) {
            points.removeAt(0)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Subtle bottom baseline
        if (showBaseline) {
            canvas.drawLine(0f, h - 1f, w, h - 1f, gridPaint)
        }

        if (points.size < 2) return

        path.reset()
        fillPath.reset()

        val stepX = (w / (points.size - 1).toFloat()).coerceAtLeast(0.001f)
        val padding = if (showBaseline) 4f * resources.displayMetrics.density else 2f * resources.displayMetrics.density
        val usableH = (h - (padding * 2)).coerceAtLeast(1f)
        val safeCapacity = if (maxCapacity.isFinite() && maxCapacity > 0f) maxCapacity else 100f

        val coords = mutableListOf<PointF>()
        for (i in points.indices) {
            val raw = points[i]
            val safeVal = if (raw.isFinite() && !raw.isNaN()) raw else 0f
            val normalized = (safeVal / safeCapacity).coerceIn(0f, 1f)
            val px = (i * stepX).coerceIn(0f, w)
            val py = ((h - padding) - (normalized * usableH)).coerceIn(0f, h)
            coords.add(PointF(px, py))
        }

        val validCoords = coords.filter { it.x.isFinite() && it.y.isFinite() }
        if (validCoords.size < 2) return

        path.moveTo(validCoords[0].x, validCoords[0].y)
        if (showFill) {
            fillPath.moveTo(validCoords[0].x, h)
            fillPath.lineTo(validCoords[0].x, validCoords[0].y)
        }

        for (i in 1 until validCoords.size) {
            val prev = validCoords[i - 1]
            val curr = validCoords[i]
            val cpx = (prev.x + curr.x) / 2f
            if (cpx.isFinite() && prev.y.isFinite() && curr.y.isFinite() && curr.x.isFinite()) {
                path.cubicTo(cpx, prev.y, cpx, curr.y, curr.x, curr.y)
                if (showFill) {
                    fillPath.cubicTo(cpx, prev.y, cpx, curr.y, curr.x, curr.y)
                }
            } else {
                path.lineTo(curr.x, curr.y)
                if (showFill) {
                    fillPath.lineTo(curr.x, curr.y)
                }
            }
        }

        if (showFill) {
            fillPath.lineTo(validCoords.last().x, h)
            fillPath.close()
        }

        try {
            if (showFill) {
                // Gradient shader for fill
                fillPaint.shader = LinearGradient(
                    0f, 0f, 0f, h,
                    adjustAlpha(lineColor, 0.35f),
                    adjustAlpha(lineColor, 0.02f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawPath(fillPath, fillPaint)
            }

            canvas.drawPath(path, linePaint)

            // Glowing point indicator at latest coordinate
            if (showEndDot) {
                val last = validCoords.last()
                if (last.x.isFinite() && last.y.isFinite()) {
                    val d = resources.displayMetrics.density
                    canvas.drawCircle(last.x, last.y, 5.5f * d, haloPaint)
                    canvas.drawCircle(last.x, last.y, 2.75f * d, dotPaint)
                }
            }
        } catch (e: Throwable) {
            // Guard hardware accelerated canvas against Skia floating point drawing anomalies
        }
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = Math.round(Color.alpha(color) * factor)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
