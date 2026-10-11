package com.example.serverdashboard.ui.storage

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.serverdashboard.R
import com.example.serverdashboard.data.model.FirewallRule
import com.example.serverdashboard.databinding.ItemFirewallRuleBinding
import java.util.Locale

class FirewallRulesAdapter : RecyclerView.Adapter<FirewallRulesAdapter.RuleViewHolder>() {

    private val rules = mutableListOf<FirewallRule>()

    fun updateData(newRules: List<FirewallRule>) {
        rules.clear()
        rules.addAll(newRules)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuleViewHolder {
        val binding = ItemFirewallRuleBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RuleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RuleViewHolder, position: Int) {
        holder.bind(rules[position])
    }

    override fun getItemCount(): Int = rules.size

    class RuleViewHolder(
        private val binding: ItemFirewallRuleBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(rule: FirewallRule) {
            val ctx = binding.root.context
            binding.tvRuleTarget.text = rule.target.ifEmpty { "Any Port" }
            binding.tvRuleFrom.text = if (rule.from.isNotEmpty()) rule.from else "Anywhere"

            val actionUpper = rule.action.uppercase(Locale.US)
            val actionDisplay = if (actionUpper.contains("ALLOW") || actionUpper.contains("ACCEPT")) {
                binding.tvRuleAction.setBackgroundResource(R.drawable.badge_running)
                binding.tvRuleAction.setTextColor(ContextCompat.getColor(ctx, R.color.success))
                "Allow"
            } else if (actionUpper.contains("DENY") || actionUpper.contains("REJECT") || actionUpper.contains("DROP")) {
                binding.tvRuleAction.setBackgroundResource(R.drawable.badge_critical)
                binding.tvRuleAction.setTextColor(ContextCompat.getColor(ctx, R.color.critical))
                "Deny"
            } else {
                binding.tvRuleAction.setBackgroundResource(R.drawable.badge_warning)
                binding.tvRuleAction.setTextColor(ContextCompat.getColor(ctx, R.color.warning))
                rule.action.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }.ifEmpty { "Rule" }
            }
            binding.tvRuleAction.text = actionDisplay
        }
    }
}
