package com.example.serverdashboard.ui.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.serverdashboard.R
import com.example.serverdashboard.data.model.DailyUsageItem
import com.example.serverdashboard.ui.util.Formatters
import java.util.Locale

/**
 * Custom Canvas stacked bar chart displaying daily network usage (Rx download + Tx upload).
 * Adheres to Midnight + Emerald theme with strictly opaque surfaces.
 * Features:
 * - Stacked bars: Download (Emerald) at bottom, Upload (Cyan/Teal) on top
 * - Peak day visual highlight indicator
 * - Interactive tap tooltip with date, total, and down/up breakdown
 * - isFinite guards on all coordinates and ratios
 * - Handles empty and single-day datasets gracefully
 * - Measures width to support smooth horizontal scrolling when needed
 */
class DailyUsageChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    // Paints
    private val rxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val txPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10f * density
    }

    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val tooltipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    // Colors
    private var rxColor = ContextCompat.getColor(context, R.color.accent_tech)
    private var txColor = ContextCompat.getColor(context, R.color.accent)
    private var trackColor = ContextCompat.getColor(context, R.color.surface_3)
    private var gridColor = ContextCompat.getColor(context, R.color.separator)
    private var textColor = ContextCompat.getColor(context, R.color.label_tertiary)
    private var peakColor = ContextCompat.getColor(context, R.color.warning)
    private var tooltipBgColor = Color.parseColor("#16201D") // Opaque dark surface
    private var tooltipBorderColor = Color.parseColor("#2C3E38") // Opaque border

    // Data
    private var items: List<DailyUsageItem> = emptyList()
    private var peakDateStr: String? = null
    private var serverTimezone: String? = null
    private var selectedIndex: Int? = null

    // Touch tracking
    private var downX = 0f
    private var downY = 0f

    // Layout metrics
    private val minSlotWidth = 36f * density
    private val padH = 12f * density
    private val padTop = 32f * density // Space for peak badges & tooltip
    private val padBottom = 26f * density // Space for date labels

    init {
        refreshColors()
    }

    fun refreshColors() {
        try {
            rxColor = ContextCompat.getColor(context, R.color.accent_tech)
            txColor = ContextCompat.getColor(context, R.color.accent)
            trackColor = ContextCompat.getColor(context, R.color.surface_3)
            gridColor = ContextCompat.getColor(context, R.color.separator)
            textColor = ContextCompat.getColor(context, R.color.label_tertiary)
            peakColor = ContextCompat.getColor(context, R.color.warning)
        } catch (_: Throwable) {}

        rxPaint.color = rxColor
        txPaint.color = txColor
        trackPaint.color = trackColor
        gridLinePaint.color = gridColor
        textPaint.color = textColor
        peakPaint.color = peakColor
        tooltipBgPaint.color = tooltipBgColor
        tooltipBorderPaint.color = tooltipBorderColor
        invalidate()
    }

    fun setData(
        dailyItems: List<DailyUsageItem>,
        peakDate: String? = null,
        timezone: String? = null
    ) {
        this.items = dailyItems
        this.peakDateStr = peakDate ?: dailyItems.maxByOrNull { it.total }?.date
        this.serverTimezone = timezone
        this.selectedIndex = null
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val count = items.size
        val desiredW = if (count > 0) {
            val totalSlots = count * minSlotWidth + (padH * 2f)
            totalSlots.toInt()
        } else {
            (200f * density).toInt()
        }

        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)

        val resolvedW = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> desiredW.coerceAtLeast(widthSize)
            else -> desiredW
        }

        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val resolvedH = if (heightSize > 0) heightSize else (200f * density).toInt()

        setMeasuredDimension(resolvedW, resolvedH)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val count = items.size
        if (count == 0) {
            drawEmptyState(canvas, w, h)
            return
        }

        val maxBytes = (items.maxOfOrNull { it.total } ?: 0L).coerceAtLeast(1024L).toDouble()
        val usableW = (w - (padH * 2f)).coerceAtLeast(1f)
        val usableH = (h - padTop - padBottom).coerceAtLeast(1f)
        val bottomY = h - padBottom

        // Draw horizontal grid lines (0%, 50%, 100%)
        val midY = bottomY - (usableH * 0.5f)
        val topY = bottomY - usableH

        if (bottomY.isFinite() && midY.isFinite() && topY.isFinite()) {
            canvas.drawLine(padH, bottomY, w - padH, bottomY, gridLinePaint)
            canvas.drawLine(padH, midY, w - padH, midY, gridLinePaint)
            canvas.drawLine(padH, topY, w - padH, topY, gridLinePaint)
        }

        val slotW = usableW / count
        val barW = (slotW * 0.58f).coerceIn(6f * density, 24f * density)
        val cornerRadius = 3f * density

        for (i in 0 until count) {
            val item = items[i]
            val slotCenter = padH + (i * slotW) + (slotW / 2f)
            val barLeft = slotCenter - (barW / 2f)
            val barRight = slotCenter + (barW / 2f)

            if (!barLeft.isFinite() || !barRight.isFinite()) continue

            // Background slot track
            val trackRect = RectF(barLeft, padTop, barRight, bottomY)
            canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, trackPaint)

            // Ratios
            val rxBytes = item.rx.toDouble()
            val txBytes = item.tx.toDouble()
            val totalBytes = (rxBytes + txBytes).coerceAtLeast(0.0)

            val rxNorm = if (maxBytes > 0) (rxBytes / maxBytes).coerceIn(0.0, 1.0).toFloat() else 0f
            val txNorm = if (maxBytes > 0) (txBytes / maxBytes).coerceIn(0.0, 1.0).toFloat() else 0f
            val totalNorm = (rxNorm + txNorm).coerceIn(0f, 1f)

            val totalH = (totalNorm * usableH)
            val rxH = (rxNorm * usableH)
            val txH = (txNorm * usableH)

            val totalTop = bottomY - totalH
            val rxTop = bottomY - rxH

            // 1. Draw Rx (Download) bar at the bottom
            if (rxH > 1f && rxTop.isFinite()) {
                val rxRect = RectF(barLeft, rxTop, barRight, bottomY)
                canvas.drawRoundRect(rxRect, cornerRadius, cornerRadius, rxPaint)
            }

            // 2. Draw Tx (Upload) bar stacked on top
            if (txH > 1f && totalTop.isFinite() && rxTop.isFinite()) {
                val txRect = RectF(barLeft, totalTop, barRight, rxTop)
                canvas.drawRoundRect(txRect, cornerRadius, cornerRadius, txPaint)
            }

            // 3. Peak Day Highlight
            val isPeak = (peakDateStr != null && item.date == peakDateStr) && item.total > 0L
            if (isPeak) {
                val peakIndicatorY = (totalTop - 7f * density).coerceAtLeast(padTop - 18f * density)
                if (peakIndicatorY.isFinite()) {
                    // Draw small amber peak badge
                    val badgeW = 22f * density
                    val badgeH = 12f * density
                    val badgeRect = RectF(slotCenter - badgeW / 2f, peakIndicatorY - badgeH / 2f, slotCenter + badgeW / 2f, peakIndicatorY + badgeH / 2f)
                    canvas.drawRoundRect(badgeRect, 3f * density, 3f * density, peakPaint)

                    val pTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 8f * density
                        textAlign = Paint.Align.CENTER
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    canvas.drawText("TOP", slotCenter, peakIndicatorY + (3f * density), pTextPaint)
                }
            }

            // 4. Date Label below bar
            val shouldShowLabel = count <= 14 || (i % ((count / 7).coerceAtLeast(1)) == 0) || i == count - 1 || isPeak
            if (shouldShowLabel) {
                val labelText = Formatters.formatUsageDateShort(item.date, serverTimezone)
                textPaint.color = if (isPeak) peakColor else textColor
                textPaint.typeface = if (isPeak) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                canvas.drawText(labelText, slotCenter, h - 8f * density, textPaint)
            }
        }

        // 5. Draw interactive Tooltip if bar selected
        selectedIndex?.let { selIdx ->
            if (selIdx in 0 until count) {
                drawTooltip(canvas, selIdx, w, h, usableW, usableH, count, slotW, maxBytes)
            }
        }
    }

    private fun drawEmptyState(canvas: Canvas, w: Float, h: Float) {
        val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 13f * density
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("No network usage recorded for this period", w / 2f, h / 2f, emptyPaint)
    }

    private fun drawTooltip(
        canvas: Canvas,
        index: Int,
        w: Float,
        h: Float,
        usableW: Float,
        usableH: Float,
        count: Int,
        slotW: Float,
        maxBytes: Double
    ) {
        val item = items[index]
        val slotCenter = padH + (index * slotW) + (slotW / 2f)

        val totalNorm = if (maxBytes > 0) (item.total.toDouble() / maxBytes).coerceIn(0.0, 1.0).toFloat() else 0f
        val bottomY = h - padBottom
        val barTop = bottomY - (totalNorm * usableH)

        val dateFormatted = Formatters.formatUsageDate(item.date, serverTimezone)
        val totalFormatted = "Total: ${Formatters.formatBytes(item.total)}"
        val breakdown = "↓ ${Formatters.formatBytes(item.rx)}  •  ↑ ${Formatters.formatBytes(item.tx)}"

        val boxW = 160f * density
        val boxH = 56f * density
        var boxLeft = slotCenter - (boxW / 2f)
        var boxRight = boxLeft + boxW

        // Clamp inside view bounds
        if (boxLeft < 6f * density) {
            boxLeft = 6f * density
            boxRight = boxLeft + boxW
        } else if (boxRight > w - 6f * density) {
            boxRight = w - 6f * density
            boxLeft = boxRight - boxW
        }

        val boxBottom = (barTop - 12f * density).coerceAtLeast(boxH + 4f * density)
        val boxTop = boxBottom - boxH

        val tooltipRect = RectF(boxLeft, boxTop, boxRight, boxBottom)
        canvas.drawRoundRect(tooltipRect, 6f * density, 6f * density, tooltipBgPaint)
        canvas.drawRoundRect(tooltipRect, 6f * density, 6f * density, tooltipBorderPaint)

        // Draw text inside tooltip
        val textCenterX = (boxLeft + boxRight) / 2f

        // Line 1: Date
        tooltipTextPaint.apply {
            color = ContextCompat.getColor(context, R.color.label_secondary)
            textSize = 10.5f * density
            typeface = Typeface.DEFAULT
        }
        canvas.drawText(dateFormatted, textCenterX, boxTop + 16f * density, tooltipTextPaint)

        // Line 2: Total in bold
        tooltipTextPaint.apply {
            color = Color.WHITE
            textSize = 12.5f * density
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(totalFormatted, textCenterX, boxTop + 32f * density, tooltipTextPaint)

        // Line 3: Down/Up breakdown
        tooltipTextPaint.apply {
            color = rxColor
            textSize = 10f * density
            typeface = Typeface.DEFAULT
        }
        canvas.drawText(breakdown, textCenterX, boxTop + 47f * density, tooltipTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = Math.abs(event.x - downX)
                val dy = Math.abs(event.y - downY)
                if (dx < 10f * density && dy < 10f * density) {
                    handleTap(event.x, event.y)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTap(touchX: Float, touchY: Float) {
        val count = items.size
        if (count == 0) return

        val usableW = (width.toFloat() - (padH * 2f)).coerceAtLeast(1f)
        val slotW = usableW / count

        val tappedIndex = ((touchX - padH) / slotW).toInt()
        if (tappedIndex in 0 until count) {
            selectedIndex = if (selectedIndex == tappedIndex) null else tappedIndex
            invalidate()
        } else {
            if (selectedIndex != null) {
                selectedIndex = null
                invalidate()
            }
        }
    }
}
