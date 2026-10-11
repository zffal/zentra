package com.example.serverdashboard.ui.overview

import android.graphics.Color
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.serverdashboard.MainActivity
import com.example.serverdashboard.R
import com.example.serverdashboard.data.api.ConnectionState
import com.example.serverdashboard.data.model.CardId
import com.example.serverdashboard.data.model.CardSpan
import com.example.serverdashboard.data.model.OverviewConfig
import com.example.serverdashboard.data.model.SystemOverview
import com.example.serverdashboard.data.storage.OverviewConfigStore
import com.example.serverdashboard.databinding.FragmentOverviewBinding
import com.example.serverdashboard.ui.main.MainViewModel
import com.example.serverdashboard.ui.util.Formatters
import com.example.serverdashboard.ui.util.ServerNameResolver
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

class OverviewFragment : Fragment() {

    private var _binding: FragmentOverviewBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private var isEditMode = false
    private lateinit var configStore: OverviewConfigStore
    private lateinit var currentConfig: OverviewConfig
    private var backCallback: OnBackPressedCallback? = null
    private var cachedOverview: SystemOverview? = null
    private val netDownTracker = com.example.serverdashboard.ui.util.SpeedHysteresisTracker()
    private val netUpTracker = com.example.serverdashboard.ui.util.SpeedHysteresisTracker()
    private val diskReadTracker = com.example.serverdashboard.ui.util.SpeedHysteresisTracker()
    private val diskWriteTracker = com.example.serverdashboard.ui.util.SpeedHysteresisTracker()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOverviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        configStore = OverviewConfigStore.getInstance(requireContext())
        currentConfig = configStore.getConfig(viewModel.serverUrl)

