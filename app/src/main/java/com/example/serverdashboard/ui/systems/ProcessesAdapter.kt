package com.example.serverdashboard.ui.systems

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.serverdashboard.data.model.ProcessInfo
import com.example.serverdashboard.databinding.ItemProcessBinding
import com.example.serverdashboard.ui.util.Formatters
import java.util.Locale

class ProcessesAdapter(
    private var processes: List<ProcessInfo> = emptyList()
) : RecyclerView.Adapter<ProcessesAdapter.ProcessViewHolder>() {

    fun updateData(newProcesses: List<ProcessInfo>) {
        this.processes = newProcesses
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProcessViewHolder {
        val binding = ItemProcessBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ProcessViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProcessViewHolder, position: Int) {
        holder.bind(processes[position])
    }

    override fun getItemCount(): Int = processes.size

    inner class ProcessViewHolder(private val binding: ItemProcessBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ProcessInfo) {
            binding.tvProcPid.text = item.pid.toString()
            binding.tvProcName.text = item.name
            val cpuPct = if (item.cpu_percent.isFinite() && item.cpu_percent >= 0f) item.cpu_percent else 0f
            binding.tvProcCpu.text = String.format(Locale.US, "%.1f%%", cpuPct)
            binding.tvProcMem.text = Formatters.formatBytes(item.memory_rss_bytes)
        }
    }
}
