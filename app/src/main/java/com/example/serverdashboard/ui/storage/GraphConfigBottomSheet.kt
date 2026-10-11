package com.example.serverdashboard.ui.storage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.serverdashboard.data.model.GraphConfig
import com.example.serverdashboard.data.model.GraphMetric
import com.example.serverdashboard.data.model.GraphStyle
import com.example.serverdashboard.data.model.GraphTimeRange
import com.example.serverdashboard.databinding.BottomSheetGraphConfigBinding
import com.example.serverdashboard.ui.util.GlassEffectHelper
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class GraphConfigBottomSheet(
    private val currentConfig: GraphConfig,
    private val onConfigSaved: (GraphConfig) -> Unit,
    private val onResetToDefaults: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetGraphConfigBinding? = null
    private val binding get() = _binding!!

    private var selectedMetric: GraphMetric = currentConfig.metric
    private var selectedStyle: GraphStyle = currentConfig.style
    private var selectedRange: GraphTimeRange = currentConfig.timeRange

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            GlassEffectHelper.applyWindowBlur(window, 36)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetGraphConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindInitialState()
        setupListeners()
    }

    private fun bindInitialState() {
        // Metric selection
        when (currentConfig.metric) {
            GraphMetric.CPU -> binding.chipMetricCpu.isChecked = true
            GraphMetric.MEMORY -> binding.chipMetricMemory.isChecked = true
            GraphMetric.STORAGE -> binding.chipMetricStorage.isChecked = true
            GraphMetric.DISK_READ -> binding.chipMetricDiskRead.isChecked = true
            GraphMetric.DISK_WRITE -> binding.chipMetricDiskWrite.isChecked = true
            GraphMetric.NET_DOWNLOAD -> binding.chipMetricNetDown.isChecked = true
            GraphMetric.NET_UPLOAD -> binding.chipMetricNetUp.isChecked = true
        }

        updateStyleChipsForMetric(currentConfig.metric)

        // Style selection
        when (currentConfig.style) {
            GraphStyle.LINE -> binding.toggleGraphStyles.check(binding.btnStyleLine.id)
            GraphStyle.SEMI_CIRCLE -> binding.toggleGraphStyles.check(binding.btnStyleSemiCircle.id)
            GraphStyle.RING -> binding.toggleGraphStyles.check(binding.btnStyleRing.id)
            GraphStyle.BAR -> binding.toggleGraphStyles.check(binding.btnStyleBar.id)
        }

        // Range selection
        when (currentConfig.timeRange) {
            GraphTimeRange.ONE_MIN -> binding.toggleGraphTimeRanges.check(binding.btnRange1m.id)
            GraphTimeRange.FIVE_MIN -> binding.toggleGraphTimeRanges.check(binding.btnRange5m.id)
            GraphTimeRange.FIFTEEN_MIN -> binding.toggleGraphTimeRanges.check(binding.btnRange15m.id)
            GraphTimeRange.ONE_HOUR -> binding.toggleGraphTimeRanges.check(binding.btnRange1h.id)
            GraphTimeRange.TWENTY_FOUR_HOUR -> binding.toggleGraphTimeRanges.check(binding.btnRange24h.id)
        }

        // Preferences
        binding.switchSecondaryDetails.isChecked = currentConfig.showSecondaryValue
        binding.switchCompactHeight.isChecked = currentConfig.isCompact
    }

    private fun updateStyleChipsForMetric(metric: GraphMetric) {
        val allowed = metric.allowedStyles
        binding.toggleGraphStyles.setSegmentEnabled(binding.btnStyleRing.id, allowed.contains(GraphStyle.RING))
        binding.toggleGraphStyles.setSegmentEnabled(binding.btnStyleSemiCircle.id, allowed.contains(GraphStyle.SEMI_CIRCLE))
        binding.toggleGraphStyles.setSegmentEnabled(binding.btnStyleLine.id, allowed.contains(GraphStyle.LINE))
        binding.toggleGraphStyles.setSegmentEnabled(binding.btnStyleBar.id, allowed.contains(GraphStyle.BAR))

        if (!allowed.contains(selectedStyle)) {
            selectedStyle = allowed.first()
            when (selectedStyle) {
                GraphStyle.LINE -> binding.toggleGraphStyles.check(binding.btnStyleLine.id)
                GraphStyle.SEMI_CIRCLE -> binding.toggleGraphStyles.check(binding.btnStyleSemiCircle.id)
                GraphStyle.RING -> binding.toggleGraphStyles.check(binding.btnStyleRing.id)
                GraphStyle.BAR -> binding.toggleGraphStyles.check(binding.btnStyleBar.id)
            }
        }
    }

    private fun setupListeners() {
        binding.chipGroupMetrics.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            selectedMetric = when (checkedIds.first()) {
                binding.chipMetricCpu.id -> GraphMetric.CPU
                binding.chipMetricMemory.id -> GraphMetric.MEMORY
                binding.chipMetricStorage.id -> GraphMetric.STORAGE
                binding.chipMetricDiskRead.id -> GraphMetric.DISK_READ
                binding.chipMetricDiskWrite.id -> GraphMetric.DISK_WRITE
                binding.chipMetricNetDown.id -> GraphMetric.NET_DOWNLOAD
                binding.chipMetricNetUp.id -> GraphMetric.NET_UPLOAD
                else -> GraphMetric.CPU
            }
            updateStyleChipsForMetric(selectedMetric)
        }

        binding.toggleGraphStyles.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedStyle = when (checkedId) {
                binding.btnStyleLine.id -> GraphStyle.LINE
                binding.btnStyleSemiCircle.id -> GraphStyle.SEMI_CIRCLE
                binding.btnStyleRing.id -> GraphStyle.RING
                binding.btnStyleBar.id -> GraphStyle.BAR
                else -> GraphStyle.LINE
            }
        }

        binding.toggleGraphTimeRanges.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedRange = when (checkedId) {
                binding.btnRange1m.id -> GraphTimeRange.ONE_MIN
                binding.btnRange5m.id -> GraphTimeRange.FIVE_MIN
                binding.btnRange15m.id -> GraphTimeRange.FIFTEEN_MIN
                binding.btnRange1h.id -> GraphTimeRange.ONE_HOUR
                binding.btnRange24h.id -> GraphTimeRange.TWENTY_FOUR_HOUR
                else -> GraphTimeRange.ONE_MIN
            }
        }

        binding.btnApplyConfig.setOnClickListener {
            val updated = currentConfig.copy(
                metric = selectedMetric,
                style = selectedStyle,
                timeRange = selectedRange,
                showSecondaryValue = binding.switchSecondaryDetails.isChecked,
                isCompact = binding.switchCompactHeight.isChecked
            ).validated()

            onConfigSaved(updated)
            dismiss()
        }

        binding.btnResetDefaults.setOnClickListener {
            onResetToDefaults()
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "GraphConfigBottomSheet"
    }
}
