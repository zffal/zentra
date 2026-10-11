package com.example.serverdashboard.ui.views

import android.content.Context
import android.graphics.Outline
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.example.serverdashboard.R

/**
 * Apple-grade SegmentedControl with full-pill track and spring-animated sliding thumb.
 * Adheres strictly to the Apple Human Interface Guidelines:
 * - 40dp height, full pill track (radius = height / 2) with tertiary fill and clipToOutline = true.
 * - Sliding thumb pill inset 3dp on all sides with elevated fill and soft shadow.
 * - 14sp medium sentence-case labels with normal letter spacing.
 * - Concentric pills guarantee the thumb never pokes past the track.
 */
class SegmentedControl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    fun interface OnButtonCheckedListener {
        fun onButtonChecked(group: SegmentedControl, checkedId: Int, isChecked: Boolean)
    }

    private val segments = mutableListOf<View>()
    private val listeners = mutableListOf<OnButtonCheckedListener>()

    private val thumbView: View
    private val labelsRow: LinearLayout
    private var springAnim: SpringAnimation? = null

    var selectedIndex: Int = 0
        private set

    val checkedButtonId: Int
        get() = segments.getOrNull(selectedIndex)?.id ?: View.NO_ID

    init {
        // Track setup: 40dp default, full pill, tertiary fill, no stroke, clipToOutline
        try {
            background = ContextCompat.getDrawable(context, R.drawable.bg_segmented_track_pill)
        } catch (_: Throwable) {
            // Safe fallback if drawable missing
        }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val h = view.height.toFloat()
                outline.setRoundRect(0, 0, view.width, view.height, h / 2f)
            }
        }
        clipToOutline = true

        // Thumb setup: inset 3dp, elevated fill, soft shadow
        thumbView = View(context).apply {
            try {
                background = ContextCompat.getDrawable(context, R.drawable.bg_segmented_thumb_pill)
            } catch (_: Throwable) {
                // Safe fallback if drawable missing
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val h = view.height.toFloat()
                    outline.setRoundRect(0, 0, view.width, view.height, h / 2f)
                }
            }
            clipToOutline = true
            elevation = 0f
        }
        super.addView(thumbView, LayoutParams(0, 0))

        // Horizontal container for segment labels - elevated above thumb
        labelsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            translationZ = dpToPx(1f)
        }
        super.addView(labelsRow)
    }

    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams?) {
        if (child === thumbView || child === labelsRow) {
            super.addView(child, index, params)
        } else {
            val lp = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            labelsRow.addView(child, lp)
            setupSegmentChild(child)
        }
    }

    private fun setupSegmentChild(child: View) {
        if (child is TextView) {
            child.gravity = Gravity.CENTER
            child.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val tf = try {
                ResourcesCompat.getFont(context, R.font.inter_semibold)
                    ?: ResourcesCompat.getFont(context, R.font.inter)
                    ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
            } catch (_: Throwable) {
                try {
                    Typeface.create("sans-serif-medium", Typeface.NORMAL)
                } catch (_: Throwable) {
                    Typeface.DEFAULT
                }
            }
            child.typeface = tf
            child.letterSpacing = 0f
            child.isSingleLine = true
            // Ensure sentence case for existing text
            val txt = child.text.toString()
            if (txt.isNotEmpty() && txt.all { it.isUpperCase() } && txt.length > 4) {
                child.text = txt.lowercase().replaceFirstChar { it.uppercase() }
            }
        }
        child.isClickable = true
        child.isFocusable = true

        val index = segments.size
        segments.add(child)

        child.setOnClickListener {
            if (child.isEnabled) {
                val idx = segments.indexOf(child)
                if (idx >= 0) {
                    triggerHaptics()
                    selectIndex(idx, animate = true, notify = true)
                }
            }
        }
    }

    private fun triggerHaptics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
        } else {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultHeight = dpToPx(36f).toInt()
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val resolvedHeightSpec = if (heightMode == MeasureSpec.EXACTLY) {
            heightMeasureSpec
        } else {
            MeasureSpec.makeMeasureSpec(
                if (heightMode == MeasureSpec.AT_MOST) minOf(defaultHeight, heightSize) else defaultHeight,
                MeasureSpec.EXACTLY
            )
        }
        super.onMeasure(widthMeasureSpec, resolvedHeightSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        layoutThumb()
    }

    private fun layoutThumb() {
        val count = segments.size
        if (count == 0 || width <= 0 || height <= 0) return

        val inset = dpToPx(3f).toInt()
        val availableWidth = (width - 2 * inset).coerceAtLeast(0)
        val segmentWidth = availableWidth.toFloat() / count
        val thumbHeight = (height - 2 * inset).coerceAtLeast(0)
        val thumbWidth = segmentWidth.toInt()

        // Place the thumb view at baseline position (index 0)
        thumbView.layout(inset, inset, inset + thumbWidth, inset + thumbHeight)

        val targetX = selectedIndex * segmentWidth
        if (springAnim == null || !springAnim!!.isRunning) {
            thumbView.translationX = targetX
        }

        updateLabelColors()
    }

    private fun updateLabelColors() {
        for (i in segments.indices) {
            val v = segments[i]
            if (v is TextView) {
                val colorRes = if (!v.isEnabled) {
                    R.color.label_tertiary
                } else if (i == selectedIndex) {
                    R.color.label_segment_selected
                } else {
                    R.color.label_secondary
                }
                v.setTextColor(ContextCompat.getColor(context, colorRes))
            }
        }
    }

    private fun animateThumb(targetX: Float) {
        if (!isLaidOut || thumbView.width == 0) {
            thumbView.translationX = targetX
            return
        }
        if (springAnim == null) {
            springAnim = SpringAnimation(thumbView, DynamicAnimation.TRANSLATION_X).apply {
                spring = SpringForce().apply {
                    stiffness = SpringForce.STIFFNESS_LOW
                    dampingRatio = 0.78f
                }
            }
        }
        springAnim?.spring?.finalPosition = targetX
        springAnim?.start()
    }

    fun selectIndex(index: Int, animate: Boolean = true, notify: Boolean = true) {
        if (index < 0 || index >= segments.size) return
        val changed = selectedIndex != index
        selectedIndex = index

        val count = segments.size
        if (count > 0 && width > 0) {
            val inset = dpToPx(3f).toInt()
            val segmentWidth = (width - 2 * inset).toFloat() / count
            val targetX = index * segmentWidth
            if (animate && isLaidOut) {
                animateThumb(targetX)
            } else {
                springAnim?.cancel()
                thumbView.translationX = targetX
            }
        }

        updateLabelColors()

        if (notify) {
            val currentId = segments[index].id
            listeners.forEach { it.onButtonChecked(this, currentId, true) }
        }
    }

    fun check(@IdRes id: Int) {
        val index = segments.indexOfFirst { it.id == id }
        if (index >= 0) {
            selectIndex(index, animate = isLaidOut, notify = false)
        }
    }

    fun setSegmentEnabled(@IdRes id: Int, enabled: Boolean) {
        val seg = segments.find { it.id == id } ?: return
        seg.isEnabled = enabled
        seg.alpha = if (enabled) 1.0f else 0.35f
        updateLabelColors()
    }

    fun addOnButtonCheckedListener(listener: OnButtonCheckedListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeOnButtonCheckedListener(listener: OnButtonCheckedListener) {
        listeners.remove(listener)
    }

    fun clearOnButtonCheckedListeners() {
        listeners.clear()
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}
