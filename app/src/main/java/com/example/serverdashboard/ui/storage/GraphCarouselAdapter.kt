package com.example.serverdashboard.ui.storage

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.serverdashboard.data.model.BarItem
import com.example.serverdashboard.data.model.GraphConfig
import com.example.serverdashboard.data.model.GraphMetric
import com.example.serverdashboard.data.model.GraphStyle
import com.example.serverdashboard.data.model.SystemOverview
import com.example.serverdashboard.databinding.ItemCustomizableGraphBinding
import com.example.serverdashboard.ui.util.Formatters
import java.util.Locale

class GraphCarouselAdapter(
    private val onConfigureClick: (GraphConfig, Int) -> Unit
) : RecyclerView.Adapter<GraphCarouselAdapter.GraphViewHolder>() {

    private val configs = mutableListOf<GraphConfig>()
    private var overview: SystemOverview? = null
    private var cpuHistory: List<Float> = emptyList()
    private var ramHistory: List<Float> = emptyList()
    private var diskReadHistory: List<Float> = emptyList()
    private var diskWriteHistory: List<Float> = emptyList()
    private var netDownloadHistory: List<Float> = emptyList()
    private var netUploadHistory: List<Float> = emptyList()
    private var storageHistory: List<Float> = emptyList()
    private var currentDiskReadRate: Long? = null
    private var currentDiskWriteRate: Long? = null

    fun updateConfigs(newConfigs: List<GraphConfig>) {
        configs.clear()
        configs.addAll(newConfigs)
        notifyDataSetChanged()
    }

    fun updateTelemetry(
        newOverview: SystemOverview?,
        cpuHist: List<Float>,
        ramHist: List<Float>,
        diskReadHist: List<Float>,
        diskWriteHist: List<Float>,
        netDownHist: List<Float>,
        netUpHist: List<Float>,
        storageHist: List<Float>,
        diskReadRate: Long? = null,
        diskWriteRate: Long? = null
    ) {
        overview = newOverview
        cpuHistory = cpuHist
        ramHistory = ramHist
        diskReadHistory = diskReadHist
        diskWriteHistory = diskWriteHist
        netDownloadHistory = netDownHist
        netUploadHistory = netUpHist
        storageHistory = storageHist
        currentDiskReadRate = diskReadRate ?: newOverview?.storage?.calculatedReadBps ?: newOverview?.storage?.precalculatedReadRate
        currentDiskWriteRate = diskWriteRate ?: newOverview?.storage?.calculatedWriteBps ?: newOverview?.storage?.precalculatedWriteRate
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GraphViewHolder {
        val binding = ItemCustomizableGraphBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        // Ensure root layout params are strictly MATCH_PARENT for both dimensions as required by ViewPager2
        binding.root.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        return GraphViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GraphViewHolder, position: Int) {
        holder.bind(configs[position], position)
    }

    override fun getItemCount(): Int = configs.size

    inner class GraphViewHolder(
        private val binding: ItemCustomizableGraphBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(config: GraphConfig, pos: Int) {
            val ctx = binding.root.context

            // Header titles & badges
            binding.tvGraphTitle.text = config.metric.displayName
            binding.tvGraphStyleBadge.text = when (config.style) {
                GraphStyle.SEMI_CIRCLE -> "Gauge"
                GraphStyle.RING -> "Ring"
                GraphStyle.LINE -> "Line"
                GraphStyle.BAR -> "Bar"
            }
            binding.tvGraphRangeBadge.text = config.timeRange.displayName

            // Adjust internal padding based on compact preference
            val density = ctx.resources.displayMetrics.density
            val pad = if (config.isCompact) (8 * density).toInt() else (12 * density).toInt()
            (binding.root.getChildAt(0) as? ViewGroup)?.setPadding(pad, pad, pad, pad)

            // Bind Metric View data
            binding.metricGraphView.setStyle(config.style)

            val currentOverview = overview
            when (config.metric) {
                GraphMetric.CPU -> {
                    val rawVal = currentOverview?.cpu?.overall_usage ?: 0f
                    val safeVal = if (rawVal.isFinite() && rawVal >= 0f) rawVal else 0f
                    val formatted = String.format(Locale.US, "%.1f%%", safeVal)
                    val cores = currentOverview?.cpu?.logical_count ?: currentOverview?.cpu?.cores ?: 0
                    val secondary = if (cores > 0) "$cores Cores • Real-time load" else "Real-time load"

                    val bars = mutableListOf<BarItem>()
                    val perCore = currentOverview?.cpu?.per_core_usage ?: emptyList()
                    if (perCore.isNotEmpty()) {
                        perCore.take(8).forEachIndexed { i, usage ->
                            bars.add(BarItem("C$i", usage, 100f))
                        }
                    }

                    binding.metricGraphView.setData(
                        value = safeVal,
                        max = 100f,
                        formatted = formatted,
                        secondary = secondary,
                        rate = false,
                        history = cpuHistory,
                        bars = bars
                    )
                    binding.tvGraphSecondaryDetails.text = secondary
                }

                GraphMetric.MEMORY -> {
                    val rawVal = currentOverview?.memory?.usage_percent ?: 0f
                    val safeVal = if (rawVal.isFinite() && rawVal >= 0f) rawVal else 0f
                    val formatted = String.format(Locale.US, "%.1f%%", safeVal)
                    val usedStr = Formatters.formatBytes(currentOverview?.memory?.used_bytes ?: 0L)
                    val totalStr = Formatters.formatBytes(currentOverview?.memory?.total_bytes ?: 0L)
                    val secondary = "$usedStr used / $totalStr"

                    binding.metricGraphView.setData(
                        value = safeVal,
                        max = 100f,
                        formatted = formatted,
                        secondary = secondary,
                        rate = false,
                        history = ramHistory
                    )
                    binding.tvGraphSecondaryDetails.text = secondary
                }

                GraphMetric.STORAGE -> {
                    val fsList = currentOverview?.storage?.filesystems ?: emptyList()
                    val rootFs = fsList.firstOrNull { it.mount_point == "/" } ?: fsList.firstOrNull()
                    val rawVal = rootFs?.usage_percent ?: 0f
                    val safeVal = if (rawVal.isFinite() && rawVal >= 0f) rawVal else 0f
                    val formatted = String.format(Locale.US, "%.1f%%", safeVal)
                    val usedStr = Formatters.formatBytes(rootFs?.used_bytes ?: 0L)
                    val totalStr = Formatters.formatBytes(rootFs?.total_bytes ?: 0L)
                    val secondary = if (rootFs != null) "${rootFs.mount_point} ($usedStr / $totalStr)" else "Storage Drives"

                    val bars = fsList.take(6).map { fs ->
                        val label = if (fs.mount_point.length > 5) fs.mount_point.takeLast(4) else fs.mount_point
                        BarItem(label.ifEmpty { "drive" }, fs.usage_percent, 100f)
                    }

                    binding.metricGraphView.setData(
                        value = safeVal,
                        max = 100f,
                        formatted = formatted,
                        secondary = secondary,
                        rate = false,
                        history = storageHistory,
                        bars = bars
                    )
                    binding.tvGraphSecondaryDetails.text = secondary
                }

                GraphMetric.DISK_READ -> {
                    val readRate = currentDiskReadRate ?: currentOverview?.storage?.calculatedReadBps ?: currentOverview?.storage?.precalculatedReadRate
                    val writeRate = currentDiskWriteRate ?: currentOverview?.storage?.calculatedWriteBps ?: currentOverview?.storage?.precalculatedWriteRate

                    val (formatted, safeVal) = if (readRate != null) {
                        Pair(Formatters.formatSpeed(readRate), readRate.toFloat())
                    } else {
                        Pair("Unavailable", 0f)
                    }

                    val secondary = if (writeRate != null) {
                        "Write: ${Formatters.formatSpeed(writeRate)}"
                    } else {
                        "Disk I/O unavailable"
                    }

                    binding.metricGraphView.setData(
                        value = safeVal,
                        max = (diskReadHistory.maxOrNull() ?: 1024f).coerceAtLeast(1024f),
                        formatted = formatted,
                        secondary = secondary,
                        rate = true,
                        history = if (readRate != null) diskReadHistory else emptyList()
                    )
                    binding.tvGraphSecondaryDetails.text = secondary
                }

                GraphMetric.DISK_WRITE -> {
                    val readRate = currentDiskReadRate ?: currentOverview?.storage?.calculatedReadBps ?: currentOverview?.storage?.precalculatedReadRate
                    val writeRate = currentDiskWriteRate ?: currentOverview?.storage?.calculatedWriteBps ?: currentOverview?.storage?.precalculatedWriteRate

                    val (formatted, safeVal) = if (writeRate != null) {
                        Pair(Formatters.formatSpeed(writeRate), writeRate.toFloat())
                    } else {
                        Pair("Unavailable", 0f)
                    }

                    val secondary = if (readRate != null) {
                        "Read: ${Formatters.formatSpeed(readRate)}"
                    } else {
                        "Disk I/O unavailable"
                    }

                    binding.metricGraphView.setData(
                        value = safeVal,
                        max = (diskWriteHistory.maxOrNull() ?: 1024f).coerceAtLeast(1024f),
                        formatted = formatted,
                        secondary = secondary,
                        rate = true,
                        history = if (writeRate != null) diskWriteHistory else emptyList()
                    )
                    binding.tvGraphSecondaryDetails.text = secondary
                }

                GraphMetric.NET_DOWNLOAD -> {
                    val useBits = com.example.serverdashboard.data.storage.NetworkConfigStore.getInstance(ctx).speedInBits
                    val bps = currentOverview?.network?.download_speed_bps ?: 0L
                    val safeVal = bps.toFloat()
                    val formatted = Formatters.formatSpeed(bps, useBits)
                    val upBps = currentOverview?.network?.upload_speed_bps ?: 0L
                    val secondary = "Upload: ${Formatters.formatSpeed(upBps, useBits)}"

                    binding.metricGraphView.setData(
                        value = safeVal,
                        max = (netDownloadHistory.maxOrNull() ?: 1024f).coerceAtLeast(1024f),
                        formatted = formatted,
                        secondary = secondary,
                        rate = true,
                        history = netDownloadHistory
                    )
                    binding.tvGraphSecondaryDetails.text = secondary
                }

                GraphMetric.NET_UPLOAD -> {
                    val useBits = com.example.serverdashboard.data.storage.NetworkConfigStore.getInstance(ctx).speedInBits
                    val bps = currentOverview?.network?.upload_speed_bps ?: 0L
                    val safeVal = bps.toFloat()
                    val formatted = Formatters.formatSpeed(bps, useBits)
                    val downBps = currentOverview?.network?.download_speed_bps ?: 0L
                    val secondary = "Download: ${Formatters.formatSpeed(downBps, useBits)}"

                    binding.metricGraphView.setData(
                        value = safeVal,
                        max = (netUploadHistory.maxOrNull() ?: 1024f).coerceAtLeast(1024f),
                        formatted = formatted,
                        secondary = secondary,
                        rate = true,
                        history = netUploadHistory
                    )
                    binding.tvGraphSecondaryDetails.text = secondary
                }
            }

            // Secondary visibility
            binding.tvGraphSecondaryDetails.visibility =
                if (config.showSecondaryValue) View.VISIBLE else View.GONE

            // Clicks
            binding.btnGraphSettings.setOnClickListener {
                onConfigureClick(config, pos)
            }
            binding.root.setOnClickListener {
                onConfigureClick(config, pos)
            }
        }
    }
}
