package com.example.serverdashboard.ui.connect

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.serverdashboard.data.model.ServerConfig
import com.example.serverdashboard.databinding.ItemSavedServerBinding

class SavedServersAdapter(
    private var servers: List<ServerConfig>,
    private var activeId: String?,
    private val onSelect: (ServerConfig) -> Unit,
    private val onEdit: (ServerConfig) -> Unit,
    private val onDelete: (ServerConfig) -> Unit
) : RecyclerView.Adapter<SavedServersAdapter.ServerViewHolder>() {

    fun updateData(newServers: List<ServerConfig>, newActiveId: String?) {
        this.servers = newServers
        this.activeId = newActiveId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        val binding = ItemSavedServerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ServerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        holder.bind(servers[position])
    }

    override fun getItemCount(): Int = servers.size

    inner class ServerViewHolder(private val binding: ItemSavedServerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(server: ServerConfig) {
            binding.tvServerName.text = server.name
            binding.tvServerUrl.text = server.url

            val isActive = server.id == activeId
            binding.tvDefaultBadge.visibility = if (isActive) View.VISIBLE else View.GONE

            binding.root.setOnClickListener {
                onSelect(server)
            }
            binding.btnEditServer.setOnClickListener {
                onEdit(server)
            }
            binding.btnDeleteServer.setOnClickListener {
                onDelete(server)
            }
        }
    }
}
