package com.example.serverdashboard.ui.storage

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.serverdashboard.data.model.FilesystemMetric
import com.example.serverdashboard.databinding.ItemFilesystemBinding
import com.example.serverdashboard.ui.util.Formatters
import java.util.Locale

class FilesystemAdapter(
    private var filesystems: List<FilesystemMetric> = emptyList()
) : RecyclerView.Adapter<FilesystemAdapter.FilesystemViewHolder>() {

    fun updateData(newFilesystems: List<FilesystemMetric>) {
        this.filesystems = newFilesystems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FilesystemViewHolder {
        val binding = ItemFilesystemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FilesystemViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FilesystemViewHolder, position: Int) {
        holder.bind(filesystems[position])
    }

    override fun getItemCount(): Int = filesystems.size

    inner class FilesystemViewHolder(private val binding: ItemFilesystemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FilesystemMetric) {
            binding.tvMountPoint.text = item.mount_point
            val safeUsage = if (item.usage_percent.isFinite() && item.usage_percent >= 0f) item.usage_percent else 0f
            binding.tvFsUsagePercent.text = String.format(Locale.US, "%.1f%%", safeUsage)
            binding.progressFsUsage.progress = safeUsage.toInt().coerceIn(0, 100)

            val fsType = if (item.filesystem.isNotEmpty()) item.filesystem else item.fstype
            binding.tvFsDevice.text = if (fsType.isNotEmpty()) "${item.device} ($fsType)" else item.device

            val used = Formatters.formatBytes(item.used_bytes)
            val total = Formatters.formatBytes(item.total_bytes)
            val free = Formatters.formatBytes(item.free_bytes)
            binding.tvFsCapacity.text = "$used / $total ($free free)"
        }
    }
}
