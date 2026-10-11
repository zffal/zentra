package com.example.serverdashboard.ui.systems

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.serverdashboard.R
import com.example.serverdashboard.data.api.ApiClient
import com.example.serverdashboard.data.model.SystemOverview
import com.example.serverdashboard.databinding.FragmentSystemsBinding
import com.example.serverdashboard.ui.main.MainViewModel
import com.example.serverdashboard.ui.util.Formatters
import kotlinx.coroutines.launch
import java.util.Locale

class SystemsFragment : Fragment() {

    private var _binding: FragmentSystemsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private val processesAdapter = ProcessesAdapter()
    private var currentSort = "cpu"
    private var isCoresExpanded = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSystemsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ctx = context ?: return
        binding.recyclerProcesses.layoutManager = LinearLayoutManager(ctx)
        binding.recyclerProcesses.adapter = processesAdapter

        binding.layoutToggleCores.setOnClickListener {
            isCoresExpanded = !isCoresExpanded
            _binding?.containerCores?.visibility = if (isCoresExpanded) View.VISIBLE else View.GONE
            val coreCount = viewModel.systemOverview.value?.cpu?.per_core_usage?.size ?: 0
            val arrow = if (isCoresExpanded) "▲" else "▼"
            _binding?.tvToggleCoresText?.text = if (coreCount > 0) "Per-core activity ($coreCount cores) $arrow" else "Per-core activity $arrow"
        }

