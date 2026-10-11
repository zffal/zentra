package com.example.serverdashboard.ui.views

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.example.serverdashboard.R
import com.example.serverdashboard.data.api.ConnectionState
import com.example.serverdashboard.data.api.StreamState
import com.example.serverdashboard.databinding.LayoutStatusIslandBinding

/**
 * StatusIslandView: Modern floating "Status Island" component replacing the top bar.
 *
 * Features:
 * - Collapsed State: 48dp opaque pill with fixed 28dp HealthRingView slot at far left, 12dp padding,
 *   centered two-line server name + status word, and 8dp alert dot.
 * - Spring Animation: springs open into an expanded panel with full telemetry and action buttons.
 * - Haptics on expand and collapse.
 * - Respects system "remove animations" settings.
 * - Interactive states (Online, Reconnecting, Data outdated, Offline, Alert badge).
 * - Scroll awareness: shrinks to 0.92x during scroll down.
 */
class StatusIslandView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding: LayoutStatusIslandBinding =
        LayoutStatusIslandBinding.inflate(LayoutInflater.from(context), this, true)

    var isExpanded: Boolean = false
        private set

    // Callbacks
    var onWakeClickListener: (() -> Unit)? = null
    var onRefreshClickListener: (() -> Unit)? = null
    var onAlertsClickListener: (() -> Unit)? = null
    var onSettingsClickListener: (() -> Unit)? = null
    var onExpandedStateChangeListener: ((Boolean) -> Unit)? = null

    private var currentConnectionState: ConnectionState? = null
    private var currentStreamState: StreamState? = null
    private var isMetricsStale: Boolean = false
    private var currentUptime: String = ""
    private var currentFreshness: String = ""

    private var wakePulseAnim: Animation? = null
    private var refreshRotateAnim: Animation? = null

    private var expandSpringAnim: SpringAnimation? = null

    init {
        clipChildren = false
        clipToPadding = false

        // Wire tap on collapsed pill to expand
        binding.pillTouchTarget.setOnClickListener {
            toggleExpanded()
        }

        binding.pillTouchTarget.setOnLongClickListener {
            toggleExpanded()
            true
        }

        // Tap scrim to collapse
        binding.viewIslandScrim.setOnClickListener {
            if (isExpanded) {
                collapse()
            }
        }

        // Action buttons wiring
        binding.btnIslandWake.setOnClickListener {
            triggerHaptic(HapticFeedbackConstants.CONTEXT_CLICK)
            onWakeClickListener?.invoke()
            collapse()
        }

        binding.btnIslandRefresh.setOnClickListener {
            triggerHaptic(HapticFeedbackConstants.CONTEXT_CLICK)
            onRefreshClickListener?.invoke()
            collapse()
        }

        binding.btnIslandAlerts.setOnClickListener {
            triggerHaptic(HapticFeedbackConstants.CONTEXT_CLICK)
            onAlertsClickListener?.invoke()
            collapse()
        }

        binding.btnIslandSettings.setOnClickListener {
            triggerHaptic(HapticFeedbackConstants.CONTEXT_CLICK)
            onSettingsClickListener?.invoke()
            collapse()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxAllowed = (resources.displayMetrics.widthPixels * 0.75f).toInt()
        val minAllowed = resources.getDimensionPixelSize(R.dimen.status_island_min_width)
        binding.islandPill.minimumWidth = minAllowed

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        if (binding.islandPill.measuredWidth > maxAllowed) {
            val childWidthSpec = MeasureSpec.makeMeasureSpec(maxAllowed, MeasureSpec.EXACTLY)
            val childHeightSpec = MeasureSpec.makeMeasureSpec(
                resources.getDimensionPixelSize(R.dimen.status_island_collapsed_height),
                MeasureSpec.EXACTLY
            )
            binding.islandPill.measure(childWidthSpec, childHeightSpec)
            binding.pillTouchTarget.measure(
                childWidthSpec,
                MeasureSpec.makeMeasureSpec(binding.pillTouchTarget.measuredHeight, MeasureSpec.EXACTLY)
            )
            binding.layoutCollapsedWrapper.measure(
                childWidthSpec,
                MeasureSpec.makeMeasureSpec(binding.layoutCollapsedWrapper.measuredHeight, MeasureSpec.EXACTLY)
            )
        }
    }

    fun setServerInfo(hostName: String, cleanUrl: String, uptime: String) {
        val displayHost = hostName.ifEmpty { "Server" }
        binding.tvIslandHost.text = displayHost
        binding.tvIslandExpandedHost.text = displayHost
        binding.tvIslandExpandedIp.text = cleanUrl.ifEmpty { "192.168.1.42:8765" }
        currentUptime = uptime
        updateExpandedUptimeText()
    }

    fun setCpuPercent(percent: Float) {
        binding.healthRing.setCpuPercent(percent)
    }

    fun setIsStale(stale: Boolean) {
        if (isMetricsStale != stale) {
            isMetricsStale = stale
            updateStatusDisplay()
            updateExpandedUptimeText()
        }
    }

    fun setExpandedFreshness(freshnessText: String) {
        currentFreshness = freshnessText
        updateExpandedUptimeText()
    }

    private fun updateExpandedUptimeText() {
        val uptimePart = if (currentUptime.isNotEmpty()) "Uptime: $currentUptime" else ""
        val freshnessPart = if (currentFreshness.isNotEmpty()) {
            currentFreshness
        } else {
            "Telemetry live"
        }
        binding.tvIslandExpandedUptime.text = if (uptimePart.isNotEmpty()) {
            "$uptimePart • $freshnessPart"
        } else {
            freshnessPart
        }
    }

    fun setConnectionState(conn: ConnectionState?, stream: StreamState?) {
        currentConnectionState = conn
        currentStreamState = stream
        updateStatusDisplay()
    }

    private fun updateStatusDisplay() {
        val successColor = ContextCompat.getColor(context, R.color.status_success)
        val warningColor = ContextCompat.getColor(context, R.color.status_warning)
        val criticalColor = ContextCompat.getColor(context, R.color.status_critical)

        when (currentConnectionState) {
            ConnectionState.CONNECTED -> {
                updateWakePulse(false)
                when {
                    currentStreamState == StreamState.RECONNECTING -> {
                        binding.healthRing.setHealthStatus(HealthRingView.HealthStatus.STALE_RECONNECTING)
                        binding.tvIslandStatusWord.text = "Reconnecting"
                        binding.tvIslandStatusWord.setTextColor(warningColor)
                        binding.tvIslandExpandedStatus.text = "Reconnecting"
                        binding.tvIslandExpandedStatus.setTextColor(warningColor)
                    }
                    isMetricsStale -> {
                        binding.healthRing.setHealthStatus(HealthRingView.HealthStatus.STALE_RECONNECTING)
                        binding.tvIslandStatusWord.text = "Outdated"
                        binding.tvIslandStatusWord.setTextColor(warningColor)
                        binding.tvIslandExpandedStatus.text = "Outdated"
                        binding.tvIslandExpandedStatus.setTextColor(warningColor)
                    }
                    else -> {
                        binding.healthRing.setHealthStatus(HealthRingView.HealthStatus.ONLINE)
                        binding.tvIslandStatusWord.text = "Online"
                        binding.tvIslandStatusWord.setTextColor(successColor)
                        binding.tvIslandExpandedStatus.text = "Online"
                        binding.tvIslandExpandedStatus.setTextColor(successColor)
                    }
                }
            }
            ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> {
                updateWakePulse(false)
                binding.healthRing.setHealthStatus(HealthRingView.HealthStatus.STALE_RECONNECTING)
                binding.tvIslandStatusWord.text = "Reconnecting"
                binding.tvIslandStatusWord.setTextColor(warningColor)
                binding.tvIslandExpandedStatus.text = "Reconnecting"
                binding.tvIslandExpandedStatus.setTextColor(warningColor)
            }
            ConnectionState.AUTH_REQUIRED -> {
                updateWakePulse(false)
                binding.healthRing.setHealthStatus(HealthRingView.HealthStatus.OFFLINE)
                binding.tvIslandStatusWord.text = "Offline"
                binding.tvIslandStatusWord.setTextColor(criticalColor)
                binding.tvIslandExpandedStatus.text = "Auth Expired"
                binding.tvIslandExpandedStatus.setTextColor(criticalColor)
            }
            ConnectionState.SERVER_UNAVAILABLE, ConnectionState.DISCONNECTED, null -> {
                updateWakePulse(true)
                binding.healthRing.setHealthStatus(HealthRingView.HealthStatus.OFFLINE)
                binding.tvIslandStatusWord.text = "Offline"
                binding.tvIslandStatusWord.setTextColor(criticalColor)
                binding.tvIslandExpandedStatus.text = "Offline"
                binding.tvIslandExpandedStatus.setTextColor(criticalColor)
            }
        }
    }

    fun setActiveAlertsCount(count: Int) {
        if (count > 0) {
            binding.viewIslandAlertDot.visibility = View.VISIBLE
            binding.tvIslandAlertBadge.visibility = View.VISIBLE
            binding.tvIslandAlertBadge.text = if (count > 9) "9+" else count.toString()
        } else {
            binding.viewIslandAlertDot.visibility = View.GONE
            binding.tvIslandAlertBadge.visibility = View.GONE
        }
    }

    fun setIsRefreshing(refreshing: Boolean) {
        if (refreshing) {
            if (refreshRotateAnim == null) {
                refreshRotateAnim = RotateAnimation(
                    0f, 360f,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f
                ).apply {
                    duration = 800
                    repeatCount = Animation.INFINITE
                }
                binding.imgIslandRefresh.startAnimation(refreshRotateAnim)
            }
        } else {
            refreshRotateAnim?.cancel()
            refreshRotateAnim = null
            binding.imgIslandRefresh.clearAnimation()
        }
    }

    private fun updateWakePulse(isUnreachable: Boolean) {
        if (isUnreachable) {
            val accentColor = ContextCompat.getColor(context, R.color.accent)
            binding.imgIslandWake.setColorFilter(accentColor)
            binding.tvIslandWakeLabel.setTextColor(accentColor)
            if (wakePulseAnim == null && !areAnimationsDisabled()) {
                wakePulseAnim = AlphaAnimation(0.4f, 1.0f).apply {
                    duration = 900
                    repeatMode = Animation.REVERSE
                    repeatCount = Animation.INFINITE
                }
                binding.btnIslandWake.startAnimation(wakePulseAnim)
            }
        } else {
            wakePulseAnim?.cancel()
            wakePulseAnim = null
            binding.btnIslandWake.clearAnimation()
            binding.btnIslandWake.alpha = 1.0f
            val secColor = ContextCompat.getColor(context, R.color.label_secondary)
            binding.imgIslandWake.setColorFilter(secColor)
            binding.tvIslandWakeLabel.setTextColor(secColor)
        }
    }

    fun toggleExpanded() {
        if (isExpanded) collapse() else expand()
    }

    fun expand() {
        if (isExpanded) return
        isExpanded = true
        triggerHaptic(HapticFeedbackConstants.CONTEXT_CLICK)

        val panel = binding.layoutExpandedPanel
        val pillWrapper = binding.layoutCollapsedWrapper
        val scrim = binding.viewIslandScrim

        scrim.visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(220).start()

        panel.visibility = View.VISIBLE
        panel.alpha = 0f
        panel.scaleX = 0.92f
        panel.scaleY = 0.92f

        if (areAnimationsDisabled()) {
            panel.alpha = 1f
            panel.scaleX = 1f
            panel.scaleY = 1f
            pillWrapper.alpha = 0f
            pillWrapper.visibility = View.INVISIBLE
            onExpandedStateChangeListener?.invoke(true)
            return
        }

        // Smooth transition to expanded panel
        pillWrapper.animate().alpha(0f).setDuration(150).withEndAction {
            pillWrapper.visibility = View.INVISIBLE
        }.start()

        panel.animate().alpha(1f).setDuration(180).start()

        if (expandSpringAnim == null) {
            expandSpringAnim = SpringAnimation(panel, DynamicAnimation.SCALE_X).apply {
                spring = SpringForce().apply {
                    stiffness = SpringForce.STIFFNESS_MEDIUM
                    dampingRatio = 0.75f
                }
            }
        }
        val springY = SpringAnimation(panel, DynamicAnimation.SCALE_Y).apply {
            spring = SpringForce().apply {
                stiffness = SpringForce.STIFFNESS_MEDIUM
                dampingRatio = 0.75f
            }
        }
        expandSpringAnim?.spring?.finalPosition = 1f
        expandSpringAnim?.start()
        springY.spring.finalPosition = 1f
        springY.start()

        onExpandedStateChangeListener?.invoke(true)
    }

    fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        triggerHaptic(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                HapticFeedbackConstants.SEGMENT_TICK
            else
                HapticFeedbackConstants.CLOCK_TICK
        )

        val panel = binding.layoutExpandedPanel
        val pillWrapper = binding.layoutCollapsedWrapper
        val scrim = binding.viewIslandScrim

        scrim.animate().alpha(0f).setDuration(180).withEndAction {
            scrim.visibility = View.GONE
        }.start()

        if (areAnimationsDisabled()) {
            panel.visibility = View.GONE
            pillWrapper.visibility = View.VISIBLE
            pillWrapper.alpha = 1f
            pillWrapper.scaleX = 1f
            pillWrapper.scaleY = 1f
            onExpandedStateChangeListener?.invoke(false)
            return
        }

        pillWrapper.visibility = View.VISIBLE
        pillWrapper.scaleX = 0.95f
        pillWrapper.scaleY = 0.95f
        pillWrapper.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()

        panel.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(180).withEndAction {
            panel.visibility = View.GONE
        }.start()

        onExpandedStateChangeListener?.invoke(false)
    }

    /**
     * Scroll handler: shrinks collapsed pill slightly (0.92x) when scrolled down.
     */
    fun onScroll(scrollY: Int) {
        if (isExpanded) {
            collapse()
            return
        }
        val pill = binding.islandPill

        if (scrollY > 20) {
            if (pill.scaleX != 0.92f) {
                pill.animate().scaleX(0.92f).scaleY(0.92f).setDuration(180).start()
            }
        } else {
            if (pill.scaleX != 1.0f) {
                pill.animate().scaleX(1.0f).scaleY(1.0f).setDuration(180).start()
            }
        }
    }

    fun resetVisualState() {
        binding.layoutCollapsedWrapper.alpha = 1.0f
        binding.layoutCollapsedWrapper.visibility = View.VISIBLE
        binding.islandPill.scaleX = 1.0f
        binding.islandPill.scaleY = 1.0f
        if (isExpanded) {
            collapse()
        }
    }

    private fun triggerHaptic(feedbackConstant: Int) {
        performHapticFeedback(feedbackConstant)
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
}
