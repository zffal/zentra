package com.example.serverdashboard.ui.storage

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.serverdashboard.R
import com.example.serverdashboard.data.model.DailyUsageItem
import com.example.serverdashboard.databinding.ItemDailyUsageBinding
import com.example.serverdashboard.ui.util.Formatters

class DailyUsageAdapter : RecyclerView.Adapter<DailyUsageAdapter.ViewHolder>() {

    private var items: List<DailyUsageItem> = emptyList()
    private var serverTimezone: String? = null
    private var rankMap: Map<String, Int> = emptyMap()

    fun updateData(newItems: List<DailyUsageItem>, timezone: String?, topRanks: Map<String, Int>) {
        this.items = newItems
        this.serverTimezone = timezone
        this.rankMap = topRanks
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDailyUsageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemDailyUsageBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DailyUsageItem) {
            val ctx = binding.root.context
            val dateFormatted = Formatters.formatUsageDate(item.date, serverTimezone)
            binding.tvUsageDate.text = dateFormatted

            val downFormatted = Formatters.formatBytes(item.rx)
            val upFormatted = Formatters.formatBytes(item.tx)
            binding.tvUsageBreakdown.text = "↓ $downFormatted  •  ↑ $upFormatted"

            binding.tvUsageTotal.text = Formatters.formatBytes(item.total)

            val rank = rankMap[item.date]
            if (rank != null && rank in 1..3 && item.total > 0L) {
                binding.tvRankBadge.visibility = View.VISIBLE
                binding.tvRankBadge.text = "#$rank"
                when (rank) {
                    1 -> {
                        binding.tvRankBadge.setTextColor(ContextCompat.getColor(ctx, R.color.warning))
                        binding.tvRankBadge.setBackgroundResource(R.drawable.badge_warning)
                    }
                    2 -> {
                        binding.tvRankBadge.setTextColor(ContextCompat.getColor(ctx, R.color.accent))
                        binding.tvRankBadge.setBackgroundResource(R.drawable.badge_running)
                    }
                    3 -> {
                        binding.tvRankBadge.setTextColor(ContextCompat.getColor(ctx, R.color.accent_tech))
                        binding.tvRankBadge.setBackgroundResource(R.drawable.badge_running)
                    }
                }
            } else {
                binding.tvRankBadge.visibility = View.GONE
            }
        }
    }
}