        // Back press handling for Edit Mode
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (isEditMode) {
                    exitEditMode()
                }
            }
        }.also { backCallback = it })

        // One-time Customization Hint Banner
        if (!configStore.hasSeenEditHint()) {
            binding.layoutEditHintBanner.visibility = View.VISIBLE
            binding.btnDismissEditHint.setOnClickListener {
                binding.layoutEditHintBanner.visibility = View.GONE
                configStore.setSeenEditHint(true)
            }
        } else {
            binding.layoutEditHintBanner.visibility = View.GONE
        }

        // Edit Mode Toolbar actions
        binding.btnOverviewDone.setOnClickListener { exitEditMode() }
        binding.btnOverviewAddCard.setOnClickListener { showAddCardBottomSheet() }
        binding.btnOverviewReset.setOnClickListener { showResetConfirmDialog() }
        binding.btnChooseDrive.setOnClickListener { showDriveSelectorDialog() }

        // Card Long-press listener to enter Edit Mode
        val longClickListener = View.OnLongClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            enterEditMode()
            true
        }
        getCardViewsMap().values.forEach { cardView ->
            cardView.setOnLongClickListener(longClickListener)
        }

        // Sparklines initialization
        binding.sparklineCpu.setLineColor(ContextCompat.getColor(requireContext(), R.color.metric_cpu))
        binding.sparklineCpu.setMaxCapacity(100f)

        binding.swipeRefreshOverview.setColorSchemeResources(R.color.accent)
        binding.swipeRefreshOverview.setOnRefreshListener {
            viewModel.fetchRestMetrics()
            binding.swipeRefreshOverview.postDelayed({
                _binding?.swipeRefreshOverview?.isRefreshing = false
            }, 800)
        }

        // Tap for Details navigation
        setupTapForDetails()

        // Render initial card layout based on saved config
        renderCards()

        // Telemetry & State Observers
        viewModel.connectionState.observe(viewLifecycleOwner) { state ->
            updateHealthState(state, viewModel.systemOverview.value, viewModel.activeAlertsCount.value ?: 0)
        }

        viewModel.activeAlertsCount.observe(viewLifecycleOwner) { alertsCount ->
            updateHealthState(viewModel.connectionState.value, viewModel.systemOverview.value, alertsCount)
        }

        viewModel.systemOverview.observe(viewLifecycleOwner) { overview ->
            cachedOverview = overview
            updateUI(overview)
            updateHealthState(viewModel.connectionState.value, overview, viewModel.activeAlertsCount.value ?: 0)
        }

        viewModel.metricsFreshnessText.observe(viewLifecycleOwner) { text ->
            _binding?.tvMetricsFreshness?.text = text
        }

        viewModel.isMetricsStale.observe(viewLifecycleOwner) { isStale ->
            val b = _binding ?: return@observe
            if (isStale) {
                b.tvMetricsFreshness.setTextColor(ContextCompat.getColor(b.root.context, R.color.status_warning))
            } else {
                b.tvMetricsFreshness.setTextColor(
                    ContextCompat.getColor(b.root.context, R.color.text_muted)
                )
            }
        }

        viewModel.cpuHistory.observe(viewLifecycleOwner) { history ->
            val b = _binding ?: return@observe
            if (history.isNotEmpty()) {
                b.sparklineCpu.setData(history)
            }
        }

        viewModel.diskReadRate.observe(viewLifecycleOwner) {
            updateDiskIoCard()
        }

        viewModel.diskWriteRate.observe(viewLifecycleOwner) {
            updateDiskIoCard()
        }

        viewModel.networkPorts.observe(viewLifecycleOwner) {
            updatePortsCard()
        }
    }

    private fun getCardViewsMap(): Map<CardId, View> {
        return mapOf(
            CardId.HERO to binding.cardServerHealth,
            CardId.CPU to binding.cardCpu,
            CardId.MEMORY to binding.cardMemory,
            CardId.STORAGE to binding.cardStorage,
            CardId.THERMALS to binding.cardTemperature,
            CardId.NETWORK to binding.cardNetwork,
            CardId.SERVICES to binding.cardServices,
            CardId.INCIDENTS to binding.cardAlertsSummary,
            CardId.DISK_IO to binding.cardDiskIo,
            CardId.PORTS to binding.cardPorts,
            CardId.CONTAINERS to binding.cardContainers
        )
    }

    private fun renderCards() {
        val b = _binding ?: return
        val context = b.root.context
        val container = b.layoutCardsContainer
        val warehouse = b.layoutCardsWarehouse

        val cardMap = getCardViewsMap()

        // Safely detach all cards from their current parents
        cardMap.values.forEach { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        container.removeAllViews()

        val visibleCardIds = currentConfig.getVisibleCards()
        val gapPx = resources.getDimensionPixelSize(R.dimen.card_gap)
        val halfGapPx = gapPx / 2

        var i = 0
        while (i < visibleCardIds.size) {
            val cardId1 = visibleCardIds[i]
            val view1 = cardMap[cardId1]
            if (view1 == null) {
                i++
                continue
            }

            view1.visibility = View.VISIBLE
            setupCardEditHeader(cardId1, view1, i, visibleCardIds)

            // Dynamic Pairing: pair two consecutive HALF cards
            if (cardId1.defaultSpan == CardSpan.HALF &&
                i + 1 < visibleCardIds.size &&
                visibleCardIds[i + 1].defaultSpan == CardSpan.HALF
            ) {
                val cardId2 = visibleCardIds[i + 1]
                val view2 = cardMap[cardId2]
                if (view2 == null) {
                    // Fallback to full width if view2 is unavailable
                    view1.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = gapPx
                    }
                    container.addView(view1)
                    i++
                    continue
                }

                view2.visibility = View.VISIBLE
                setupCardEditHeader(cardId2, view2, i + 1, visibleCardIds)

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    isBaselineAligned = false
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = gapPx
                    }
                }

                view1.layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f
                ).apply {
                    marginEnd = halfGapPx
                    bottomMargin = 0
                }

                view2.layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f
                ).apply {
                    marginStart = halfGapPx
                    bottomMargin = 0
                }

                row.addView(view1)
                row.addView(view2)
                container.addView(row)
                i += 2
            } else {
                // FULL span card or lone HALF card: expand to full width
                view1.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = gapPx
                }
                container.addView(view1)
                i++
            }
        }

        // Park hidden cards in warehouse with GONE visibility
        val hiddenCardIds = currentConfig.hiddenCards
        for (hiddenId in hiddenCardIds) {
            val hiddenView = cardMap[hiddenId] ?: continue
            hiddenView.visibility = View.GONE
            warehouse.addView(hiddenView)
        }

        // Re-bind content for newly attached cards
        cachedOverview?.let { updateUI(it) }
    }

    private fun setupCardEditHeader(
        cardId: CardId,
        cardView: View,
        index: Int,
        visibleCardIds: List<CardId>
    ) {
        val innerLayout = (cardView as? ViewGroup)?.getChildAt(0) as? ViewGroup ?: return
        var editHeader = innerLayout.findViewById<View>(R.id.layoutCardEditHeader)
        if (editHeader == null) {
            editHeader = layoutInflater.inflate(R.layout.layout_card_edit_header, innerLayout, false)
            innerLayout.addView(editHeader, 0)
        }

        if (isEditMode) {
            editHeader.visibility = View.VISIBLE

            val btnUp = editHeader.findViewById<ImageButton>(R.id.btnMoveCardUp)
            val btnDown = editHeader.findViewById<ImageButton>(R.id.btnMoveCardDown)
            val btnHide = editHeader.findViewById<ImageButton>(R.id.btnHideCard)

            if (index == 0) {
                btnUp.isEnabled = false
                btnUp.alpha = 0.3f
            } else {
                btnUp.isEnabled = true
                btnUp.alpha = 1.0f
                btnUp.setOnClickListener { moveCard(cardId, -1) }
            }

            if (index == visibleCardIds.size - 1) {
                btnDown.isEnabled = false
                btnDown.alpha = 0.3f
            } else {
                btnDown.isEnabled = true
                btnDown.alpha = 1.0f
                btnDown.setOnClickListener { moveCard(cardId, 1) }
            }

            btnHide.setOnClickListener { hideCard(cardId) }
        } else {
            editHeader.visibility = View.GONE
        }
    }

    private fun moveCard(cardId: CardId, direction: Int) {
        val visible = currentConfig.getVisibleCards()
        val currentIndex = visible.indexOf(cardId)
        if (currentIndex == -1) return
        val targetIndex = currentIndex + direction
        if (targetIndex !in visible.indices) return

        val targetCardId = visible[targetIndex]
        val order = currentConfig.cardOrder.toMutableList()
        val orderIdx1 = order.indexOf(cardId)
        val orderIdx2 = order.indexOf(targetCardId)
        if (orderIdx1 != -1 && orderIdx2 != -1) {
            order[orderIdx1] = targetCardId
            order[orderIdx2] = cardId
            currentConfig = currentConfig.copy(cardOrder = order)
            configStore.saveConfig(viewModel.serverUrl, currentConfig)
            renderCards()
        }
    }

    private fun hideCard(cardId: CardId) {
        val visible = currentConfig.getVisibleCards()
        if (visible.size <= 1) {
            Toast.makeText(requireContext(), "At least one card must remain visible", Toast.LENGTH_SHORT).show()
            return
        }
        val newHidden = currentConfig.hiddenCards.toMutableSet()
        newHidden.add(cardId)
        currentConfig = currentConfig.copy(hiddenCards = newHidden)
        configStore.saveConfig(viewModel.serverUrl, currentConfig)
        renderCards()
    }

    private fun enterEditMode() {
        if (isEditMode) return
        isEditMode = true

        // Disable tab swiping and pull-to-refresh
        (activity as? MainActivity)?.setViewPagerUserInputEnabled(false)
        binding.swipeRefreshOverview.isEnabled = false

        // Dismiss hint banner and persist seen status
        binding.layoutEditHintBanner.visibility = View.GONE
        configStore.setSeenEditHint(true)

        // Show edit toolbar and drive change button
        binding.layoutEditToolbar.visibility = View.VISIBLE
        binding.btnChooseDrive.visibility = View.VISIBLE

        backCallback?.isEnabled = true

        renderCards()
    }

    private fun exitEditMode() {
        if (!isEditMode) return
        isEditMode = false

        // Persist config changes immediately
        configStore.saveConfig(viewModel.serverUrl, currentConfig)

        // Re-enable tab swiping and pull-to-refresh
        (activity as? MainActivity)?.setViewPagerUserInputEnabled(true)
        binding.swipeRefreshOverview.isEnabled = true

        // Hide edit toolbar and drive change button
        binding.layoutEditToolbar.visibility = View.GONE
        binding.btnChooseDrive.visibility = View.GONE

        backCallback?.isEnabled = false

        renderCards()
    }

    private fun showDriveSelectorDialog() {
        val data = cachedOverview ?: viewModel.systemOverview.value
        val filesystems = data?.storage?.filesystems.orEmpty()
        if (filesystems.isEmpty()) {
            Toast.makeText(requireContext(), "No filesystems reported by server", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_drive_selector, null)
        val layoutItems = sheetView.findViewById<LinearLayout>(R.id.layoutDriveItems)

        val currentMount = currentConfig.selectedMountPoint
            ?: filesystems.firstOrNull { it.mount_point == "/" }?.mount_point
            ?: filesystems.firstOrNull()?.mount_point

        for (fs in filesystems) {
            val itemView = layoutInflater.inflate(R.layout.item_drive_picker, layoutItems, false)
            val tvMount = itemView.findViewById<TextView>(R.id.tvItemMountPoint)
            val tvDev = itemView.findViewById<TextView>(R.id.tvItemDeviceAndFs)
            val tvSelectedBadge = itemView.findViewById<TextView>(R.id.tvItemSelectedBadge)
            val progress = itemView.findViewById<ProgressBar>(R.id.progressItemDrive)
            val tvUsage = itemView.findViewById<TextView>(R.id.tvItemDriveUsage)

            val mountPoint = fs.mount_point.ifEmpty { "/" }
            tvMount.text = mountPoint
            val devInfo = buildString {
                if (fs.device.isNotEmpty()) append(fs.device)
                if (fs.fstype.isNotEmpty()) {
                    if (isNotEmpty()) append(" • ")
                    append(fs.fstype)
                }
            }.ifEmpty { "Filesystem" }
            tvDev.text = devInfo

            val rawPercent = fs.usage_percent
            val percent = if (rawPercent.isFinite() && rawPercent >= 0f) rawPercent else 0f
            progress.progress = percent.toInt().coerceIn(0, 100)

            val used = Formatters.formatBytes(fs.used_bytes)
            val total = Formatters.formatBytes(fs.total_bytes)
            tvUsage.text = String.format(Locale.US, "%s / %s (%.1f%%)", used, total, percent)

            val isSelected = mountPoint == currentMount
            tvSelectedBadge.visibility = if (isSelected) View.VISIBLE else View.GONE

            itemView.setOnClickListener {
                currentConfig = currentConfig.copy(selectedMountPoint = mountPoint)
                configStore.saveConfig(viewModel.serverUrl, currentConfig)
                cachedOverview?.let { updateUI(it) }
                dialog.dismiss()
            }

            layoutItems.addView(itemView)
        }

        dialog.setContentView(sheetView)
        dialog.show()
    }

    private fun showAddCardBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_add_cards, null)
        val layoutItems = sheetView.findViewById<LinearLayout>(R.id.layoutAddCardItems)
        val tvEmpty = sheetView.findViewById<TextView>(R.id.tvNoHiddenCards)

        val hiddenCards = currentConfig.hiddenCards.toList()
        if (hiddenCards.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
        } else {
            tvEmpty.visibility = View.GONE
            val data = cachedOverview ?: viewModel.systemOverview.value

            for (cardId in hiddenCards) {
                val itemView = layoutInflater.inflate(R.layout.item_add_card, layoutItems, false)
                val tvTitle = itemView.findViewById<TextView>(R.id.tvAddCardTitle)
                val tvDesc = itemView.findViewById<TextView>(R.id.tvAddCardDesc)
                val tvStatus = itemView.findViewById<TextView>(R.id.tvAddCardStatus)
                val btnAdd = itemView.findViewById<MaterialButton>(R.id.btnAddCardAction)

                tvTitle.text = cardId.displayName
                var isAvailable = true

                when (cardId) {
                    CardId.DISK_IO -> {
                        tvDesc.text = "Real-time storage read and write throughput"
                        val ioAvailable = data?.storage?.isIoAvailable == true || viewModel.diskReadRate.value != null
                        if (!ioAvailable) {
                            isAvailable = false
                            tvStatus.text = "No I/O metrics reported by server"
                            tvStatus.visibility = View.VISIBLE
                        }
                    }
                    CardId.PORTS -> {
                        tvDesc.text = "Active listening ports and network sockets"
                    }
                    CardId.CONTAINERS -> {
                        tvDesc.text = "Docker container count and runtime health"
                        val dockerAvail = data?.docker?.available != false || (data?.containers_summary?.total ?: 0) > 0
                        if (!dockerAvail) {
                            isAvailable = false
                            tvStatus.text = "Docker daemon inactive"
                            tvStatus.visibility = View.VISIBLE
                        }
                    }
                    CardId.HERO -> tvDesc.text = "Server health status, uptime, and host summary"
                    CardId.CPU -> tvDesc.text = "Processor utilization, core count, and history sparkline"
                    CardId.MEMORY -> tvDesc.text = "RAM utilization, used bytes, and total capacity"
                    CardId.STORAGE -> tvDesc.text = "Primary filesystem disk space utilization"
                    CardId.THERMALS -> tvDesc.text = "CPU and motherboard thermal sensor temperatures"
                    CardId.NETWORK -> tvDesc.text = "Real-time upload and download transfer rates"
                    CardId.SERVICES -> tvDesc.text = "Background daemon and system service status"
                    CardId.INCIDENTS -> tvDesc.text = "Active alarms and system warning notifications"
                }

                if (!isAvailable) {
                    btnAdd.isEnabled = false
                    btnAdd.alpha = 0.4f
                } else {
                    btnAdd.isEnabled = true
                    btnAdd.alpha = 1.0f
                    btnAdd.setOnClickListener {
                        val newHidden = currentConfig.hiddenCards.toMutableSet()
                        newHidden.remove(cardId)
                        val newOrder = currentConfig.cardOrder.toMutableList()
                        if (cardId !in newOrder) {
                            newOrder.add(cardId)
                        }
                        currentConfig = currentConfig.copy(cardOrder = newOrder, hiddenCards = newHidden)
                        configStore.saveConfig(viewModel.serverUrl, currentConfig)
                        renderCards()
                        dialog.dismiss()
                    }
                }

                layoutItems.addView(itemView)
            }
        }

        dialog.setContentView(sheetView)
        dialog.show()
    }

    private fun showResetConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.overview_reset_title)
            .setMessage(R.string.overview_reset_message)
            .setPositiveButton(R.string.overview_edit_reset) { _, _ ->
                currentConfig = configStore.resetToDefaults(viewModel.serverUrl)
                renderCards()
                cachedOverview?.let { updateUI(it) }
                Toast.makeText(requireContext(), "Overview reset to defaults", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupTapForDetails() {
        val mainActivity = activity as? MainActivity

        binding.cardServerHealth.setOnClickListener {
            if (isEditMode) return@setOnClickListener
            val alerts = viewModel.activeAlertsCount.value ?: 0
            if (alerts > 0) {
                mainActivity?.navigateToTab(4) // Alerts
            } else {
                mainActivity?.navigateToTab(1) // Systems
            }
        }

        binding.cardCpu.setOnClickListener {
            if (isEditMode) return@setOnClickListener
            mainActivity?.navigateToTab(1) // Systems (CPU details)
        }

        binding.cardMemory.setOnClickListener {
            if (isEditMode) return@setOnClickListener
            mainActivity?.navigateToTab(1) // Systems (Memory details)
        }

        binding.cardTemperature.setOnClickListener {
            if (isEditMode) return@setOnClickListener
            mainActivity?.navigateToTab(1) // Systems (Thermals details)
        }

        binding.cardStorage.setOnClickListener {
            if (isEditMode) {
                showDriveSelectorDialog()
                return@setOnClickListener
            }
            mainActivity?.navigateToTab(3) // Storage (Drives & I/O)
        }

        binding.cardServices.setOnClickListener {
            if (isEditMode) return@setOnClickListener
            mainActivity?.navigateToTab(2) // Services (Containers)
        }

        binding.cardAlertsSummary.setOnClickListener {
            if (isEditMode) return@setOnClickListener
            mainActivity?.navigateToTab(4) // Alerts
        }

        binding.cardNetwork.setOnClickListener {
            if (isEditMode) return@setOnClickListener
            mainActivity?.navigateToTab(3) // Storage & Network
        }

        binding.cardDiskIo.setOnClickListener {
            if (isEditMode) return@setOnClickListener
            mainActivity?.navigateToTab(3) // Storage & Network
        }

        binding.cardPorts.setOnClickListener {
            if (isEditMode) return@setOnClickListener
            mainActivity?.navigateToTab(3) // Storage & Network
        }

        binding.cardContainers.setOnClickListener {
            if (isEditMode) return@setOnClickListener
            mainActivity?.navigateToTab(2) // Services
        }
    }

    private fun updateHealthState(state: ConnectionState?, data: SystemOverview?, alertsCount: Int) {
        val b = _binding ?: return
        val isOffline = state == ConnectionState.DISCONNECTED
        val cpuUsage = if (data?.cpu?.overall_usage?.isFinite() == true) data.cpu.overall_usage else 0f
        val ramUsage = if (data?.memory?.usage_percent?.isFinite() == true) data.memory.usage_percent else 0f
        val cpuTemp = data?.temperature?.cpu?.takeIf { it.isFinite() } ?: 0f
        val mainFs = data?.storage?.filesystems?.firstOrNull { it.mount_point == "/" }
            ?: data?.storage?.filesystems?.firstOrNull()
        val diskUsage = if (mainFs?.usage_percent?.isFinite() == true) mainFs.usage_percent else 0f

        val needsAttention = alertsCount > 0 || cpuUsage >= 85f || ramUsage >= 90f || cpuTemp >= 80f || diskUsage >= 90f

        val ctx = b.root.context
        when {
            isOffline -> {
                b.imgHealthDot.setImageResource(R.drawable.dot_offline)
                b.tvHealthStatusTitle.text = getString(R.string.status_offline)
                b.tvHealthStatusTitle.setTextColor(ContextCompat.getColor(ctx, R.color.critical))
                b.cardServerHealth.contentDescription = getString(R.string.cd_hero_offline)
                b.tvUptimeBadge.text = getString(R.string.status_offline)
                b.tvUptimeBadge.setBackgroundResource(R.drawable.badge_critical)
                b.tvUptimeBadge.setTextColor(ContextCompat.getColor(ctx, R.color.critical))
            }
            needsAttention -> {
                b.imgHealthDot.setImageResource(R.drawable.dot_reconnecting)
                b.tvHealthStatusTitle.text = getString(R.string.status_attention)
                b.tvHealthStatusTitle.setTextColor(ContextCompat.getColor(ctx, R.color.warning))
                b.cardServerHealth.contentDescription = getString(R.string.cd_hero_attention)
                b.tvUptimeBadge.text = getString(R.string.status_attention)
                b.tvUptimeBadge.setBackgroundResource(R.drawable.badge_warning)
                b.tvUptimeBadge.setTextColor(ContextCompat.getColor(ctx, R.color.warning))
            }
            else -> {
                b.imgHealthDot.setImageResource(R.drawable.dot_online)
                b.tvHealthStatusTitle.text = getString(R.string.status_healthy)
                b.tvHealthStatusTitle.setTextColor(ContextCompat.getColor(ctx, R.color.success))
                b.cardServerHealth.contentDescription = getString(R.string.cd_hero_healthy)
                val uptimeSec = data?.resolvedHost?.uptime?.takeIf { it > 0 }?.toLong()
                    ?: data?.uptime?.toLong() ?: 0L
                b.tvUptimeBadge.text = if (uptimeSec > 0) "Up " + Formatters.formatUptime(uptimeSec) else getString(R.string.status_healthy)
                b.tvUptimeBadge.setBackgroundResource(R.drawable.badge_running)
                b.tvUptimeBadge.setTextColor(ContextCompat.getColor(ctx, R.color.success))
            }
        }

        // Alerts summary row
        if (alertsCount > 0) {
            b.tvAlertsSummaryDesc.text = getString(R.string.alerts_active_format, alertsCount)
            b.tvAlertsSummaryBadge.text = getString(R.string.alerts_active_format, alertsCount)
            b.tvAlertsSummaryBadge.setBackgroundResource(R.drawable.badge_warning)
            b.tvAlertsSummaryBadge.setTextColor(ContextCompat.getColor(ctx, R.color.warning))
        } else {
            b.tvAlertsSummaryDesc.text = getString(R.string.alerts_none)
            b.tvAlertsSummaryBadge.text = getString(R.string.status_healthy)
            b.tvAlertsSummaryBadge.setBackgroundResource(R.drawable.badge_running)
            b.tvAlertsSummaryBadge.setTextColor(ContextCompat.getColor(ctx, R.color.success))
        }
    }

    private fun updateDiskIoCard() {
        val b = _binding ?: return
        val ctx = b.root.context
        val readBps = viewModel.diskReadRate.value ?: cachedOverview?.storage?.read_speed_bps ?: 0L
        val writeBps = viewModel.diskWriteRate.value ?: cachedOverview?.storage?.write_speed_bps ?: 0L

        b.tvDiskIoRead.text = "${Formatters.formatSpeed(readBps, tracker = diskReadTracker)} Read ↓"
        b.tvDiskIoWrite.text = "${Formatters.formatSpeed(writeBps, tracker = diskWriteTracker)} Write ↑"

        if (readBps > 100_000L || writeBps > 100_000L) {
            b.tvDiskIoBadge.text = "I/O Active"
            b.tvDiskIoBadge.setBackgroundResource(R.drawable.badge_running)
            b.tvDiskIoBadge.setTextColor(ContextCompat.getColor(ctx, R.color.accent))
        } else {
            b.tvDiskIoBadge.text = "Idle"
            b.tvDiskIoBadge.setBackgroundResource(R.drawable.badge_stopped)
            b.tvDiskIoBadge.setTextColor(ContextCompat.getColor(ctx, R.color.label_tertiary))
        }
    }

    private fun updatePortsCard() {
        val b = _binding ?: return
        val ctx = b.root.context
        val portsResp = viewModel.networkPorts.value
        val portsList = portsResp?.activePorts ?: emptyList()

        if (portsList.isNotEmpty()) {
            b.tvPortsCount.text = "${portsList.size} open ports"
            val samplePorts = portsList.take(4).map { ":${it.port}" }.joinToString(", ")
            b.tvPortsSummary.text = "Listening: $samplePorts"
            b.tvPortsBadge.text = "Listening"
            b.tvPortsBadge.setBackgroundResource(R.drawable.badge_running)
            b.tvPortsBadge.setTextColor(ContextCompat.getColor(ctx, R.color.success))
        } else {
            b.tvPortsCount.text = "Listening Ports"
            b.tvPortsSummary.text = "Active network sockets"
            b.tvPortsBadge.text = "Active"
            b.tvPortsBadge.setBackgroundResource(R.drawable.badge_running)
            b.tvPortsBadge.setTextColor(ContextCompat.getColor(ctx, R.color.success))
        }
    }

    private fun updateUI(data: SystemOverview) {
        val b = _binding ?: return
        val ctx = b.root.context
        val host = data.resolvedHost
        val hostname = ServerNameResolver.resolveServerName(ctx, viewModel.serverUrl, host.hostname)
        val os = host.os.ifEmpty { "Ubuntu Linux" }
        val arch = if (host.architecture.isNotEmpty()) host.architecture else host.arch
        b.tvServerMeta.text = "$hostname • $os ($arch)"

        // 1. CPU Usage
        val cpuUsage = if (data.cpu.overall_usage.isFinite()) data.cpu.overall_usage else 0f
        b.tvCpuPercent.text = Formatters.formatPercent(cpuUsage)
        val cores = data.cpu.core_count
        b.tvCpuSubtext.text = getString(R.string.cpu_cores_format, cores)

        // 2. Memory
        val ramUsage = if (data.memory.usage_percent.isFinite()) data.memory.usage_percent else 0f
        b.tvRamPercent.text = Formatters.formatPercent(ramUsage)
        b.progressRam.progress = ramUsage.toInt().coerceIn(0, 100)
        val usedRam = Formatters.formatBytes(data.memory.used_bytes)
        val totalRam = Formatters.formatBytes(data.memory.total_bytes)
        b.tvRamDetails.text = "$usedRam / $totalRam"

        // 3. Temperature
        val temp = data.temperature
        val cpuTemp = temp?.cpu
        if (cpuTemp != null && cpuTemp.isFinite() && cpuTemp > 0) {
            b.tvCpuTemp.text = String.format(Locale.US, "%.0f°C", cpuTemp)
            when {
                cpuTemp >= 85f -> {
                    b.tvCpuTemp.setTextColor(ContextCompat.getColor(ctx, R.color.critical))
                    b.tvTempBadge.text = "Too hot"
                    b.tvTempBadge.setBackgroundResource(R.drawable.badge_critical)
                    b.tvTempBadge.setTextColor(ContextCompat.getColor(ctx, R.color.critical))
                    b.tvThermalExplanation.text = "Critical temp"
                }
                cpuTemp >= 70f -> {
                    b.tvCpuTemp.setTextColor(ContextCompat.getColor(ctx, R.color.warning))
                    b.tvTempBadge.text = "Warm"
                    b.tvTempBadge.setBackgroundResource(R.drawable.badge_warning)
                    b.tvTempBadge.setTextColor(ContextCompat.getColor(ctx, R.color.warning))
                    b.tvThermalExplanation.text = "Elevated temp"
                }
                else -> {
                    b.tvCpuTemp.setTextColor(ContextCompat.getColor(ctx, R.color.label_primary))
                    b.tvTempBadge.text = getString(R.string.thermal_normal)
                    b.tvTempBadge.setBackgroundResource(R.drawable.badge_running)
                    b.tvTempBadge.setTextColor(ContextCompat.getColor(ctx, R.color.success))
                    b.tvThermalExplanation.text = getString(R.string.thermal_normal)
                }
            }
        } else {
            b.tvCpuTemp.text = "N/A"
            b.tvTempBadge.text = "Unknown"
            b.tvTempBadge.setBackgroundResource(R.drawable.badge_stopped)
            b.tvTempBadge.setTextColor(ContextCompat.getColor(ctx, R.color.label_tertiary))
            b.tvThermalExplanation.text = "N/A"
        }

        // 4. Storage (Driven by selectedMountPoint with fallback and missing state)
        val targetMount = currentConfig.selectedMountPoint
        val selectedFs = if (!targetMount.isNullOrEmpty()) {
            data.storage.filesystems.firstOrNull { it.mount_point == targetMount }
        } else {
            data.storage.filesystems.firstOrNull { it.mount_point == "/" }
                ?: data.storage.filesystems.firstOrNull()
        }

        if (!targetMount.isNullOrEmpty() && selectedFs == null) {
            // Selected drive is currently missing or unmounted
            b.tvStorageMountName.text = "$targetMount (Missing)"
            b.tvDiskPercent.text = "N/A"
            b.progressDisk.progress = 0
            b.tvDiskDetails.text = "Drive unmounted or unavailable"
            b.tvDiskBadge.text = "Missing"
            b.tvDiskBadge.setBackgroundResource(R.drawable.badge_critical)
            b.tvDiskBadge.setTextColor(ContextCompat.getColor(ctx, R.color.critical))
            b.btnChooseDrive.visibility = View.VISIBLE
        } else if (selectedFs != null) {
            b.tvStorageMountName.text = selectedFs.mount_point
            val rawPercent = selectedFs.usage_percent
            val diskPercent = if (rawPercent.isFinite() && rawPercent >= 0f) rawPercent else 0f
            b.tvDiskPercent.text = Formatters.formatPercent(diskPercent)
            b.progressDisk.progress = diskPercent.toInt().coerceIn(0, 100)
            val usedDisk = Formatters.formatBytes(selectedFs.used_bytes)
            val totalDisk = Formatters.formatBytes(selectedFs.total_bytes)
            b.tvDiskDetails.text = "$usedDisk / $totalDisk"

            if (diskPercent >= 85f) {
                b.tvDiskBadge.text = "Full"
                b.tvDiskBadge.setBackgroundResource(R.drawable.badge_warning)
                b.tvDiskBadge.setTextColor(ContextCompat.getColor(ctx, R.color.warning))
            } else {
                b.tvDiskBadge.text = "Healthy"
                b.tvDiskBadge.setBackgroundResource(R.drawable.badge_running)
                b.tvDiskBadge.setTextColor(ContextCompat.getColor(ctx, R.color.success))
            }
            b.btnChooseDrive.visibility = if (isEditMode) View.VISIBLE else View.GONE
        } else {
            b.tvStorageMountName.text = "/"
            b.tvDiskPercent.text = "N/A"
            b.progressDisk.progress = 0
            b.tvDiskDetails.text = "No storage"
            b.tvDiskBadge.text = "Unknown"
            b.tvDiskBadge.setBackgroundResource(R.drawable.badge_stopped)
            b.tvDiskBadge.setTextColor(ContextCompat.getColor(ctx, R.color.label_tertiary))
            b.btnChooseDrive.visibility = if (isEditMode) View.VISIBLE else View.GONE
        }

        // 5. Services (Containers summary)
        val summary = data.containers_summary
        b.tvContainersRunning.text = getString(R.string.services_running_format, summary.running, summary.total)
        b.tvContainersSummary.text = if (summary.stopped > 0) "${summary.stopped} stopped" else "All up"
        if (summary.stopped > 0) {
            b.tvContainersRunning.setBackgroundResource(R.drawable.badge_warning)
            b.tvContainersRunning.setTextColor(ContextCompat.getColor(ctx, R.color.warning))
        } else {
            b.tvContainersRunning.setBackgroundResource(R.drawable.badge_running)
            b.tvContainersRunning.setTextColor(ContextCompat.getColor(ctx, R.color.success))
        }

        // 6. Network
        val netConfig = com.example.serverdashboard.data.storage.NetworkConfigStore.getInstance(ctx)
        val useBits = netConfig.speedInBits
        val downSpeed = data.network.download_speed_bps
        val upSpeed = data.network.upload_speed_bps
        b.tvDownSpeed.text = "${Formatters.formatSpeed(downSpeed, useBits, netDownTracker)} ↓"
        b.tvUpSpeed.text = "${Formatters.formatSpeed(upSpeed, useBits, netUpTracker)} ↑"

        if (downSpeed > 500_000L || upSpeed > 500_000L) {
            b.tvNetworkStateBadge.text = "Active"
            b.tvNetworkStateBadge.setBackgroundResource(R.drawable.badge_running)
            b.tvNetworkStateBadge.setTextColor(ContextCompat.getColor(ctx, R.color.accent))
        } else {
            b.tvNetworkStateBadge.text = "Normal"
            b.tvNetworkStateBadge.setBackgroundResource(R.drawable.badge_running)
            b.tvNetworkStateBadge.setTextColor(ContextCompat.getColor(ctx, R.color.success))
        }

        // 7. Containers Card (Dynamic)
        b.tvContainersCardRunning.text = "${summary.running} / ${summary.total} running"
        b.tvContainersCardSummary.text = if (summary.stopped > 0) "${summary.stopped} stopped" else "All containers active"
        if (summary.stopped > 0) {
            b.tvContainersCardBadge.text = "Attention"
            b.tvContainersCardBadge.setBackgroundResource(R.drawable.badge_warning)
            b.tvContainersCardBadge.setTextColor(ContextCompat.getColor(ctx, R.color.warning))
        } else {
            b.tvContainersCardBadge.text = "Active"
            b.tvContainersCardBadge.setBackgroundResource(R.drawable.badge_running)
            b.tvContainersCardBadge.setTextColor(ContextCompat.getColor(ctx, R.color.success))
        }

        // 8. Disk I/O & Ports Cards
        updateDiskIoCard()
        updatePortsCard()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
