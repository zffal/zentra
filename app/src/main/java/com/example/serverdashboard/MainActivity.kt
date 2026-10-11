package com.example.serverdashboard

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.example.serverdashboard.data.api.ConnectionState
import com.example.serverdashboard.data.api.StreamState
import com.example.serverdashboard.data.storage.SecureTokenManager
import com.example.serverdashboard.data.storage.ServerStore
import com.example.serverdashboard.databinding.ActivityMainBinding
import com.example.serverdashboard.ui.alerts.AlertsFragment
import com.example.serverdashboard.ui.connect.ServerConnectActivity
import com.example.serverdashboard.ui.containers.ContainersFragment
import com.example.serverdashboard.ui.main.MainViewModel
import com.example.serverdashboard.ui.overview.OverviewFragment
import com.example.serverdashboard.ui.settings.SettingsBottomSheet
import com.example.serverdashboard.ui.storage.StorageNetworkFragment
import com.example.serverdashboard.ui.systems.SystemsFragment
import com.example.serverdashboard.ui.util.Formatters
import com.example.serverdashboard.ui.util.HapticsUtil
import com.example.serverdashboard.ui.util.ServerNameResolver
import com.example.serverdashboard.ui.util.WakeOnLanUtil
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 1. Configure Edge-to-Edge window handling
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        // Dynamically configure status bar & navigation bar icon colors for theme contrast
        val isNightMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNightMode
            isAppearanceLightNavigationBars = !isNightMode
        }

        var serverUrl = intent.getStringExtra("server_url") ?: ""
        var authToken = intent.getStringExtra("auth_token") ?: ""

        val serverStore = ServerStore.getInstance(this)
        val tokenManager = SecureTokenManager.getInstance(this)

        if (serverUrl.isEmpty()) {
            val active = serverStore.getActiveServer()
            if (active != null) {
                serverUrl = active.url
            }
        }

        if (serverUrl.isNotEmpty() && authToken.isEmpty()) {
            authToken = tokenManager.getToken(serverUrl) ?: ""
        }

        // If no server configured or not authenticated, redirect to connection screen
        if (serverUrl.isEmpty() || authToken.isEmpty()) {
            startActivity(Intent(this, ServerConnectActivity::class.java))
            finish()
            return
        }

        // Ensure active server in ServerStore matches the server MainActivity is connected to
        val active = serverStore.getActiveServer()
        if (active == null || !active.url.equals(serverUrl, ignoreCase = true)) {
            val config = serverStore.addOrUpdateServer(serverUrl)
            serverStore.setActiveServer(config.id)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 2. Apply proper WindowInsets to avoid system bar overlap
        applyWindowInsets()

        // Initialize Status Island header display
        val hostDisplay = Formatters.extractHost(serverUrl)
        val initialHost = ServerNameResolver.resolveServerName(this, serverUrl, null)
        binding.statusIsland.setServerInfo(initialHost, hostDisplay, "")

        viewModel.initialize(serverUrl, authToken)

        setupStatusIsland()
        setupViewPager()
        setupFloatingNav()
        observeViewModel()

        val targetTab = intent.getIntExtra(EXTRA_NAVIGATE_TAB, -1)
        if (targetTab in 0..4) {
            binding.viewPager.post { navigateToTab(targetTab) }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.statusIsland.resetVisualState()
        val currentOverview = viewModel.systemOverview.value
        val host = ServerNameResolver.resolveServerName(
            this,
            viewModel.serverUrl,
            currentOverview?.resolvedHost?.hostname
        )
        val cleanUrl = Formatters.extractHost(viewModel.serverUrl)
        val uptimeSec = currentOverview?.resolvedHost?.uptime?.takeIf { it > 0 }?.toLong()
            ?: currentOverview?.uptime?.toLong() ?: 0L
        val uptime = if (uptimeSec > 0) "Up " + Formatters.formatUptime(uptimeSec) else ""
        binding.statusIsland.setServerInfo(host, cleanUrl, uptime)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val targetTab = intent?.getIntExtra(EXTRA_NAVIGATE_TAB, -1) ?: -1
        if (targetTab in 0..4) {
            binding.viewPager.post { navigateToTab(targetTab) }
        }
    }

    companion object {
        const val EXTRA_NAVIGATE_TAB = "extra_navigate_tab"
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun applyWindowInsets() {
        val islandHeight = resources.getDimensionPixelSize(R.dimen.status_island_collapsed_height)

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )

            // Status Island positioned 8dp below status bar inset
            val islandTopMargin = insets.top + dpToPx(8)
            binding.statusIsland.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = islandTopMargin
            }

            // Tab content paddingTop = status bar inset + pill height + 12dp clearance below pill
            binding.viewPager.updatePadding(
                top = islandTopMargin + islandHeight + dpToPx(12),
                bottom = 0
            )

            // navArea absorbs gesture navigation bar inset
            binding.navArea.updatePadding(
                left = insets.left,
                right = insets.right,
                bottom = insets.bottom
            )

            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onBackground()
    }

    private fun setupStatusIsland() {
        val island = binding.statusIsland

        // Predictive back / back gesture support: collapses island when expanded
        val backCallback = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (island.isExpanded) {
                    island.collapse()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        island.onExpandedStateChangeListener = { expanded ->
            backCallback.isEnabled = expanded
        }

        island.onWakeClickListener = {
            val legacyPrefs = getSharedPreferences("omnis_wol_prefs", Context.MODE_PRIVATE)
            val prefs = getSharedPreferences("zentra_wol_prefs", Context.MODE_PRIVATE)
            val mac = prefs.getString("wol_mac", legacyPrefs.getString("wol_mac", "") ?: "") ?: ""
            val broadcast = prefs.getString("wol_broadcast", legacyPrefs.getString("wol_broadcast", "192.168.1.255") ?: "192.168.1.255") ?: "192.168.1.255"
            val port = if (prefs.contains("wol_port")) prefs.getInt("wol_port", 9) else legacyPrefs.getInt("wol_port", 9)

            if (mac.isEmpty()) {
                Snackbar.make(
                    binding.mainRoot,
                    "Configure server MAC address to enable Wake-on-LAN",
                    Snackbar.LENGTH_SHORT
                ).setAnchorView(binding.floatingNavContainer).show()

                if (!supportFragmentManager.isStateSaved && supportFragmentManager.findFragmentByTag("settings_sheet") == null) {
                    try {
                        val sheet = SettingsBottomSheet.newInstance(expandWol = true)
                        sheet.show(supportFragmentManager, "settings_sheet")
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Failed to show settings sheet", e)
                    }
                }
            } else {
                lifecycleScope.launch {
                    val result = WakeOnLanUtil.sendMagicPacket(mac, broadcast, port)
                    val hostDisplay = ServerNameResolver.resolveServerName(
                        this@MainActivity,
                        viewModel.serverUrl,
                        viewModel.systemOverview.value?.resolvedHost?.hostname
                    )
                    if (result.isSuccess) {
                        Snackbar.make(
                            binding.mainRoot,
                            "Wake signal sent to $hostDisplay",
                            Snackbar.LENGTH_SHORT
                        ).setAnchorView(binding.floatingNavContainer).show()
                    } else {
                        val err = result.exceptionOrNull()?.message ?: "Failed to send wake signal"
                        Snackbar.make(
                            binding.mainRoot,
                            "Error: $err",
                            Snackbar.LENGTH_LONG
                        ).setAnchorView(binding.floatingNavContainer).show()
                    }
                }
            }
        }

        island.onRefreshClickListener = {
            if (viewModel.isRefreshing.value != true) {
                viewModel.refreshTelemetry()
            }
        }

        island.onAlertsClickListener = {
            binding.viewPager.setCurrentItem(4, true)
        }

        island.onSettingsClickListener = {
            if (!supportFragmentManager.isStateSaved && supportFragmentManager.findFragmentByTag("settings_sheet") == null) {
                try {
                    val sheet = SettingsBottomSheet()
                    sheet.show(supportFragmentManager, "settings_sheet")
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to show settings sheet", e)
                }
            }
        }
    }

    private fun setupViewPager() {
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 5
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> OverviewFragment()
                1 -> SystemsFragment()
                2 -> ContainersFragment()
                3 -> StorageNetworkFragment()
                4 -> AlertsFragment()
                else -> OverviewFragment()
            }
        }
        binding.viewPager.offscreenPageLimit = 4

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateFloatingNavSelection(position)
                binding.viewPager.post {
                    val pageView = (binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
                        ?.findViewHolderForAdapterPosition(position)?.itemView
                    android.util.Log.d(
                        "ZentraLayout",
                        "Tab $position view: width=${pageView?.width}, height=${pageView?.height}, visibility=${pageView?.visibility}, alpha=${pageView?.alpha}"
                    )
                }
            }
        })

        binding.viewPager.post {
            android.util.Log.d(
                "ZentraLayout",
                "ViewPager2: width=${binding.viewPager.width}, height=${binding.viewPager.height}, " +
                "visibility=${binding.viewPager.visibility}, alpha=${binding.viewPager.alpha}; " +
                "statusIsland: height=${binding.statusIsland.height}; " +
                "floatingNavContainer: height=${binding.floatingNavContainer.height}"
            )
        }
    }

    fun navigateToTab(tabIndex: Int) {
        if (tabIndex in 0..4) {
            binding.viewPager.setCurrentItem(tabIndex, true)
        }
    }

    fun setViewPagerUserInputEnabled(enabled: Boolean) {
        binding.viewPager.isUserInputEnabled = enabled
    }

    private fun setupFloatingNav() {
        val nav = binding.floatingNav

        // Full pill outline clipping for the capsule container
        val pillOutlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radius = view.height / 2f
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        nav.capsuleNavContainer.outlineProvider = pillOutlineProvider
        nav.capsuleNavContainer.clipToOutline = true

        // Tooltips and long-press accessibility support
        val destinations = listOf(
            Triple(nav.navItemOverview, 0, "Overview"),
            Triple(nav.navItemSystems, 1, "Systems"),
            Triple(nav.navItemContainers, 2, "Services"),
            Triple(nav.navItemStorage, 3, "Storage"),
            Triple(nav.navItemAlerts, 4, "Alerts")
        )

        destinations.forEach { (view, index, title) ->
            TooltipCompat.setTooltipText(view, title)
            view.setOnClickListener {
                HapticsUtil.tick(view)
                binding.viewPager.setCurrentItem(index, true)
            }
            view.setOnLongClickListener {
                HapticsUtil.press(view)
                Toast.makeText(this, title, Toast.LENGTH_SHORT).show()
                true
            }
        }

        updateFloatingNavSelection(0)
    }

    private var navSpringAnim: SpringAnimation? = null

    private fun updateFloatingNavSelection(selectedIndex: Int) {
        val nav = binding.floatingNav
        data class NavItem(
            val container: View,
            val icon: ImageView,
            val label: TextView,
            val filledIcon: Int,
            val outlineIcon: Int
        )

        val items = listOf(
            NavItem(nav.navItemOverview, nav.navIconOverview, nav.tvNavLabelOverview, R.drawable.ic_nav_overview_filled, R.drawable.ic_nav_overview),
            NavItem(nav.navItemSystems, nav.navIconSystems, nav.tvNavLabelSystems, R.drawable.ic_nav_systems_filled, R.drawable.ic_nav_systems),
            NavItem(nav.navItemContainers, nav.navIconContainers, nav.tvNavLabelContainers, R.drawable.ic_nav_containers_filled, R.drawable.ic_nav_containers),
            NavItem(nav.navItemStorage, nav.navIconStorage, nav.tvNavLabelStorage, R.drawable.ic_nav_storage_filled, R.drawable.ic_nav_storage),
            NavItem(nav.navItemAlerts, nav.navIconAlerts, nav.tvNavLabelAlerts, R.drawable.ic_nav_alerts_filled, R.drawable.ic_nav_alerts)
        )

        val accentColor = ContextCompat.getColor(this, R.color.accent)
        val mutedColor = ContextCompat.getColor(this, R.color.label_secondary)

        for (i in items.indices) {
            val item = items[i]
            item.container.background = null
            if (i == selectedIndex) {
                item.icon.setImageResource(item.filledIcon)
                item.icon.setColorFilter(accentColor)
                item.label.setTextColor(accentColor)
                item.label.setTypeface(null, android.graphics.Typeface.BOLD)
                item.container.animate().scaleX(1.04f).scaleY(1.04f).setDuration(220).start()
            } else {
                item.icon.setImageResource(item.outlineIcon)
                item.icon.setColorFilter(mutedColor)
                item.label.setTextColor(mutedColor)
                item.label.setTypeface(null, android.graphics.Typeface.NORMAL)
                item.container.animate().scaleX(1.0f).scaleY(1.0f).setDuration(180).start()
            }
        }

        val targetItem = items.getOrNull(selectedIndex)?.container ?: return
        nav.capsuleNavContainer.post {
            if (isFinishing || isDestroyed) return@post
            val targetWidth = targetItem.width
            if (targetWidth > 0) {
                if (nav.navSlidingPill.layoutParams.width != targetWidth) {
                    nav.navSlidingPill.updateLayoutParams { width = targetWidth }
                }
                val targetX = nav.navItemsRow.left + targetItem.left.toFloat()
                if (nav.navSlidingPill.isLaidOut && nav.navSlidingPill.visibility == View.VISIBLE) {
                    if (navSpringAnim == null) {
                        navSpringAnim = SpringAnimation(nav.navSlidingPill, DynamicAnimation.TRANSLATION_X).apply {
                            spring = SpringForce().apply {
                                stiffness = SpringForce.STIFFNESS_LOW
                                dampingRatio = 0.78f
                            }
                        }
                    }
                    navSpringAnim?.spring?.finalPosition = targetX
                    navSpringAnim?.start()
                } else {
                    navSpringAnim?.cancel()
                    nav.navSlidingPill.translationX = targetX
                    nav.navSlidingPill.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun observeViewModel() {
        val updateTopStatus = {
            val conn = viewModel.connectionState.value
            val stream = viewModel.streamState.value
            binding.statusIsland.setConnectionState(conn, stream)
        }

        viewModel.connectionState.observe(this) { updateTopStatus() }
        viewModel.streamState.observe(this) { updateTopStatus() }

        viewModel.activeAlertsCount.observe(this) { count ->
            binding.statusIsland.setActiveAlertsCount(count)
        }

        viewModel.isRefreshing.observe(this) { refreshing ->
            binding.statusIsland.setIsRefreshing(refreshing == true)
        }

        viewModel.isMetricsStale.observe(this) { isStale ->
            binding.statusIsland.setIsStale(isStale == true)
        }

        viewModel.metricsFreshnessText.observe(this) { freshness ->
            binding.statusIsland.setExpandedFreshness(freshness ?: "")
        }

        viewModel.systemOverview.observe(this) { overview ->
            val host = ServerNameResolver.resolveServerName(
                this,
                viewModel.serverUrl,
                overview?.resolvedHost?.hostname
            )
            val cleanUrl = Formatters.extractHost(viewModel.serverUrl)
            val uptimeSec = overview?.resolvedHost?.uptime?.takeIf { it > 0 }?.toLong()
                ?: overview?.uptime?.toLong() ?: 0L
            val uptime = if (uptimeSec > 0) "Up " + Formatters.formatUptime(uptimeSec) else ""
            binding.statusIsland.setServerInfo(host, cleanUrl, uptime)

            val cpu = if (overview?.cpu?.overall_usage?.isFinite() == true) overview.cpu.overall_usage else 0f
            binding.statusIsland.setCpuPercent(cpu)
        }

        viewModel.refreshResultEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let { result ->
                if (result.success) {
                    Toast.makeText(this, "Server telemetry updated.", Toast.LENGTH_SHORT).show()
                } else {
                    val msg = result.message ?: "Failed to refresh telemetry."
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }

        viewModel.authInvalidEvent.observe(this) { invalid ->
            if (invalid == true) {
                val tokenManager = SecureTokenManager.getInstance(this)
                val activeServer = viewModel.serverUrl
                tokenManager.clearToken(activeServer)
                val intent = Intent(this, ServerConnectActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
        }
    }
}
