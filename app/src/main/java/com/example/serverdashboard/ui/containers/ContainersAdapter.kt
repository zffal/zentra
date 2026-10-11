package com.example.serverdashboard.ui.containers

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.serverdashboard.R
import com.example.serverdashboard.data.model.ContainerItem
import com.example.serverdashboard.databinding.ItemContainerBinding
import com.example.serverdashboard.ui.util.Formatters
import java.util.Locale

class ContainersAdapter(
    private var containers: List<ContainerItem> = emptyList(),
    private val onLogsClick: (ContainerItem) -> Unit,
    private val onActionClick: (ContainerItem, String) -> Unit
) : RecyclerView.Adapter<ContainersAdapter.ContainerViewHolder>() {

    private val expandedIds = mutableSetOf<String>()

    fun updateData(newContainers: List<ContainerItem>) {
        this.containers = newContainers
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContainerViewHolder {
        val binding = ItemContainerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ContainerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ContainerViewHolder, position: Int) {
        holder.bind(containers[position])
    }

    override fun getItemCount(): Int = containers.size

    inner class ContainerViewHolder(private val binding: ItemContainerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ContainerItem) {
            val displayName = item.name.removePrefix("/")
            binding.tvContainerName.text = displayName

            val isRunning = item.state.equals("running", ignoreCase = true)
            val isPaused = item.state.equals("paused", ignoreCase = true)

            val ctx = binding.root.context
            when {
                isRunning -> {
                    binding.tvContainerStatusBadge.text = "Running"
                    binding.tvContainerStatusBadge.setBackgroundResource(R.drawable.badge_running)
                    binding.tvContainerStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.success))
                    binding.btnStartContainer.visibility = View.GONE
                    binding.btnStopContainer.visibility = View.VISIBLE
                    binding.btnRestartContainer.visibility = View.VISIBLE
                }
                isPaused -> {
                    binding.tvContainerStatusBadge.text = "Paused"
                    binding.tvContainerStatusBadge.setBackgroundResource(R.drawable.badge_warning)
                    binding.tvContainerStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.warning))
                    binding.btnStartContainer.visibility = View.VISIBLE
                    binding.btnStopContainer.visibility = View.VISIBLE
                    binding.btnRestartContainer.visibility = View.VISIBLE
                }
                else -> {
                    binding.tvContainerStatusBadge.text = "Stopped"
                    binding.tvContainerStatusBadge.setBackgroundResource(R.drawable.badge_stopped)
                    binding.tvContainerStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.label_secondary))
                    binding.btnStartContainer.visibility = View.VISIBLE
                    binding.btnStopContainer.visibility = View.GONE
                    binding.btnRestartContainer.visibility = View.GONE
                }
            }

            binding.tvContainerState.text = item.image.ifEmpty { item.status.ifEmpty { if (isRunning) "Running" else "Stopped" } }

            // Technical details
            binding.tvContainerImage.text = "Image: ${item.image}"
            binding.tvContainerId.text = "ID: ${if (item.id.length > 12) item.id.substring(0, 12) else item.id}"

            val rawCpu = item.cpu_percent ?: 0f
            val safeCpu = if (rawCpu.isFinite() && rawCpu >= 0f) rawCpu else 0f
            binding.tvContainerCpu.text = String.format(Locale.US, "CPU: %.1f%%", safeCpu)

            val memBytes = item.memory_usage_bytes ?: 0L
            binding.tvContainerMem.text = "RAM: " + Formatters.formatBytes(memBytes)

            // Expansion state
            val isExpanded = expandedIds.contains(item.id)
            binding.layoutContainerDetails.visibility = if (isExpanded) View.VISIBLE else View.GONE
            binding.imgExpandChevron.rotation = if (isExpanded) 90f else 0f

            binding.cardContainerItem.setOnClickListener {
                if (isExpanded) {
                    expandedIds.remove(item.id)
                } else {
                    expandedIds.add(item.id)
                }
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    notifyItemChanged(pos)
                }
            }

            binding.btnViewLogs.setOnClickListener { onLogsClick(item) }
            binding.btnRestartContainer.setOnClickListener { onActionClick(item, "restart") }
            binding.btnStopContainer.setOnClickListener { onActionClick(item, "stop") }
            binding.btnStartContainer.setOnClickListener { onActionClick(item, "start") }
        }
    }
}
