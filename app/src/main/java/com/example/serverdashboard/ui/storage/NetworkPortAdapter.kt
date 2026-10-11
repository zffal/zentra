package com.example.serverdashboard.ui.storage

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.serverdashboard.R
import com.example.serverdashboard.data.model.PortItem
import com.example.serverdashboard.databinding.ItemNetworkPortBinding

class NetworkPortAdapter : RecyclerView.Adapter<NetworkPortAdapter.PortViewHolder>() {

    private val items = mutableListOf<PortItem>()

    fun updateData(newItems: List<PortItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PortViewHolder {
        val binding = ItemNetworkPortBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PortViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PortViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class PortViewHolder(private val binding: ItemNetworkPortBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PortItem) {
            val context = binding.root.context

            binding.tvPortProtocol.text = item.protocol.uppercase()
            binding.tvPortNumber.text = ":${item.port}"

            val sName = if (!item.serviceName.isNullOrBlank()) {
                item.serviceName
            } else {
                item.processName ?: "Service"
            }
            binding.tvPortServiceName.text = sName

            val isListening = item.state == "LISTEN" || item.state == "ACTIVE"
            if (isListening) {
                val scopeDesc = when (item.scope) {
                    "local" -> "Localhost only"
                    "all" -> "All interfaces"
                    else -> item.bindAddress ?: "Any"
                }
                val pName = item.processName
                val currentPid = item.pid
                val procPart = if (!pName.isNullOrBlank()) {
                    val pidPart = if (currentPid != null && currentPid > 0) " (PID $currentPid)" else ""
                    " • $pName$pidPart"
                } else {
                    ""
                }
                binding.tvPortDetails.text = "${item.bindAddress ?: "0.0.0.0"} ($scopeDesc)$procPart"

                binding.tvPortState.text = "Listening"
                binding.tvPortState.setTextColor(ContextCompat.getColor(context, R.color.success))
                binding.tvPortState.setBackgroundResource(R.drawable.badge_running)
            } else {
                binding.tvPortDetails.text = item.description ?: "Service inactive / Not listening"
                binding.tvPortState.text = "Inactive"
                binding.tvPortState.setTextColor(ContextCompat.getColor(context, R.color.label_tertiary))
                binding.tvPortState.setBackgroundResource(R.drawable.badge_stopped)
            }
        }
    }
}
