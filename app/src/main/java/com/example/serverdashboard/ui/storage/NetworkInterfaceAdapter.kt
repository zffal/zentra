package com.example.serverdashboard.ui.storage

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.serverdashboard.R
import com.example.serverdashboard.data.model.NetworkInterface
import com.example.serverdashboard.databinding.ItemNetworkInterfaceBinding
import com.example.serverdashboard.ui.util.Formatters

class NetworkInterfaceAdapter(
    private var interfaces: List<NetworkInterface> = emptyList()
) : RecyclerView.Adapter<NetworkInterfaceAdapter.InterfaceViewHolder>() {

    fun updateData(newInterfaces: List<NetworkInterface>) {
        this.interfaces = newInterfaces
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InterfaceViewHolder {
        val binding = ItemNetworkInterfaceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return InterfaceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: InterfaceViewHolder, position: Int) {
        holder.bind(interfaces[position])
    }

    override fun getItemCount(): Int = interfaces.size

    inner class InterfaceViewHolder(private val binding: ItemNetworkInterfaceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: NetworkInterface) {
            binding.tvIfName.text = item.name
            binding.tvIfIp.text = item.ip_address ?: "No IP assigned"

            val ctx = binding.root.context
            if (item.is_up) {
                binding.tvIfStatusBadge.text = "Up"
                binding.tvIfStatusBadge.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.success))
                binding.tvIfStatusBadge.setBackgroundResource(R.drawable.badge_running)
            } else {
                binding.tvIfStatusBadge.text = "Down"
                binding.tvIfStatusBadge.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.label_tertiary))
                binding.tvIfStatusBadge.setBackgroundResource(R.drawable.badge_stopped)
            }

            binding.tvIfRx.text = "Rx: " + Formatters.formatBytes(item.bytes_recv)
            binding.tvIfTx.text = "Tx: " + Formatters.formatBytes(item.bytes_sent)
        }
    }
}
