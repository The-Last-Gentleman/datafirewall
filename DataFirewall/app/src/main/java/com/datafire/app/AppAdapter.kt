package com.datafire.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.datafire.app.databinding.ItemAppBinding

class AppAdapter(
    private val onToggleBlock: (AppModel, Boolean) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

    private val allApps = mutableListOf<AppModel>()
    private val filteredApps = mutableListOf<AppModel>()
    private var searchQuery = ""
    var isMultiSelectMode = false
        private set

    inner class AppViewHolder(val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(app: AppModel) {
            binding.apply {
                tvAppName.text = app.appName
                tvPackageName.text = app.packageName
                ivAppIcon.setImageDrawable(app.icon)

                // Switch reflects blocked state
                switchBlock.isChecked = app.isBlocked
                switchBlock.setOnCheckedChangeListener(null)
                switchBlock.setOnCheckedChangeListener { _, isChecked ->
                    app.isBlocked = isChecked
                    onToggleBlock(app, isChecked)
                }

                // Checkbox for multiselect
                checkbox.isChecked = app.isSelected
                checkbox.visibility = if (isMultiSelectMode) android.view.View.VISIBLE else android.view.View.GONE

                // Card highlight when selected
                cardView.strokeWidth = if (app.isSelected && isMultiSelectMode) 3 else 0

                // System app badge
                tvSystemBadge.visibility = if (app.isSystemApp) android.view.View.VISIBLE else android.view.View.GONE

                // Click: in multiselect mode, toggle selection
                root.setOnClickListener {
                    if (isMultiSelectMode) {
                        app.isSelected = !app.isSelected
                        checkbox.isChecked = app.isSelected
                        cardView.strokeWidth = if (app.isSelected) 3 else 0
                        onSelectionChanged(getSelectedCount())
                    }
                }

                // Long click: enter multiselect
                root.setOnLongClickListener {
                    if (!isMultiSelectMode) {
                        isMultiSelectMode = true
                        app.isSelected = true
                        notifyDataSetChanged()
                        onSelectionChanged(getSelectedCount())
                    }
                    true
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(filteredApps[position])
    }

    override fun getItemCount() = filteredApps.size

    fun setApps(apps: List<AppModel>) {
        allApps.clear()
        allApps.addAll(apps)
        applyFilter()
    }

    fun filter(query: String) {
        searchQuery = query.lowercase()
        applyFilter()
    }

    private fun applyFilter() {
        val result = if (searchQuery.isEmpty()) {
            allApps.toList()
        } else {
            allApps.filter {
                it.appName.lowercase().contains(searchQuery) ||
                        it.packageName.lowercase().contains(searchQuery)
            }
        }
        val diffCallback = AppDiffCallback(filteredApps, result)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        filteredApps.clear()
        filteredApps.addAll(result)
        diffResult.dispatchUpdatesTo(this)
    }

    fun selectAll() {
        isMultiSelectMode = true
        filteredApps.forEach { it.isSelected = true }
        notifyDataSetChanged()
        onSelectionChanged(getSelectedCount())
    }

    fun deselectAll() {
        filteredApps.forEach { it.isSelected = false }
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun exitMultiSelectMode() {
        isMultiSelectMode = false
        filteredApps.forEach { it.isSelected = false }
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun getSelectedApps(): List<AppModel> = filteredApps.filter { it.isSelected }

    fun getSelectedCount(): Int = filteredApps.count { it.isSelected }

    fun blockSelectedApps() {
        filteredApps.filter { it.isSelected }.forEach {
            it.isBlocked = true
            it.isSelected = false
        }
        // Also update in allApps
        val selectedPkgs = allApps.filter { it.isSelected }.map { it.packageName }.toSet()
        allApps.filter { it.packageName in selectedPkgs }.forEach {
            it.isBlocked = true
            it.isSelected = false
        }
        isMultiSelectMode = false
        notifyDataSetChanged()
    }

    fun unblockSelectedApps() {
        val selectedPkgs = filteredApps.filter { it.isSelected }.map { it.packageName }.toSet()
        filteredApps.filter { it.packageName in selectedPkgs }.forEach {
            it.isBlocked = false
            it.isSelected = false
        }
        allApps.filter { it.packageName in selectedPkgs }.forEach {
            it.isBlocked = false
            it.isSelected = false
        }
        isMultiSelectMode = false
        notifyDataSetChanged()
    }

    fun getBlockedPackages(): Set<String> = allApps.filter { it.isBlocked }.map { it.packageName }.toSet()

    fun isAllSelected(): Boolean = filteredApps.isNotEmpty() && filteredApps.all { it.isSelected }

    class AppDiffCallback(
        private val oldList: List<AppModel>,
        private val newList: List<AppModel>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size
        override fun areItemsTheSame(o: Int, n: Int) = oldList[o].packageName == newList[n].packageName
        override fun areContentsTheSame(o: Int, n: Int) = oldList[o] == newList[n]
    }
}
