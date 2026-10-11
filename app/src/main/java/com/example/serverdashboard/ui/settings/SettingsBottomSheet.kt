package com.example.serverdashboard.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.serverdashboard.MainActivity
import com.example.serverdashboard.data.api.ApiClient
import com.example.serverdashboard.data.storage.SecureTokenManager
import com.example.serverdashboard.data.storage.ServerStore
import com.example.serverdashboard.databinding.BottomSheetSettingsBinding
import com.example.serverdashboard.ui.connect.ServerConnectActivity
import com.example.serverdashboard.ui.main.MainViewModel
import com.example.serverdashboard.ui.util.Formatters
import com.example.serverdashboard.ui.util.WakeOnLanUtil
import com.example.serverdashboard.R
import com.example.serverdashboard.ui.theme.AppTheme
import com.example.serverdashboard.ui.theme.ThemeManager
import com.example.serverdashboard.ui.util.GlassEffectHelper
import com.example.serverdashboard.ui.util.ServerNameResolver
import androidx.core.content.ContextCompat
import com.example.serverdashboard.data.storage.NotificationConfigStore
import com.example.serverdashboard.ui.alerts.AlertNotificationManager
import com.example.serverdashboard.ui.alerts.LiveMonitoringService
import com.example.serverdashboard.ui.alerts.NotificationPermissionHelper
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

class SettingsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            GlassEffectHelper.applyWindowBlur(window, 36)
        }
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }

    override fun onResume() {
        super.onResume()
        val ctx = context ?: return
        val b = _binding ?: return
        val hasPerm = NotificationPermissionHelper.areNotificationsEnabled(ctx)
        if (hasPerm) {
            b.tvNotifPermissionStatus.text = "Notifications: Active • Background checks running"
            b.tvNotifPermissionStatus.setTextColor(ContextCompat.getColor(ctx, R.color.label_secondary))
            b.ivNotifStatusChevron.visibility = View.GONE
            b.layoutNotifPermissionStatus.isClickable = false
        } else {
            b.tvNotifPermissionStatus.text = "Notifications disabled in system settings (Tap to enable)"
            b.tvNotifPermissionStatus.setTextColor(ContextCompat.getColor(ctx, R.color.warning))
            b.ivNotifStatusChevron.visibility = View.VISIBLE
            b.layoutNotifPermissionStatus.isClickable = true
            b.layoutNotifPermissionStatus.setOnClickListener {
                NotificationPermissionHelper.openNotificationSettings(ctx)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Server Section (Section 19)
        val host = ServerNameResolver.resolveServerName(
            requireContext(),
            viewModel.serverUrl,
            viewModel.systemOverview.value?.resolvedHost?.hostname
        )
        binding.tvSettingsHostName.text = host
        val cleanUrl = Formatters.extractHost(viewModel.serverUrl)
        binding.tvSettingsServerUrl.text = cleanUrl

        val isRemote = cleanUrl.contains("100.")
        binding.tvSettingsConnectionType.text = if (isRemote) "Remote network connection" else "Local network connection"

        // Fetch user info
        viewLifecycleOwner.lifecycleScope.launch {
            val meResult = ApiClient.instance.getMe(viewModel.serverUrl, viewModel.authToken)
            val b = _binding ?: return@launch
            if (meResult.isSuccess) {
                val user = meResult.getOrNull()
                b.tvSettingsUser.text = "Logged in as: ${user?.username ?: "admin"}"
            }
        }

        val ctx = context ?: return

        // 2. Appearance Theme Selection
        val savedTheme = ThemeManager.getSavedTheme(ctx)
        when (savedTheme) {
            AppTheme.DARK -> binding.toggleThemeGroup.check(R.id.btnThemeDark)
            AppTheme.LIGHT -> binding.toggleThemeGroup.check(R.id.btnThemeLight)
            AppTheme.SYSTEM -> binding.toggleThemeGroup.check(R.id.btnThemeSystem)
        }

        binding.toggleThemeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newTheme = when (checkedId) {
                    R.id.btnThemeDark -> AppTheme.DARK
                    R.id.btnThemeLight -> AppTheme.LIGHT
                    R.id.btnThemeSystem -> AppTheme.SYSTEM
                    else -> AppTheme.SYSTEM
                }
                if (newTheme != ThemeManager.getSavedTheme(ctx)) {
                    ThemeManager.setTheme(ctx, newTheme)
                }
            }
        }

        // 3. Notifications Configuration
        val configStore = NotificationConfigStore.getInstance(ctx)

        fun updatePermissionStatus() {
            val b = _binding ?: return
            val hasPerm = NotificationPermissionHelper.areNotificationsEnabled(ctx)
            if (hasPerm) {
                b.tvNotifPermissionStatus.text = "Notifications: Active • Background checks running"
                b.tvNotifPermissionStatus.setTextColor(ContextCompat.getColor(ctx, R.color.label_secondary))
                b.ivNotifStatusChevron.visibility = View.GONE
                b.layoutNotifPermissionStatus.isClickable = false
            } else {
                b.tvNotifPermissionStatus.text = "Notifications disabled in system settings (Tap to enable)"
                b.tvNotifPermissionStatus.setTextColor(ContextCompat.getColor(ctx, R.color.warning))
                b.ivNotifStatusChevron.visibility = View.VISIBLE
                b.layoutNotifPermissionStatus.isClickable = true
                b.layoutNotifPermissionStatus.setOnClickListener {
                    NotificationPermissionHelper.openNotificationSettings(ctx)
                }
            }
        }
        updatePermissionStatus()

        // Master toggle
        binding.switchNotifMaster.isChecked = configStore.notificationsEnabled
        binding.layoutNotifDetails.visibility = if (configStore.notificationsEnabled) View.VISIBLE else View.GONE
        binding.switchNotifMaster.setOnCheckedChangeListener { _, isChecked ->
            configStore.notificationsEnabled = isChecked
            _binding?.layoutNotifDetails?.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Category toggles
        binding.switchNotifTemp.isChecked = configStore.notifyTemperature
        binding.switchNotifTemp.setOnCheckedChangeListener { _, isChecked ->
            configStore.notifyTemperature = isChecked
        }

        binding.switchNotifLoad.isChecked = configStore.notifyLoad
        binding.switchNotifLoad.setOnCheckedChangeListener { _, isChecked ->
            configStore.notifyLoad = isChecked
        }

        binding.switchNotifDisk.isChecked = configStore.notifyDisk
        binding.switchNotifDisk.setOnCheckedChangeListener { _, isChecked ->
            configStore.notifyDisk = isChecked
        }

        binding.switchNotifConn.isChecked = configStore.notifyConnection
        binding.switchNotifConn.setOnCheckedChangeListener { _, isChecked ->
            configStore.notifyConnection = isChecked
        }

        // Live monitoring
        binding.switchLiveMonitoring.isChecked = configStore.liveMonitoringEnabled
        binding.switchLiveMonitoring.setOnCheckedChangeListener { _, isChecked ->
            configStore.liveMonitoringEnabled = isChecked
            if (isChecked) {
                LiveMonitoringService.start(ctx)
            } else {
                LiveMonitoringService.stop(ctx)
            }
        }

        // Quiet hours
        binding.switchQuietHours.isChecked = configStore.quietHoursEnabled
        binding.switchQuietHours.setOnCheckedChangeListener { _, isChecked ->
            configStore.quietHoursEnabled = isChecked
        }

        // Battery optimization
        binding.btnAllowBackgroundActivity.setOnClickListener {
            NotificationPermissionHelper.openBatteryOptimizationSettings(ctx)
        }

        // Test notification
        binding.btnSendTestNotification.setOnClickListener {
            if (!NotificationPermissionHelper.areNotificationsEnabled(ctx)) {
                Toast.makeText(ctx, "Please enable notification permissions first", Toast.LENGTH_SHORT).show()
                NotificationPermissionHelper.openNotificationSettings(ctx)
            } else {
                AlertNotificationManager.getInstance(ctx).dispatchTestNotification()
                Toast.makeText(ctx, "Test notification dispatched", Toast.LENGTH_SHORT).show()
            }
        }

        // 4. Network & Data Usage Configuration
        val netConfig = com.example.serverdashboard.data.storage.NetworkConfigStore.getInstance(ctx)

        // Speed in bits toggle
        binding.switchSpeedBits.isChecked = netConfig.speedInBits
        binding.switchSpeedBits.setOnCheckedChangeListener { _, isChecked ->
            netConfig.speedInBits = isChecked
        }

        // Monthly data cap toggle
        binding.switchDataCap.isChecked = netConfig.dataCapEnabled
        binding.layoutDataCapDetails.visibility = if (netConfig.dataCapEnabled) View.VISIBLE else View.GONE
        binding.etDataCapGb.setText(String.format(java.util.Locale.US, "%.0f", netConfig.dataCapGb))
        binding.etBillingCycleDay.setText(netConfig.billingCycleDay.toString())

        binding.switchDataCap.setOnCheckedChangeListener { _, isChecked ->
            netConfig.dataCapEnabled = isChecked
            _binding?.layoutDataCapDetails?.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.btnSaveDataCap.setOnClickListener {
            val capVal = binding.etDataCapGb.text.toString().trim().toFloatOrNull() ?: 100f
            val cycleDay = binding.etBillingCycleDay.text.toString().trim().toIntOrNull() ?: 1

            netConfig.dataCapGb = capVal.coerceAtLeast(1f)
            netConfig.billingCycleDay = cycleDay.coerceIn(1, 31)
            Toast.makeText(ctx, "Monthly data limit settings saved", Toast.LENGTH_SHORT).show()
        }

        // 5. Wake-on-LAN values & Expandable Config (Section 20)
        val legacyPrefs = ctx.getSharedPreferences("omnis_wol_prefs", Context.MODE_PRIVATE)
        val prefs = ctx.getSharedPreferences("zentra_wol_prefs", Context.MODE_PRIVATE)
        val savedMac = prefs.getString("wol_mac", legacyPrefs.getString("wol_mac", "") ?: "") ?: ""
        val savedBroadcast = prefs.getString("wol_broadcast", legacyPrefs.getString("wol_broadcast", "192.168.1.255") ?: "192.168.1.255") ?: "192.168.1.255"
        val savedPort = if (prefs.contains("wol_port")) prefs.getInt("wol_port", 9) else legacyPrefs.getInt("wol_port", 9)

        if (savedMac.isNotEmpty()) {
            binding.etWolMac.setText(savedMac)
        }
        binding.etWolBroadcast.setText(savedBroadcast)
        binding.etWolPort.setText(savedPort.toString())

        val updateWolSummary = {
            val currentMac = prefs.getString("wol_mac", legacyPrefs.getString("wol_mac", "") ?: "") ?: ""
            if (currentMac.isNotEmpty()) {
                binding.tvWolConfigSummary.text = currentMac
                binding.btnToggleWolConfig.text = "Edit"
            } else {
                binding.tvWolConfigSummary.text = "Not configured"
                binding.btnToggleWolConfig.text = "Configure"
            }
        }
        updateWolSummary()

        var isWolConfigExpanded = arguments?.getBoolean(ARG_EXPAND_WOL, false) ?: false
        if (isWolConfigExpanded) {
            binding.layoutWolConfig.visibility = View.VISIBLE
            binding.btnToggleWolConfig.text = "Close ▲"
            binding.etWolMac.requestFocus()
        }

        val toggleWol = {
            isWolConfigExpanded = !isWolConfigExpanded
            _binding?.layoutWolConfig?.visibility = if (isWolConfigExpanded) View.VISIBLE else View.GONE
            _binding?.btnToggleWolConfig?.text = if (isWolConfigExpanded) "Close ▲" else {
                val currentMac = prefs.getString("wol_mac", legacyPrefs.getString("wol_mac", "") ?: "") ?: ""
                if (currentMac.isNotEmpty()) "Edit" else "Configure"
            }
        }

        binding.btnToggleWolConfig.setOnClickListener { toggleWol() }
        binding.layoutWolHeaderRow.setOnClickListener { toggleWol() }

        binding.btnSaveWolConfig.setOnClickListener {
            val mac = binding.etWolMac.text.toString().trim()
            val broadcast = binding.etWolBroadcast.text.toString().trim().ifEmpty { "192.168.1.255" }
            val port = binding.etWolPort.text.toString().trim().toIntOrNull() ?: 9

            if (mac.isNotEmpty()) {
                val cleanMac = mac.replace(":", "").replace("-", "")
                if (cleanMac.length != 12) {
                    binding.etWolMac.error = "Invalid MAC (expected 12 hex digits)"
                    return@setOnClickListener
                }
            }

            prefs.edit()
                .putString("wol_mac", mac)
                .putString("wol_broadcast", broadcast)
                .putInt("wol_port", port)
                .apply()

            updateWolSummary()
            isWolConfigExpanded = false
            _binding?.layoutWolConfig?.visibility = View.GONE
            _binding?.btnToggleWolConfig?.text = if (mac.isNotEmpty()) "Edit" else "Configure"
            Toast.makeText(ctx, "Wake-on-LAN settings saved", Toast.LENGTH_SHORT).show()
        }

        var isAdvancedExpanded = false
        binding.layoutAdvancedToggle.setOnClickListener {
            isAdvancedExpanded = !isAdvancedExpanded
            _binding?.layoutAdvancedContainer?.visibility = if (isAdvancedExpanded) View.VISIBLE else View.GONE
            _binding?.tvAdvancedToggleText?.text = if (isAdvancedExpanded) "Advanced Host & Network Controls ▲" else "Advanced Host & Network Controls ▼"
        }

        // Network Restart Service
        binding.btnSettingsRestartNetwork.setOnClickListener {
            val c = context ?: return@setOnClickListener
            AlertDialog.Builder(c)
                .setTitle("Restart Network Service")
                .setMessage("Restart the server's network connection? Running applications and this dashboard may momentarily disconnect while network interfaces bounce.")
                .setPositiveButton("Restart") { _, _ ->
                    viewModel.restartNetworkService { success, msg ->
                        context?.let { safeCtx ->
                            Toast.makeText(
                                safeCtx,
                                msg,
                                if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        viewModel.networkActionInProgress.observe(viewLifecycleOwner) { inProgress ->
            val b = _binding ?: return@observe
            b.btnSettingsRestartNetwork.isEnabled = !inProgress
            b.layoutSettingsNetworkProgress.visibility = if (inProgress) View.VISIBLE else View.GONE
            if (inProgress) {
                b.tvSettingsNetworkStatus.text = "Restarting network service..."
            }
        }

        // 3. Actions
        binding.btnServerReboot.setOnClickListener {
            confirmPowerAction("reboot", "Reboot Server")
        }

        binding.btnServerShutdown.setOnClickListener {
            confirmPowerAction("shutdown", "Power Off Server")
        }

        binding.btnSwitchServer.setOnClickListener {
            val c = context ?: return@setOnClickListener
            val serverStore = ServerStore.getInstance(c)
            val tokenManager = SecureTokenManager.getInstance(c)
            val servers = serverStore.getServers()

            if (servers.size > 1) {
                val currentUrl = viewModel.serverUrl
                val serverNames = servers.map { s ->
                    val isCurrent = s.url.equals(currentUrl, ignoreCase = true)
                    if (isCurrent) "${s.name} (Current)" else s.name
                }.toTypedArray()

                AlertDialog.Builder(c)
                    .setTitle("Switch Server")
                    .setItems(serverNames) { _, which ->
                        val selected = servers[which]
                        if (selected.url.equals(currentUrl, ignoreCase = true)) {
                            dismissAllowingStateLoss()
                            return@setItems
                        }
                        serverStore.setActiveServer(selected.id)
                        val token = tokenManager.getToken(selected.url)
                        dismissAllowingStateLoss()
                        if (!token.isNullOrEmpty()) {
                            val intent = Intent(c, MainActivity::class.java).apply {
                                putExtra("server_url", selected.url)
                                putExtra("auth_token", token)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            }
                            startActivity(intent)
                            activity?.finish()
                        } else {
                            val intent = Intent(c, ServerConnectActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            }
                            startActivity(intent)
                            activity?.finish()
                        }
                    }
                    .setNeutralButton("Manage / Add Server") { _, _ ->
                        dismissAllowingStateLoss()
                        val intent = Intent(c, ServerConnectActivity::class.java)
                        startActivity(intent)
                        activity?.finish()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                dismissAllowingStateLoss()
                val intent = Intent(c, ServerConnectActivity::class.java)
                startActivity(intent)
                activity?.finish()
            }
        }

        binding.btnSignOut.setOnClickListener {
            val c = context ?: return@setOnClickListener
            val tokenManager = SecureTokenManager.getInstance(c)
            val sUrl = viewModel.serverUrl
            val aToken = viewModel.authToken
            tokenManager.clearToken(sUrl)
            viewLifecycleOwner.lifecycleScope.launch {
                ApiClient.instance.logout(sUrl, aToken)
            }
            dismissAllowingStateLoss()
            val intent = Intent(c, ServerConnectActivity::class.java)
            startActivity(intent)
            activity?.finish()
        }
    }

    private fun confirmPowerAction(action: String, title: String) {
        val c = context ?: return
        val message = if (action == "reboot") {
            "Reboot server? Running applications will be temporarily paused while the server restarts."
        } else {
            "Power off server? You will not be able to connect until it is turned back on."
        }

        AlertDialog.Builder(c)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Confirm") { _, _ ->
                executePowerAction(action)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executePowerAction(action: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = ApiClient.instance.serverPower(viewModel.serverUrl, viewModel.authToken, action, confirm = true)
            context?.let { c ->
                if (result.isSuccess) {
                    Toast.makeText(c, "Power command sent: $action", Toast.LENGTH_SHORT).show()
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Power command failed"
                    Toast.makeText(c, err, Toast.LENGTH_LONG).show()
                }
            }
            if (isAdded) {
                dismissAllowingStateLoss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_EXPAND_WOL = "arg_expand_wol"

        fun newInstance(expandWol: Boolean = false): SettingsBottomSheet {
            val sheet = SettingsBottomSheet()
            if (expandWol) {
                sheet.arguments = Bundle().apply {
                    putBoolean(ARG_EXPAND_WOL, true)
                }
            }
            return sheet
        }
    }
}
