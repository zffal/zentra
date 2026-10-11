package com.example.serverdashboard.ui.alerts

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.serverdashboard.R
import com.example.serverdashboard.data.model.AlertHistoryItem
import com.example.serverdashboard.databinding.ItemAlertBinding
import com.example.serverdashboard.ui.util.Formatters

class AlertsAdapter(
    private var alerts: List<AlertHistoryItem> = emptyList(),
    private val onResolveClick: (AlertHistoryItem) -> Unit,
    private val onItemClick: ((AlertHistoryItem) -> Unit)? = null
) : RecyclerView.Adapter<AlertsAdapter.AlertViewHolder>() {

    fun updateData(newAlerts: List<AlertHistoryItem>) {
        val oldList = this.alerts
        val diffResult = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = newAlerts.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldList[oldItemPosition].id == newAlerts[newItemPosition].id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldList[oldItemPosition] == newAlerts[newItemPosition]
        })
        this.alerts = newAlerts
        diffResult.dispatchUpdatesTo(this)
    }

    fun getAlerts(): List<AlertHistoryItem> = alerts

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlertViewHolder {
        val binding = ItemAlertBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AlertViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AlertViewHolder, position: Int) {
        holder.bind(alerts[position])
    }

    override fun getItemCount(): Int = alerts.size

    inner class AlertViewHolder(private val binding: ItemAlertBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AlertHistoryItem) {
            binding.tvAlertTitle.text = item.displayTitle
            binding.tvAlertMessage.text = item.displayExplanation
            binding.tvAlertTimestamp.text = Formatters.formatTimestamp(item.timestamp)
            binding.tvAlertMetric.text = item.displayMetric

            val ctx = binding.root.context
            val severityColor: Int
            if (item.isResolved) {
                severityColor = ContextCompat.getColor(ctx, R.color.success)
                binding.tvAlertSeverityBadge.text = "Resolved"
                binding.tvAlertSeverityBadge.setBackgroundResource(R.drawable.badge_running)
                binding.tvAlertSeverityBadge.setTextColor(severityColor)
                binding.btnResolveAlert.visibility = View.GONE
            } else if (item.severity.equals("critical", ignoreCase = true)) {
                severityColor = ContextCompat.getColor(ctx, R.color.critical)
                binding.tvAlertSeverityBadge.text = "Critical"
                binding.tvAlertSeverityBadge.setBackgroundResource(R.drawable.badge_critical)
                binding.tvAlertSeverityBadge.setTextColor(severityColor)
                binding.btnResolveAlert.visibility = View.VISIBLE
            } else {
                severityColor = ContextCompat.getColor(ctx, R.color.warning)
                binding.tvAlertSeverityBadge.text = "Warning"
                binding.tvAlertSeverityBadge.setBackgroundResource(R.drawable.badge_warning)
                binding.tvAlertSeverityBadge.setTextColor(severityColor)
                binding.btnResolveAlert.visibility = View.VISIBLE
            }
            binding.viewAlertAccentBar.setBackgroundColor(severityColor)

            binding.btnResolveAlert.text = "Resolve"
            binding.btnResolveAlert.setOnClickListener {
                onResolveClick(item)
            }

            itemView.setOnClickListener {
                onItemClick?.invoke(item)
            }
        }
    }
}