        binding.toggleGroupProcessSort.check(R.id.btnSortCpu)
        binding.toggleGroupProcessSort.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentSort = if (checkedId == R.id.btnSortCpu) "cpu" else "memory"
                fetchProcesses()
            }
        }

        binding.swipeRefreshSystems.setColorSchemeResources(R.color.accent_tech)
        binding.swipeRefreshSystems.setOnRefreshListener {
            viewModel.fetchRestMetrics()
            fetchProcesses()
            binding.swipeRefreshSystems.postDelayed({
                _binding?.swipeRefreshSystems?.isRefreshing = false
            }, 800)
        }

        viewModel.systemOverview.observe(viewLifecycleOwner) { overview ->
            updateUI(overview)
        }

        fetchProcesses()
    }

    private fun updateUI(data: SystemOverview) {
        val b = _binding ?: return
        val host = data.resolvedHost

        // CPU
        val model = host.cpuModel.ifEmpty { data.cpu.model }.ifEmpty { "Intel Core CPU" }
        b.tvCpuModel.text = model
        val cores = data.cpu.core_count
        val logical = data.cpu.logical_count
        b.tvCoreCounts.text = if (cores > 0) "$cores Cores / $logical Threads" else "-- Cores / -- Threads"

        val loadAvg1: Float = data.cpu.load_avg.firstOrNull() ?: 0f
        val effectiveCores = if (cores > 0) cores.toFloat() else 1f
        val loadDesc = when {
            loadAvg1 <= 0f -> "Idle"
            loadAvg1 < effectiveCores * 0.7f -> "Normal"
            loadAvg1 < effectiveCores * 1.0f -> "Moderate"
            else -> "High"
        }
        val loadDetails = if (data.cpu.load_avg.isNotEmpty()) {
            data.cpu.load_avg.joinToString(", ") { String.format(Locale.US, "%.2f", if (it.isFinite()) it else 0f) }
        } else "N/A"
        b.tvSysLoadAvg.text = "Load: $loadDesc ($loadDetails)"

        // Per-core toggle label & activity bars
        val coreList = data.cpu.per_core_usage
        val arrow = if (isCoresExpanded) "▲" else "▼"
        b.tvToggleCoresText.text = if (coreList.isNotEmpty()) "Per-core activity (${coreList.size} cores) $arrow" else "Per-core activity $arrow"
        updateCoreBars(coreList)

        // Memory
        b.tvMemTotal.text = Formatters.formatBytes(data.memory.total_bytes)
        b.tvMemUsed.text = Formatters.formatBytes(data.memory.used_bytes)
        b.tvMemAvailable.text = Formatters.formatBytes(data.memory.available_bytes)
        b.tvMemCached.text = "Cached: " + Formatters.formatBytes(data.memory.cached_bytes)

        val swapUsedStr = Formatters.formatBytes(data.memory.swap_used_bytes)
        val swapTotalStr = Formatters.formatBytes(data.memory.swap_total_bytes)
        val swapPct = String.format(Locale.US, "%.1f%%", if (data.memory.swap_percent.isFinite()) data.memory.swap_percent else 0f)
        b.tvSwapAllocation.text = "Swap: $swapUsedStr / $swapTotalStr ($swapPct)"

        // Thermals
        val cpuTemp = data.temperature?.cpu
        b.tvCpuThermal.text = if (cpuTemp != null && cpuTemp.isFinite() && cpuTemp > 0) String.format(Locale.US, "CPU: %.1f°C", cpuTemp) else "CPU: N/A"

        val gpuTemp = data.temperature?.gpu
        if (gpuTemp != null && gpuTemp.isFinite() && gpuTemp > 0) {
            b.tvGpuThermal.visibility = View.VISIBLE
            b.tvGpuThermal.text = String.format(Locale.US, "GPU: %.1f°C", gpuTemp)
        } else {
            b.tvGpuThermal.visibility = View.GONE
        }

        val nvmeTemp = data.temperature?.nvme
        if (nvmeTemp != null && nvmeTemp.isFinite() && nvmeTemp > 0) {
            b.tvNvmeThermal.visibility = View.VISIBLE
            b.tvNvmeThermal.text = String.format(Locale.US, "NVMe: %.1f°C", nvmeTemp)
        } else {
            b.tvNvmeThermal.visibility = View.GONE
        }
    }

    private fun updateCoreBars(perCore: List<Float>) {
        if (perCore.isEmpty()) return
        val b = _binding ?: return
        val ctx = context ?: return
        val container = b.containerCores

        if (container.childCount == perCore.size) {
            for (i in perCore.indices) {
                val row = container.getChildAt(i) ?: continue
                val progress = row.findViewById<ProgressBar>(R.id.progressCore)
                val tvPercent = row.findViewById<TextView>(R.id.tvCorePercent)
                val pct = perCore[i]
                val safePct = if (pct.isFinite() && pct >= 0f) pct else 0f
                progress?.progress = safePct.toInt().coerceIn(0, 100)
                tvPercent?.text = String.format(Locale.US, "%.0f%%", safePct)
            }
        } else {
            container.removeAllViews()
            val inflater = LayoutInflater.from(ctx)
            for (i in perCore.indices) {
                val row = inflater.inflate(R.layout.item_core_usage, container, false)
                val tvLabel = row.findViewById<TextView>(R.id.tvCoreLabel)
                val progress = row.findViewById<ProgressBar>(R.id.progressCore)
                val tvPercent = row.findViewById<TextView>(R.id.tvCorePercent)

                tvLabel?.text = "Core $i"
                val pct = perCore[i]
                val safePct = if (pct.isFinite() && pct >= 0f) pct else 0f
                progress?.progress = safePct.toInt().coerceIn(0, 100)
                tvPercent?.text = String.format(Locale.US, "%.0f%%", safePct)
                container.addView(row)
            }
        }
    }

    private fun fetchProcesses() {
        val url = viewModel.serverUrl
        val token = viewModel.authToken
        if (url.isEmpty() || token.isEmpty()) return

        viewLifecycleOwner.lifecycleScope.launch {
            val result = ApiClient.instance.getProcesses(url, token, sortBy = currentSort, limit = 25)
            val b = _binding ?: return@launch
            if (result.isSuccess) {
                val list = result.getOrDefault(emptyList())
                b.tvEmptyProcesses.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                if (list.isEmpty()) b.tvEmptyProcesses.text = "No active processes reported."
                processesAdapter.updateData(list)
            } else {
                b.tvEmptyProcesses.visibility = View.VISIBLE
                b.tvEmptyProcesses.text = "Waiting for server process data…"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
