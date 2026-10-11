package com.example.serverdashboard.ui.storage

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.example.serverdashboard.R
import com.example.serverdashboard.data.api.ApiClient
import com.example.serverdashboard.data.model.*
import com.example.serverdashboard.data.storage.GraphConfigStore
import com.example.serverdashboard.data.storage.NetworkConfigStore
import com.example.serverdashboard.databinding.FragmentStorageNetworkBinding
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.serverdashboard.ui.alerts.AlertNotificationManager
import com.example.serverdashboard.ui.main.MainViewModel
import com.example.serverdashboard.ui.util.Formatters
import com.example.serverdashboard.ui.util.SpeedHysteresisTracker
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class StorageNetworkFragment : Fragment() {

    private var _binding: FragmentStorageNetworkBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private val fsAdapter = FilesystemAdapter()
    private val ifAdapter = NetworkInterfaceAdapter()
    private val activePortsAdapter = NetworkPortAdapter()
    private val inactivePortsAdapter = NetworkPortAdapter()
    private val firewallRulesAdapter = FirewallRulesAdapter()
    private lateinit var graphCarouselAdapter: GraphCarouselAdapter
    private val dailyUsageAdapter = DailyUsageAdapter()

    // Expandable state
    private var isInactivePortsExpanded = false
    private var isActivePortsExpanded = false
    private var isFirewallRulesExpanded = false
    private var isFilesystemsExpanded = false
    private var isInterfacesExpanded = false

    // Cached raw lists for expanding/collapsing
    private var currentActivePorts: List<PortItem> = emptyList()
    private var currentFilesystems: List<FilesystemMetric> = emptyList()
    private var currentInterfaces: List<NetworkInterface> = emptyList()

    // Data usage state & cache
    private val usageCache = mutableMapOf<String, NetworkUsageResponse>()
    private var selectedRangeDays = 30
    private var isMonthView = false
    private var selectedUsageInterface = "all"
    private var isSortByMostUsed = false
    private var isUsageListExpanded = false
    private var currentUsageResponse: NetworkUsageResponse? = null
    private var currentDisplayUsageItems: List<DailyUsageItem> = emptyList()

    // Speed hysteresis trackers
    private val netDownTracker = SpeedHysteresisTracker()
    private val netUpTracker = SpeedHysteresisTracker()
    private val diskReadTracker = SpeedHysteresisTracker()
    private val diskWriteTracker = SpeedHysteresisTracker()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStorageNetworkBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ctx = context ?: return

        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollStorageNetwork) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val basePad = (88 * resources.displayMetrics.density).toInt()
            v.updatePadding(bottom = basePad + systemBars.bottom)
            insets
        }

        // 1. Setup Sparklines styling
        binding.sparklineDiskRead.setLineColor(ContextCompat.getColor(ctx, R.color.metric_disk_read))
        binding.sparklineDiskWrite.setLineColor(ContextCompat.getColor(ctx, R.color.metric_disk_write))
        binding.sparklineNetDownload.setLineColor(ContextCompat.getColor(ctx, R.color.metric_network))
        binding.sparklineNetUpload.setLineColor(ContextCompat.getColor(ctx, R.color.metric_network))

        // 2. Setup Customizable Graphs Carousel
        val graphStore = GraphConfigStore.getInstance(ctx)
        graphCarouselAdapter = GraphCarouselAdapter { config, pos ->
            openGraphConfigSheet(config)
        }
        graphCarouselAdapter.updateConfigs(graphStore.getConfigs())

        binding.pagerCustomGraphs.adapter = graphCarouselAdapter
        binding.pagerCustomGraphs.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                val total = graphCarouselAdapter.itemCount
                binding.tvGraphCarouselIndicator.text = "${position + 1} / $total"
            }
        })
        binding.tvGraphCarouselIndicator.text = "1 / ${graphCarouselAdapter.itemCount}"

        binding.btnResetGraphs.setOnClickListener {
            val defaults = graphStore.resetToDefaults()
            graphCarouselAdapter.updateConfigs(defaults)
            binding.pagerCustomGraphs.setCurrentItem(0, true)
            binding.tvGraphCarouselIndicator.text = "1 / ${defaults.size}"
            Toast.makeText(ctx, "Metric graphs reset to defaults", Toast.LENGTH_SHORT).show()
        }

        // 3. Storage & Filesystems
        binding.recyclerFilesystems.layoutManager = LinearLayoutManager(ctx)
        binding.recyclerFilesystems.adapter = fsAdapter

        binding.layoutFilesystemsToggle.setOnClickListener {
            isFilesystemsExpanded = !isFilesystemsExpanded
            updateFilesystemsList()
        }

        // 4. Network Interfaces
        binding.recyclerNetworkInterfaces.layoutManager = LinearLayoutManager(ctx)
        binding.recyclerNetworkInterfaces.adapter = ifAdapter

        binding.layoutInterfacesToggle.setOnClickListener {
            isInterfacesExpanded = !isInterfacesExpanded
            updateInterfacesList()
        }

        // 5. Network Ports
        binding.recyclerActivePorts.layoutManager = LinearLayoutManager(ctx)
        binding.recyclerActivePorts.adapter = activePortsAdapter

        binding.layoutActivePortsToggle.setOnClickListener {
            isActivePortsExpanded = !isActivePortsExpanded
            updateActivePortsList()
        }

        binding.recyclerInactivePorts.layoutManager = LinearLayoutManager(ctx)
        binding.recyclerInactivePorts.adapter = inactivePortsAdapter

        // Collapsible Inactive Ports
        binding.layoutInactivePortsToggle.setOnClickListener {
            isInactivePortsExpanded = !isInactivePortsExpanded
            _binding?.layoutInactivePortsContainer?.visibility =
                if (isInactivePortsExpanded) View.VISIBLE else View.GONE
            val count = viewModel.networkPorts.value?.inactivePorts?.size ?: 0
            val arrow = if (isInactivePortsExpanded) "▲" else "▼"
            _binding?.tvInactivePortsToggleText?.text = "Common Service Ports (Inactive: $count) $arrow"
        }

        // 6. Firewall Rules
        binding.recyclerFirewallRules.layoutManager = LinearLayoutManager(ctx)
        binding.recyclerFirewallRules.adapter = firewallRulesAdapter

        binding.layoutFirewallRulesToggle.setOnClickListener {
            if (viewModel.firewallStatus.value?.rules == null) return@setOnClickListener
            isFirewallRulesExpanded = !isFirewallRulesExpanded
            _binding?.layoutFirewallRulesContainer?.visibility =
                if (isFirewallRulesExpanded) View.VISIBLE else View.GONE
            val rulesCount = viewModel.firewallStatus.value?.rules?.size ?: 0
            val arrow = if (isFirewallRulesExpanded) "▲" else "▼"
            _binding?.tvFirewallRulesToggleText?.text = "View Firewall Rules ($rulesCount) $arrow"
        }

        // 7. Ports & Firewall Refresh
        binding.btnRefreshPorts.setOnClickListener {
            viewModel.fetchNetworkPorts()
            viewModel.fetchFirewallStatus()
        }

        // 8. Network Service Restart
        binding.btnRestartNetworkService.setOnClickListener {
            showRestartConfirmationDialog()
        }

        // 9. Setup Data Usage UI
        setupDataUsageUI()

        // 10. Swipe to Refresh
        binding.swipeRefreshStorage.setColorSchemeResources(R.color.accent_blue)
        binding.swipeRefreshStorage.setOnRefreshListener {
            viewModel.fetchRestMetrics()
            viewModel.fetchNetworkPorts()
            viewModel.fetchFirewallStatus()
            usageCache.clear()
            fetchUsageInterfaces()
            fetchNetworkUsage(forceRefresh = true)
            binding.swipeRefreshStorage.postDelayed({
                _binding?.swipeRefreshStorage?.isRefreshing = false
            }, 800)
        }

        // Initial UI rendering
        updateFirewallUI(viewModel.firewallStatus.value)
        updatePortsUI(viewModel.networkPorts.value)

        // 11. Observers
        viewModel.systemOverview.observe(viewLifecycleOwner) { overview ->
            updateStorageUI(overview)
            refreshCarouselTelemetry()
        }

        viewModel.diskReadRate.observe(viewLifecycleOwner) { rate ->
            val b = _binding ?: return@observe
            b.tvDiskReadSpeed.text = if (rate != null) Formatters.formatSpeed(rate, tracker = diskReadTracker) else "Unavailable"
            refreshCarouselTelemetry()
        }

        viewModel.diskWriteRate.observe(viewLifecycleOwner) { rate ->
            val b = _binding ?: return@observe
            b.tvDiskWriteSpeed.text = if (rate != null) Formatters.formatSpeed(rate, tracker = diskWriteTracker) else "Unavailable"
            refreshCarouselTelemetry()
        }

        viewModel.netDownloadRate.observe(viewLifecycleOwner) { rate ->
            val b = _binding ?: return@observe
            val ctx = context ?: return@observe
            val useBits = NetworkConfigStore.getInstance(ctx).speedInBits
            val bps = rate ?: viewModel.systemOverview.value?.network?.download_speed_bps ?: 0L
            b.tvNetDownloadSpeed.text = Formatters.formatSpeed(bps, useBits, netDownTracker)
        }

        viewModel.netUploadRate.observe(viewLifecycleOwner) { rate ->
            val b = _binding ?: return@observe
            val ctx = context ?: return@observe
            val useBits = NetworkConfigStore.getInstance(ctx).speedInBits
            val bps = rate ?: viewModel.systemOverview.value?.network?.upload_speed_bps ?: 0L
            b.tvNetUploadSpeed.text = Formatters.formatSpeed(bps, useBits, netUpTracker)
        }

        viewModel.diskReadHistory.observe(viewLifecycleOwner) { hist ->
            val b = _binding ?: return@observe
            if (hist.isNotEmpty()) {
                val maxSample = (hist.maxOrNull() ?: 1024f).coerceAtLeast(1024f)
                b.sparklineDiskRead.visibility = View.VISIBLE
                b.sparklineDiskRead.setMaxCapacity(maxSample)
                b.sparklineDiskRead.setData(hist)
            } else {
                b.sparklineDiskRead.visibility = View.GONE
            }
            refreshCarouselTelemetry()
        }

        viewModel.diskWriteHistory.observe(viewLifecycleOwner) { hist ->
            val b = _binding ?: return@observe
            if (hist.isNotEmpty()) {
                val maxSample = (hist.maxOrNull() ?: 1024f).coerceAtLeast(1024f)
                b.sparklineDiskWrite.visibility = View.VISIBLE
                b.sparklineDiskWrite.setMaxCapacity(maxSample)
                b.sparklineDiskWrite.setData(hist)
            } else {
                b.sparklineDiskWrite.visibility = View.GONE
            }
            refreshCarouselTelemetry()
        }

        viewModel.netDownloadHistory.observe(viewLifecycleOwner) { hist ->
            val maxSample = (hist.maxOrNull() ?: 1024f).coerceAtLeast(1024f)
            binding.sparklineNetDownload.setMaxCapacity(maxSample)
            binding.sparklineNetDownload.setData(hist)
            refreshCarouselTelemetry()
        }

        viewModel.netUploadHistory.observe(viewLifecycleOwner) { hist ->
            val maxSample = (hist.maxOrNull() ?: 1024f).coerceAtLeast(1024f)
            binding.sparklineNetUpload.setMaxCapacity(maxSample)
            binding.sparklineNetUpload.setData(hist)
            refreshCarouselTelemetry()
        }

        viewModel.cpuHistory.observe(viewLifecycleOwner) {
            refreshCarouselTelemetry()
        }

        viewModel.ramHistory.observe(viewLifecycleOwner) {
            refreshCarouselTelemetry()
        }

        viewModel.storageHistory.observe(viewLifecycleOwner) {
            refreshCarouselTelemetry()
        }

        viewModel.networkPorts.observe(viewLifecycleOwner) { ports ->
            updatePortsUI(ports)
        }

        viewModel.firewallStatus.observe(viewLifecycleOwner) { fw ->
            updateFirewallUI(fw)
        }

        viewModel.isLoadingPorts.observe(viewLifecycleOwner) { loading ->
            _binding?.progressLoadingPorts?.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.isLoadingFirewall.observe(viewLifecycleOwner) { loading ->
            if (viewModel.firewallStatus.value == null) {
                updateFirewallUI(null)
            }
        }

        viewModel.networkActionInProgress.observe(viewLifecycleOwner) { inProgress ->
            val b = _binding ?: return@observe
            b.btnRestartNetworkService.isEnabled = !inProgress
            b.layoutNetworkRestartProgress.visibility = if (inProgress) View.VISIBLE else View.GONE
            if (inProgress) {
                b.tvNetworkRestartStatus.text = "Restarting network service..."
            }
        }
    }

    private fun openGraphConfigSheet(config: GraphConfig) {
        val ctx = context ?: return
        val sheet = GraphConfigBottomSheet(
            currentConfig = config,
            onConfigSaved = { updated ->
                GraphConfigStore.getInstance(ctx).updateConfig(updated)
                graphCarouselAdapter.updateConfigs(GraphConfigStore.getInstance(ctx).getConfigs())
                refreshCarouselTelemetry()
            },
            onResetToDefaults = {
                val defs = GraphConfigStore.getInstance(ctx).resetToDefaults()
                graphCarouselAdapter.updateConfigs(defs)
                binding.pagerCustomGraphs.setCurrentItem(0, true)
                binding.tvGraphCarouselIndicator.text = "1 / ${defs.size}"
                refreshCarouselTelemetry()
            }
        )
        sheet.show(parentFragmentManager, GraphConfigBottomSheet.TAG)
    }

    private fun refreshCarouselTelemetry() {
        graphCarouselAdapter.updateTelemetry(
            newOverview = viewModel.systemOverview.value,
            cpuHist = viewModel.cpuHistory.value ?: emptyList(),
            ramHist = viewModel.ramHistory.value ?: emptyList(),
            diskReadHist = viewModel.diskReadHistory.value ?: emptyList(),
            diskWriteHist = viewModel.diskWriteHistory.value ?: emptyList(),
            netDownHist = viewModel.netDownloadHistory.value ?: emptyList(),
            netUpHist = viewModel.netUploadHistory.value ?: emptyList(),
            storageHist = viewModel.storageHistory.value ?: emptyList(),
            diskReadRate = viewModel.diskReadRate.value,
            diskWriteRate = viewModel.diskWriteRate.value
        )
    }

    private fun showRestartConfirmationDialog() {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle("Restart Network Service")
            .setMessage("Restart the server's network connection? Running applications and this dashboard may momentarily disconnect while network interfaces bounce.")
            .setPositiveButton("Restart") { _, _ ->
                viewModel.restartNetworkService { success, msg ->
                    context?.let { c ->
                        Toast.makeText(
                            c,
                            msg,
                            if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateStorageUI(data: SystemOverview) {
        val b = _binding ?: return
        val ctx = context ?: return
        val netConfig = NetworkConfigStore.getInstance(ctx)
        val useBits = netConfig.speedInBits

        // Disk I/O Speeds
        val readRate = viewModel.diskReadRate.value ?: data.storage.calculatedReadBps ?: data.storage.precalculatedReadRate
        val writeRate = viewModel.diskWriteRate.value ?: data.storage.calculatedWriteBps ?: data.storage.precalculatedWriteRate
        b.tvDiskReadSpeed.text = if (readRate != null) Formatters.formatSpeed(readRate, tracker = diskReadTracker) else "Unavailable"
        b.tvDiskWriteSpeed.text = if (writeRate != null) Formatters.formatSpeed(writeRate, tracker = diskWriteTracker) else "Unavailable"

        // Network I/O Speeds
        val downBps = data.network.download_speed_bps
        val upBps = data.network.upload_speed_bps
        b.tvNetDownloadSpeed.text = Formatters.formatSpeed(downBps, useBits, netDownTracker)
        b.tvNetUploadSpeed.text = Formatters.formatSpeed(upBps, useBits, netUpTracker)

        // Filesystems
        currentFilesystems = data.storage.filesystems ?: emptyList()
        b.tvEmptyFilesystems.visibility = if (currentFilesystems.isEmpty()) View.VISIBLE else View.GONE
        updateFilesystemsList()

        // Network interfaces
        currentInterfaces = data.network.interfaces ?: emptyList()
        b.tvEmptyNetwork.visibility = if (currentInterfaces.isEmpty()) View.VISIBLE else View.GONE
        updateInterfacesList()
    }

    private fun updateFilesystemsList() {
        val b = _binding ?: return
        if (currentFilesystems.size > 2) {
            b.layoutFilesystemsToggle.visibility = View.VISIBLE
            val displayList = if (isFilesystemsExpanded) currentFilesystems else currentFilesystems.take(2)
            fsAdapter.updateData(displayList)
            b.tvFilesystemsToggleText.text = if (isFilesystemsExpanded) {
                "Show fewer drives ▲"
            } else {
                "Show all ${currentFilesystems.size} drives ▼"
            }
        } else {
            b.layoutFilesystemsToggle.visibility = View.GONE
            fsAdapter.updateData(currentFilesystems)
        }
    }

    private fun updateInterfacesList() {
        val b = _binding ?: return
        if (currentInterfaces.size > 2) {
            b.layoutInterfacesToggle.visibility = View.VISIBLE
            val displayList = if (isInterfacesExpanded) currentInterfaces else currentInterfaces.take(2)
            ifAdapter.updateData(displayList)
            b.tvInterfacesToggleText.text = if (isInterfacesExpanded) {
                "Show fewer interfaces ▲"
            } else {
                "Show all ${currentInterfaces.size} interfaces ▼"
            }
        } else {
            b.layoutInterfacesToggle.visibility = View.GONE
            ifAdapter.updateData(currentInterfaces)
        }
    }

    private fun updatePortsUI(ports: NetworkPortsResponse?) {
        val b = _binding ?: return
        if (ports == null) {
            b.tvEmptyActivePorts.visibility = View.VISIBLE
            b.tvEmptyActivePorts.text = "Port data unavailable"
            currentActivePorts = emptyList()
            activePortsAdapter.updateData(emptyList())
            inactivePortsAdapter.updateData(emptyList())
            b.layoutActivePortsToggle.visibility = View.GONE
            val arrow = if (isInactivePortsExpanded) "▲" else "▼"
            b.tvInactivePortsToggleText.text = "Common service ports (Inactive: 0) $arrow"
            return
        }
        currentActivePorts = ports.activePorts ?: emptyList()
        val inactive = ports.inactivePorts ?: emptyList()

        b.tvEmptyActivePorts.visibility = if (currentActivePorts.isEmpty()) View.VISIBLE else View.GONE
        b.tvEmptyActivePorts.text = "No open listening ports detected"

        updateActivePortsList()
        inactivePortsAdapter.updateData(inactive)

        val arrow = if (isInactivePortsExpanded) "▲" else "▼"
        b.tvInactivePortsToggleText.text = "Common service ports (Inactive: ${inactive.size}) $arrow"
    }

    private fun updateActivePortsList() {
        val b = _binding ?: return
        if (currentActivePorts.size > 3) {
            b.layoutActivePortsToggle.visibility = View.VISIBLE
            val displayList = if (isActivePortsExpanded) currentActivePorts else currentActivePorts.take(3)
            activePortsAdapter.updateData(displayList)
            b.tvActivePortsToggleText.text = if (isActivePortsExpanded) {
                "Show fewer ports ▲"
            } else {
                "Show all ${currentActivePorts.size} active ports ▼"
            }
        } else {
            b.layoutActivePortsToggle.visibility = View.GONE
            activePortsAdapter.updateData(currentActivePorts)
        }
    }

    private fun updateFirewallUI(fw: FirewallResponse?) {
        val b = _binding ?: return
        val ctx = context ?: return

        if (fw == null) {
            val isLoading = viewModel.isLoadingFirewall.value == true
            b.tvFirewallStatusBadge.text = if (isLoading) "Checking..." else "Unavailable"
            b.tvFirewallStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.label_tertiary))
            b.tvFirewallStatusBadge.setBackgroundResource(R.drawable.badge_stopped)
            b.cardFirewallWarning.visibility = View.GONE
            b.tvFirewallImplementation.text = if (isLoading) "Checking..." else "Unavailable"
            b.tvFirewallPolicy.text = if (isLoading) "Checking..." else "Unavailable"
            b.tvFirewallRulesSummary.text = if (isLoading) "Checking..." else "Unavailable"
            b.tvFirewallLastChecked.text = if (isLoading) "Checking..." else "Data unavailable"
            b.layoutFirewallRulesToggle.isEnabled = false
            b.tvFirewallRulesToggleText.text = "Firewall rules unavailable"
            b.tvEmptyFirewallRules.visibility = View.GONE
            b.layoutFirewallRulesContainer.visibility = View.GONE
            firewallRulesAdapter.updateData(emptyList())
            return
        }

        val impl = fw.implementation.lowercase(Locale.US)
        val implName = when (impl) {
            "ufw" -> "UFW (Uncomplicated Firewall)"
            "firewalld" -> "firewalld (RHEL/CentOS/Fedora)"
            "iptables" -> "iptables (Kernel Packet Filter)"
            "nftables" -> "nftables (Modern Packet Filter)"
            "none", "not detected", "unknown" -> "None / Not Detected"
            else -> fw.implementation.ifEmpty { "None / Not Detected" }
        }
        b.tvFirewallImplementation.text = implName

        // NEVER display Active unless verified by an identified implementation!
        when {
            fw.isConfirmedActive -> {
                b.tvFirewallStatusBadge.text = "Active"
                b.tvFirewallStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.success))
                b.tvFirewallStatusBadge.setBackgroundResource(R.drawable.badge_running)

                val isAllowAll = fw.defaultIncoming?.contains("allow", ignoreCase = true) == true
                if (isAllowAll) {
                    b.cardFirewallWarning.visibility = View.VISIBLE
                    b.tvFirewallWarningDetails.text = "Inbound policy ALLOW. Open ports accept traffic."
                } else {
                    b.cardFirewallWarning.visibility = View.GONE
                }
            }
            fw.isConfirmedInactive -> {
                b.tvFirewallStatusBadge.text = "Inactive"
                b.tvFirewallStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.warning))
                b.tvFirewallStatusBadge.setBackgroundResource(R.drawable.badge_warning)
                b.cardFirewallWarning.visibility = View.VISIBLE
                b.tvFirewallWarningDetails.text = "Firewall inactive. Ports may be exposed."
            }
            fw.hasContradictoryState || !fw.isIdentified -> {
                // If status was claimed active but implementation is None/Not Detected, or implementation is not detected
                b.tvFirewallStatusBadge.text = "Not detected"
                b.tvFirewallStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.label_tertiary))
                b.tvFirewallStatusBadge.setBackgroundResource(R.drawable.badge_stopped)
                b.cardFirewallWarning.visibility = View.VISIBLE
                b.tvFirewallWarningDetails.text = "No firewall detected."
            }
            else -> {
                b.tvFirewallStatusBadge.text = fw.status.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
                b.tvFirewallStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.label_tertiary))
                b.tvFirewallStatusBadge.setBackgroundResource(R.drawable.badge_stopped)
                b.cardFirewallWarning.visibility = View.VISIBLE
                b.tvFirewallWarningDetails.text = "State undetermined."
            }
        }

        // Policy display using effectivePolicyDisplay
        b.tvFirewallPolicy.text = fw.effectivePolicyDisplay

        // Rules display: distinguish rules == null from rules == []
        if (fw.rules == null) {
            b.tvFirewallRulesSummary.text = "Rules unavailable"
            b.layoutFirewallRulesToggle.isEnabled = false
            b.tvFirewallRulesToggleText.text = "Firewall rules unavailable"
            b.tvEmptyFirewallRules.visibility = View.GONE
            b.layoutFirewallRulesContainer.visibility = View.GONE
            firewallRulesAdapter.updateData(emptyList())
        } else {
            b.layoutFirewallRulesToggle.isEnabled = true
            val rulesList = fw.rules
            b.tvFirewallRulesSummary.text = when {
                rulesList.isEmpty() -> "No rules defined"
                rulesList.size == 1 -> "1 active rule"
                else -> "${rulesList.size} active rules"
            }
            val arrow = if (isFirewallRulesExpanded) "▲" else "▼"
            b.tvFirewallRulesToggleText.text = "View firewall rules (${rulesList.size}) $arrow"
            b.tvEmptyFirewallRules.text = "No rules defined"
            b.tvEmptyFirewallRules.visibility = if (rulesList.isEmpty()) View.VISIBLE else View.GONE
            b.layoutFirewallRulesContainer.visibility = if (isFirewallRulesExpanded) View.VISIBLE else View.GONE
            firewallRulesAdapter.updateData(rulesList)
        }

        b.tvFirewallLastChecked.text = if (fw.lastChecked > 0L) {
            Formatters.formatEpochTime(fw.lastChecked)
        } else {
            "Just now"
        }
    }

    // ==========================================
    // Data Usage UI, Cache, and Calculations
    // ==========================================
    private fun setupDataUsageUI() {
        val b = _binding ?: return
        val ctx = context ?: return

        // 1. By Date RecyclerView
        b.rvDailyUsage.layoutManager = LinearLayoutManager(ctx)
        b.rvDailyUsage.adapter = dailyUsageAdapter

        // 2. Segmented Range Control
        b.segmentedUsageRange.check(R.id.btnUsage30d)
        selectedRangeDays = 30
        isMonthView = false

        b.segmentedUsageRange.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnUsage7d -> {
                        selectedRangeDays = 7
                        isMonthView = false
                        b.tvUsageAverageLabel.text = "Daily average"
                        fetchNetworkUsage()
                    }
                    R.id.btnUsage30d -> {
                        selectedRangeDays = 30
                        isMonthView = false
                        b.tvUsageAverageLabel.text = "Daily average"
                        fetchNetworkUsage()
                    }
                    R.id.btnUsage90d -> {
                        selectedRangeDays = 90
                        isMonthView = false
                        b.tvUsageAverageLabel.text = "Daily average"
                        fetchNetworkUsage()
                    }
                    R.id.btnUsageMonth -> {
                        isMonthView = true
                        selectedRangeDays = 90
                        b.tvUsageAverageLabel.text = "Monthly average"
                        fetchNetworkUsage()
                    }
                }
            }
        }

        // 3. Sort Toggle (Date vs Heaviest)
        b.btnUsageSortToggle.setOnClickListener {
            isSortByMostUsed = !isSortByMostUsed
            b.btnUsageSortToggle.text = if (isSortByMostUsed) "Sort: Heaviest ▼" else "Sort: Date ▼"
            renderUsageList(currentDisplayUsageItems)
        }

        // 4. Expand / Show All Days
        b.layoutUsageListToggle.setOnClickListener {
            isUsageListExpanded = !isUsageListExpanded
            renderUsageList(currentDisplayUsageItems)
        }

        // 5. Retry Button
        b.btnUsageRetry.setOnClickListener {
            fetchNetworkUsage(forceRefresh = true)
        }

        // 6. Share CSV Export
        b.btnShareUsageCsv.setOnClickListener {
            shareUsageCsv()
        }

        // 7. Initial Fetch
        fetchUsageInterfaces()
        fetchNetworkUsage(forceRefresh = false)
    }

    private fun fetchUsageInterfaces() {
        if (viewModel.serverUrl.isBlank() || viewModel.authToken.isBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = ApiClient.instance.getNetworkUsageInterfaces(
                viewModel.serverUrl,
                viewModel.authToken
            )
            if (result.isSuccess) {
                val list = result.getOrNull() ?: emptyList()
                populateUsageInterfaceChips(list)
            } else {
                populateUsageInterfaceChips(emptyList())
            }
        }
    }

    private fun populateUsageInterfaceChips(interfaces: List<String>) {
        val b = _binding ?: return
        val ctx = context ?: return
        b.chipGroupUsageInterfaces.removeAllViews()

        val fullList = listOf("all") + interfaces.filter { it.isNotBlank() && it.lowercase() != "all" }
        for (iface in fullList) {
            val (friendly, raw) = getFriendlyInterfaceName(iface)
            val chip = com.google.android.material.chip.Chip(ctx).apply {
                isCheckable = true
                text = if (raw != null) "$friendly ($raw)" else friendly
                tag = iface
                isChecked = (iface == selectedUsageInterface)
                setChipBackgroundColorResource(R.color.surface_1)
                setTextColor(ContextCompat.getColor(ctx, R.color.label_primary))
                chipStrokeColor = ContextCompat.getColorStateList(ctx, R.color.separator)
                chipStrokeWidth = 1f * resources.displayMetrics.density
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked && selectedUsageInterface != iface) {
                        selectedUsageInterface = iface
                        fetchNetworkUsage(forceRefresh = false)
                    }
                }
            }
            b.chipGroupUsageInterfaces.addView(chip)
        }
    }

    private fun fetchNetworkUsage(forceRefresh: Boolean = false) {
        val b = _binding ?: return
        if (viewModel.serverUrl.isBlank() || viewModel.authToken.isBlank()) return

        val daysParam = if (isMonthView) 90 else selectedRangeDays
        val cacheKey = "$daysParam-$selectedUsageInterface"

        if (!forceRefresh && usageCache.containsKey(cacheKey)) {
            val cached = usageCache[cacheKey]!!
            renderUsageUI(cached)
            return
        }

        // Loading state
        b.layoutUsageLoading.visibility = View.VISIBLE
        b.layoutUsageError.visibility = View.GONE
        b.tvUsageEmpty.visibility = View.GONE
        b.layoutUsageContent.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val result = ApiClient.instance.getNetworkUsage(
                viewModel.serverUrl,
                viewModel.authToken,
                daysParam,
                selectedUsageInterface
            )

            val safeB = _binding ?: return@launch
            safeB.layoutUsageLoading.visibility = View.GONE

            if (result.isSuccess) {
                val resp = result.getOrNull() ?: NetworkUsageResponse()
                usageCache[cacheKey] = resp
                renderUsageUI(resp)
            } else {
                val err = result.exceptionOrNull()
                safeB.layoutUsageError.visibility = View.VISIBLE
                safeB.layoutUsageContent.visibility = View.GONE
                safeB.tvUsageEmpty.visibility = View.GONE

                if (err is ServerOutdatedException) {
                    safeB.tvUsageErrorMessage.text = "Update server to see usage"
                    safeB.btnUsageRetry.text = "Check again"
                } else {
                    safeB.tvUsageErrorMessage.text = "Failed to load usage data"
                    safeB.btnUsageRetry.text = "Retry"
                }
            }
        }
    }

    private fun renderUsageUI(response: NetworkUsageResponse) {
        val b = _binding ?: return
        currentUsageResponse = response

        b.layoutUsageLoading.visibility = View.GONE
        b.layoutUsageError.visibility = View.GONE

        if (response.days.isEmpty()) {
            b.tvUsageEmpty.visibility = View.VISIBLE
            val sinceStr = response.trackingSince?.let { Formatters.formatUsageDate(it, response.timezone) } ?: "--"
            b.tvUsageEmpty.text = "No data yet (tracking since $sinceStr)"
            b.layoutUsageContent.visibility = View.GONE
            b.layoutCapWarningPill.visibility = View.GONE
            return
        }

        b.tvUsageEmpty.visibility = View.GONE
        b.layoutUsageContent.visibility = View.VISIBLE

        if (isMonthView) {
            renderMonthView(response)
        } else {
            renderDailyView(response)
        }

        checkMonthlyDataCap(response)
    }

    private fun renderDailyView(response: NetworkUsageResponse) {
        val b = _binding ?: return
        val totalBytes = response.totals.total
        val rxBytes = response.totals.rx
        val txBytes = response.totals.tx

        b.tvUsageHeroTotal.text = Formatters.formatBytes(totalBytes)
        b.tvUsageHeroSubline.text = "↓ ${Formatters.formatBytes(rxBytes)}  •  ↑ ${Formatters.formatBytes(txBytes)}"

        val peakDate = response.peak?.date ?: response.days.maxByOrNull { it.total }?.date
        val peakTotal = response.peak?.total ?: response.days.maxByOrNull { it.total }?.total ?: 0L
        if (!peakDate.isNullOrEmpty() && peakTotal > 0L) {
            val peakDateFormatted = Formatters.formatUsageDate(peakDate, response.timezone)
            b.tvUsagePeakDay.text = "$peakDateFormatted (${Formatters.formatBytes(peakTotal)})"
        } else {
            b.tvUsagePeakDay.text = "--"
        }

        val dayCount = response.days.size
        val avgBytes = if (dayCount > 0) totalBytes / dayCount else 0L
        b.tvUsageAverageLabel.text = "Daily average"
        b.tvUsageDailyAverage.text = "${Formatters.formatBytes(avgBytes)} / day"

        b.tvUsageListHeader.text = "By date"

        b.chartDailyUsage.setData(response.days, peakDate, response.timezone)
        renderUsageList(response.days)
    }

    private fun renderMonthView(response: NetworkUsageResponse) {
        val b = _binding ?: return

        // Group daily items by month prefix "yyyy-MM"
        val monthlyMap = response.days.groupBy {
            if (it.date.length >= 7) it.date.substring(0, 7) else it.date
        }
        val monthlyItems = monthlyMap.map { (monthStr, days) ->
            val sumRx = days.sumOf { it.rx }
            val sumTx = days.sumOf { it.tx }
            val sumTotal = days.sumOf { it.total }
            DailyUsageItem(date = monthStr, rxBytes = sumRx, txBytes = sumTx, totalBytes = sumTotal)
        }.sortedBy { it.date }

        val totalBytes = monthlyItems.sumOf { it.total }
        val rxBytes = monthlyItems.sumOf { it.rx }
        val txBytes = monthlyItems.sumOf { it.tx }

        b.tvUsageHeroTotal.text = Formatters.formatBytes(totalBytes)
        b.tvUsageHeroSubline.text = "↓ ${Formatters.formatBytes(rxBytes)}  •  ↑ ${Formatters.formatBytes(txBytes)}"

        val peakMonth = monthlyItems.maxByOrNull { it.total }
        if (peakMonth != null && peakMonth.total > 0L) {
            b.tvUsagePeakDay.text = "${peakMonth.date} (${Formatters.formatBytes(peakMonth.total)})"
        } else {
            b.tvUsagePeakDay.text = "--"
        }

        val monthCount = monthlyItems.size
        val avgBytes = if (monthCount > 0) totalBytes / monthCount else 0L
        b.tvUsageAverageLabel.text = "Monthly average"
        b.tvUsageDailyAverage.text = "${Formatters.formatBytes(avgBytes)} / mo"

        b.tvUsageListHeader.text = "By month"

        b.chartDailyUsage.setData(monthlyItems, peakMonth?.date, response.timezone)
        renderUsageList(monthlyItems)
    }

    private fun renderUsageList(items: List<DailyUsageItem> = currentDisplayUsageItems) {
        val b = _binding ?: return
        currentDisplayUsageItems = items
        if (items.isEmpty()) return

        // Rank top 3 heaviest days/months
        val top3 = items.sortedByDescending { it.total }.take(3)
        val rankMap = top3.mapIndexed { idx, itm -> itm.date to (idx + 1) }.toMap()

        val sorted = if (isSortByMostUsed) {
            items.sortedByDescending { it.total }
        } else {
            items.sortedByDescending { it.date }
        }

        if (sorted.size > 7) {
            b.layoutUsageListToggle.visibility = View.VISIBLE
            val displayList = if (isUsageListExpanded) sorted else sorted.take(7)
            dailyUsageAdapter.updateData(displayList, currentUsageResponse?.timezone, rankMap)
            b.tvUsageListToggleText.text = if (isUsageListExpanded) {
                "Show fewer ▲"
            } else {
                "Show all ${sorted.size} entries ▼"
            }
        } else {
            b.layoutUsageListToggle.visibility = View.GONE
            dailyUsageAdapter.updateData(sorted, currentUsageResponse?.timezone, rankMap)
        }
    }

    private fun checkMonthlyDataCap(response: NetworkUsageResponse) {
        val b = _binding ?: return
        val ctx = context ?: return
        val netConfig = NetworkConfigStore.getInstance(ctx)

        if (!netConfig.dataCapEnabled) {
            b.layoutCapWarningPill.visibility = View.GONE
            return
        }

        val cycleStart = netConfig.getCurrentCycleStartDate()
        val cycleDays = response.days.filter { it.date >= cycleStart }
        val usedBytes = cycleDays.sumOf { it.total }
        val capBytes = netConfig.dataCapBytes

        if (capBytes <= 0L) {
            b.layoutCapWarningPill.visibility = View.GONE
            return
        }

        val percentage = (usedBytes.toDouble() / capBytes.toDouble() * 100.0).toFloat()
        if (percentage.isFinite() && percentage >= 80f) {
            b.layoutCapWarningPill.visibility = View.VISIBLE
            b.tvCapWarningText.text = String.format(
                Locale.US,
                "⚠ %.1f%% of monthly cap used (%s / %s)",
                percentage,
                Formatters.formatBytes(usedBytes),
                Formatters.formatBytes(capBytes)
            )

            val cycleKey = "${cycleStart}_80"
            if (netConfig.lastWarnedCycleKey != cycleKey) {
                netConfig.lastWarnedCycleKey = cycleKey
                AlertNotificationManager.getInstance(ctx).notifyDataCapWarning(
                    viewModel.serverUrl,
                    percentage,
                    usedBytes,
                    capBytes
                )
            }
        } else {
            b.layoutCapWarningPill.visibility = View.GONE
        }
    }

    private fun shareUsageCsv() {
        val ctx = context ?: return
        val items = currentDisplayUsageItems
        if (items.isEmpty()) {
            Toast.makeText(ctx, "No usage data to export", Toast.LENGTH_SHORT).show()
            return
        }

        val sb = StringBuilder()
        sb.append("Date,Download Bytes,Upload Bytes,Total Bytes,Download Formatted,Upload Formatted,Total Formatted\n")
        for (item in items) {
            sb.append("${item.date},${item.rx},${item.tx},${item.total},${Formatters.formatBytes(item.rx)},${Formatters.formatBytes(item.tx)},${Formatters.formatBytes(item.total)}\n")
        }
        val csvData = sb.toString()

        try {
            val fileName = "zentra_network_usage_${selectedRangeDays}d_${selectedUsageInterface}.csv"
            val file = File(ctx.cacheDir, fileName)
            file.writeText(csvData)

            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, "Zentra Server Data Usage Export")
                putExtra(Intent.EXTRA_TEXT, csvData)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(sendIntent, "Share Data Usage CSV"))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Failed to share CSV: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getFriendlyInterfaceName(rawName: String): Pair<String, String?> {
        val lower = rawName.lowercase()
        return when {
            lower == "all" -> Pair("All", null)
            lower.startsWith("wlan") || lower.startsWith("wlp") || lower.startsWith("wifi") -> Pair("Wi-Fi", rawName)
            lower.startsWith("eth") || lower.startsWith("en") -> Pair("Ethernet", rawName)
            lower.startsWith("tailscale") || lower.startsWith("wg") || lower.startsWith("tun") -> Pair("VPN", rawName)
            lower.startsWith("docker") || lower.startsWith("br-") || lower.startsWith("veth") -> Pair("Bridge", rawName)
            else -> Pair(rawName, null)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
