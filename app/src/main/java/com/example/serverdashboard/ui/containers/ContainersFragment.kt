package com.example.serverdashboard.ui.containers

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.serverdashboard.R
import com.example.serverdashboard.data.api.ApiClient
import com.example.serverdashboard.data.model.ContainerItem
import com.example.serverdashboard.databinding.FragmentContainersBinding
import com.example.serverdashboard.ui.main.MainViewModel
import kotlinx.coroutines.launch

class ContainersFragment : Fragment() {

    private var _binding: FragmentContainersBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var adapter: ContainersAdapter
    private var allContainers: List<ContainerItem> = emptyList()
    private var currentFilter: String = "all"
    private var searchQuery: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentContainersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ContainersAdapter(
            onLogsClick = { container ->
                val ctx = context ?: return@ContainersAdapter
                val intent = Intent(ctx, ContainerLogsActivity::class.java).apply {
                    putExtra("container_id", container.id)
                    putExtra("container_name", container.name)
                    putExtra("server_url", viewModel.serverUrl)
                    putExtra("auth_token", viewModel.authToken)
                }
                startActivity(intent)
            },
            onActionClick = { container, action ->
                confirmAndExecuteAction(container, action)
            }
        )

        val ctx = context ?: return
        binding.recyclerContainers.layoutManager = LinearLayoutManager(ctx)
        binding.recyclerContainers.adapter = adapter

        binding.toggleContainerFilter.check(R.id.btnFilterAll)
        binding.toggleContainerFilter.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentFilter = when (checkedId) {
                    R.id.btnFilterRunning -> "running"
                    R.id.btnFilterStopped -> "stopped"
                    else -> "all"
                }
                applyFilters()
            }
        }

        binding.etContainerSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim() ?: ""
                applyFilters()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.swipeRefreshContainers.setColorSchemeResources(R.color.accent_blue)
        binding.swipeRefreshContainers.setOnRefreshListener {
            loadContainers()
            binding.swipeRefreshContainers.postDelayed({
                _binding?.swipeRefreshContainers?.isRefreshing = false
            }, 800)
        }

        loadContainers()
    }

    private fun loadContainers() {
        val url = viewModel.serverUrl
        val token = viewModel.authToken
        if (url.isEmpty() || token.isEmpty()) return

        viewLifecycleOwner.lifecycleScope.launch {
            val result = ApiClient.instance.getContainers(url, token)
            val b = _binding ?: return@launch
            if (result.isSuccess) {
                allContainers = result.getOrDefault(emptyList())
                val running = allContainers.count { it.state.equals("running", ignoreCase = true) }
                val stopped = allContainers.size - running
                b.tvContainersSummaryHeader.text = getString(R.string.services_summary_format, running, stopped)
                applyFilters()
            } else {
                allContainers = emptyList()
                b.tvContainersSummaryHeader.text = "Docker service unavailable"
                b.tvNoContainers.text = "Docker service unavailable or no containers found."
                b.tvNoContainers.visibility = View.VISIBLE
                adapter.updateData(emptyList())
            }
        }
    }

    private fun applyFilters() {
        val b = _binding ?: return
        var filtered = allContainers

        if (currentFilter == "running") {
            filtered = filtered.filter { it.state.equals("running", ignoreCase = true) }
        } else if (currentFilter == "stopped") {
            filtered = filtered.filter { !it.state.equals("running", ignoreCase = true) }
        }

        if (searchQuery.isNotEmpty()) {
            filtered = filtered.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.image.contains(searchQuery, ignoreCase = true)
            }
        }

        b.tvNoContainers.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        adapter.updateData(filtered)
    }

    private fun confirmAndExecuteAction(container: ContainerItem, action: String) {
        val ctx = context ?: return
        val name = container.name.removePrefix("/")
        val actionVerb = action.replaceFirstChar { it.uppercase() }

        val message = when (action) {
            "stop" -> "Stop service '$name'? This will temporarily pause access to the application."
            "restart" -> "Restart service '$name'? This will restart the background application."
            else -> "Start service '$name'?"
        }

        AlertDialog.Builder(ctx)
            .setTitle("$actionVerb Service")
            .setMessage(message)
            .setPositiveButton(actionVerb) { _, _ ->
                executeContainerAction(container.id, action)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeContainerAction(containerId: String, action: String) {
        val url = viewModel.serverUrl
        val token = viewModel.authToken
        if (url.isEmpty() || token.isEmpty()) return

        viewLifecycleOwner.lifecycleScope.launch {
            val result = ApiClient.instance.containerAction(url, token, containerId, action)
            val ctx = context
            if (result.isSuccess) {
                ctx?.let { Toast.makeText(it, "Action completed successfully", Toast.LENGTH_SHORT).show() }
                loadContainers()
            } else {
                val err = result.exceptionOrNull()?.message ?: "Action failed"
                ctx?.let { Toast.makeText(it, err, Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
