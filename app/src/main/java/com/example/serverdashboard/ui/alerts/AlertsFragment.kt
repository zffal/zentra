package com.example.serverdashboard.ui.alerts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.serverdashboard.R
import com.example.serverdashboard.data.api.ApiClient
import com.example.serverdashboard.data.model.AlertHistoryItem
import com.example.serverdashboard.databinding.FragmentAlertsBinding
import com.example.serverdashboard.ui.main.MainViewModel
import com.example.serverdashboard.ui.util.Formatters
import kotlinx.coroutines.launch

class AlertsFragment : Fragment() {

    private var _binding: FragmentAlertsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var adapter: AlertsAdapter
    private var currentFilter: String = "all"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlertsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = AlertsAdapter(
            onResolveClick = { alert ->
                resolveAlert(alert.id)
            },
            onItemClick = { alert ->
                showAlertDetailsDialog(alert)
            }
        )

        val ctx = context ?: return
        binding.recyclerAlerts.layoutManager = LinearLayoutManager(ctx)
        binding.recyclerAlerts.adapter = adapter

        binding.toggleAlertFilter.check(R.id.btnAlertFilterAll)
        binding.toggleAlertFilter.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentFilter = when (checkedId) {
                    R.id.btnAlertFilterCritical -> "critical"
                    R.id.btnAlertFilterWarning -> "warning"
                    R.id.btnAlertFilterResolved -> "resolved"
                    else -> "all"
                }
                loadAlerts()
            }
        }


        binding.btnClearAllAlerts.setOnClickListener {
            confirmClearAll()
        }

        binding.swipeRefreshAlerts.setColorSchemeResources(R.color.accent_blue)
        binding.swipeRefreshAlerts.setOnRefreshListener {
            loadAlerts()
            binding.swipeRefreshAlerts.postDelayed({
                _binding?.swipeRefreshAlerts?.isRefreshing = false
            }, 800)
        }

        AlertNotificationManager.getInstance(ctx).localActiveAlerts.observe(viewLifecycleOwner) {
            loadAlerts()
        }

        updatePermissionBanner()
        loadAlerts()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionBanner()
    }

    private fun updatePermissionBanner() {
        val ctx = context ?: return
        val b = _binding ?: return
        val configStore = com.example.serverdashboard.data.storage.NotificationConfigStore.getInstance(ctx)
        val enabled = NotificationPermissionHelper.areNotificationsEnabled(ctx)

        if (!enabled && !configStore.hasDismissedBannerThisLaunch) {
            b.cardNotificationPermissionBanner.visibility = View.VISIBLE
            b.btnEnableNotificationsBanner.setOnClickListener {
                NotificationPermissionHelper.openNotificationSettings(ctx)
            }
            b.btnDismissNotificationBanner.setOnClickListener {
                b.cardNotificationPermissionBanner.visibility = View.GONE
                configStore.hasDismissedBannerThisLaunch = true
            }
        } else {
            b.cardNotificationPermissionBanner.visibility = View.GONE
        }
    }

    private var isClearingAlerts: Boolean = false

    private fun loadAlerts() {
        val url = viewModel.serverUrl
        val token = viewModel.authToken
        if (url.isEmpty() || token.isEmpty()) return

        viewLifecycleOwner.lifecycleScope.launch {
            val result = ApiClient.instance.getAlerts(url, token, currentFilter)
            val b = _binding ?: return@launch
            val ctx = context ?: return@launch

            val serverList = result.getOrDefault(emptyList())
            val localAlerts = AlertNotificationManager.getInstance(ctx).getActiveAlerts()
            val filteredLocal = localAlerts.filter {
                when (currentFilter) {
                    "critical" -> it.severity.equals("critical", ignoreCase = true)
                    "warning" -> it.severity.equals("warning", ignoreCase = true)
                    "resolved" -> false
                    else -> true
                }
            }

            val merged = (filteredLocal + serverList).distinctBy { it.id }

            val isEmpty = merged.isEmpty()
            b.layoutEmptyAlerts.visibility = if (isEmpty) View.VISIBLE else View.GONE
            b.recyclerAlerts.visibility = if (isEmpty) View.GONE else View.VISIBLE
            b.tvEmptyAlerts.text = when (currentFilter) {
                "critical" -> "No critical alerts. Everything running smoothly."
                "warning" -> "No warning alerts reported."
                "resolved" -> "No resolved alerts in history."
                else -> "No alerts reported. All server systems healthy."
            }
            adapter.updateData(merged)

            b.btnClearAllAlerts.isEnabled = merged.isNotEmpty() && !isClearingAlerts
            b.btnClearAllAlerts.alpha = if (merged.isNotEmpty() && !isClearingAlerts) 1.0f else 0.4f
        }
    }

    private fun resolveAlert(id: String) {
        val url = viewModel.serverUrl
        val token = viewModel.authToken
        if (url.isEmpty() || token.isEmpty()) return

        viewLifecycleOwner.lifecycleScope.launch {
            val result = ApiClient.instance.resolveAlert(url, token, id)
            if (result.isSuccess) {
                showSnackbar("Alert marked as resolved", isError = false)
                loadAlerts()
                viewModel.fetchRestMetrics()
            } else {
                val err = result.exceptionOrNull()?.message ?: "Failed to resolve alert"
                showSnackbar(err, isError = true)
            }
        }
    }

    private fun confirmClearAll() {
        if (isClearingAlerts) return
        val ctx = context ?: return
        if (adapter.itemCount == 0) {
            showSnackbar("No alerts to clear", isError = false)
            return
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("Clear All Alerts")
            .setMessage("All active and resolved alerts will be permanently deleted from the server. This action cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                executeClearAllAlerts()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeClearAllAlerts() {
        if (isClearingAlerts) return
        val url = viewModel.serverUrl
        val token = viewModel.authToken
        val ctx = context ?: return

        isClearingAlerts = true
        _binding?.btnClearAllAlerts?.isEnabled = false
        _binding?.btnClearAllAlerts?.alpha = 0.4f
        _binding?.swipeRefreshAlerts?.isRefreshing = true

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (url.isNotEmpty() && token.isNotEmpty()) {
                    val result = ApiClient.instance.clearAllAlerts(url, token)
                    if (result.isSuccess) {
                        val clearResponse = result.getOrNull()
                        val deletedCount = clearResponse?.countDeleted ?: 0

                        // Clear local alert notifications and cache only after backend confirms success
                        AlertNotificationManager.getInstance(ctx).clearAllAlerts()

                        // Clear displayed list
                        adapter.updateData(emptyList())
                        _binding?.layoutEmptyAlerts?.visibility = View.VISIBLE
                        _binding?.recyclerAlerts?.visibility = View.GONE
                        viewModel.updateActiveAlertsCount(0)

                        val msg = if (deletedCount == 1) {
                            "1 alert permanently deleted"
                        } else if (deletedCount > 0) {
                            "$deletedCount alerts permanently deleted"
                        } else {
                            "All alerts cleared"
                        }
                        showSnackbar(msg, isError = false)

                        viewModel.fetchRestMetrics()
                    } else {
                        val err = result.exceptionOrNull()?.message?.ifEmpty { "Failed to clear alerts on server" }
                            ?: "Failed to clear alerts on server"
                        showSnackbar(err, isError = true)
                    }
                } else {
                    AlertNotificationManager.getInstance(ctx).clearAllAlerts()
                    adapter.updateData(emptyList())
                    _binding?.layoutEmptyAlerts?.visibility = View.VISIBLE
                    _binding?.recyclerAlerts?.visibility = View.GONE
                    viewModel.updateActiveAlertsCount(0)
                    showSnackbar("Local alerts cleared", isError = false)
                }
            } catch (e: Exception) {
                val err = e.message?.ifEmpty { "Failed to clear alerts" } ?: "Failed to clear alerts"
                showSnackbar(err, isError = true)
            } finally {
                isClearingAlerts = false
                _binding?.swipeRefreshAlerts?.isRefreshing = false
                val remainingCount = adapter.itemCount
                _binding?.btnClearAllAlerts?.isEnabled = remainingCount > 0
                _binding?.btnClearAllAlerts?.alpha = if (remainingCount > 0) 1.0f else 0.4f
            }
        }
    }

    private fun showAlertDetailsDialog(alert: AlertHistoryItem) {
        val ctx = context ?: return
        val details = buildString {
            if (alert.displayExplanation.isNotBlank()) {
                append(alert.displayExplanation)
                append("\n\n")
            }
            append(alert.displayMetric)
            append("\nTime: ")
            append(Formatters.formatTimestamp(alert.timestamp))
            if (alert.isResolved) {
                append("\nStatus: Resolved")
            }
        }
        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(alert.displayTitle)
            .setMessage(details)
            .setPositiveButton("OK", null)

        if (!alert.isResolved) {
            builder.setNegativeButton("Resolve") { _, _ ->
                resolveAlert(alert.id)
            }
        }
        builder.show()
    }

    private fun showSnackbar(message: String, isError: Boolean) {
        val b = _binding ?: return
        val ctx = context ?: return
        val snackbar = com.google.android.material.snackbar.Snackbar.make(b.root, message, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
        if (isError) {
            snackbar.setBackgroundTint(ContextCompat.getColor(ctx, R.color.critical))
            snackbar.setTextColor(ContextCompat.getColor(ctx, R.color.white))
        } else {
            snackbar.setBackgroundTint(ContextCompat.getColor(ctx, R.color.surface_2))
            snackbar.setTextColor(ContextCompat.getColor(ctx, R.color.label_primary))
        }
        snackbar.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
