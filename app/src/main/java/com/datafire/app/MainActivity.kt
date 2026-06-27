package com.datafire.app

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.datafire.app.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: AppAdapter
    private lateinit var prefs: PreferencesManager

    companion object {
        private const val VPN_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager(this)
        setSupportActionBar(binding.toolbar)

        setupRecyclerView()
        setupFab()
        setupMultiSelectBar()
        loadApps()
        updateVpnStatusUI()
    }

    override fun onResume() {
        super.onResume()
        updateVpnStatusUI()
    }

    // ─── Setup ───────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = AppAdapter(
            onToggleBlock = { app, isBlocked ->
                if (isBlocked) {
                    prefs.addBlockedPackage(app.packageName)
                } else {
                    prefs.removeBlockedPackage(app.packageName)
                }
                if (FirewallVpnService.isRunning) {
                    restartVpnService()
                }
                updateBlockedCountBadge()
            },
            onSelectionChanged = { selectedCount ->
                updateMultiSelectBar(selectedCount)
            }
        )
        binding.recyclerView.adapter = adapter
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.itemAnimator = null // Smoother performance
    }

    private fun setupFab() {
        binding.fab.setOnClickListener {
            if (FirewallVpnService.isRunning) {
                showStopConfirmation()
            } else {
                requestVpnPermission()
            }
        }
    }

    private fun setupMultiSelectBar() {
        binding.multiSelectBar.visibility = View.GONE

        binding.btnSelectAll.setOnClickListener {
            if (adapter.isAllSelected()) {
                adapter.deselectAll()
            } else {
                adapter.selectAll()
            }
            updateSelectAllButton()
        }

        binding.btnBlockSelected.setOnClickListener {
            val count = adapter.getSelectedCount()
            if (count == 0) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Block $count App${if (count > 1) "s" else ""}")
                .setMessage("Block internet access for $count selected app${if (count > 1) "s" else ""}?")
                .setPositiveButton("Block") { _, _ ->
                    val selectedPkgs = adapter.getSelectedApps().map { it.packageName }.toSet()
                    val current = prefs.getBlockedPackages().toMutableSet()
                    current.addAll(selectedPkgs)
                    prefs.setBlockedPackages(current)
                    adapter.blockSelectedApps()
                    exitMultiSelect()
                    if (FirewallVpnService.isRunning) restartVpnService()
                    updateBlockedCountBadge()
                    Snackbar.make(binding.root, "Blocked $count app${if (count > 1) "s" else ""}", Snackbar.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnUnblockSelected.setOnClickListener {
            val count = adapter.getSelectedCount()
            if (count == 0) return@setOnClickListener
            val selectedPkgs = adapter.getSelectedApps().map { it.packageName }.toSet()
            val current = prefs.getBlockedPackages().toMutableSet()
            current.removeAll(selectedPkgs)
            prefs.setBlockedPackages(current)
            adapter.unblockSelectedApps()
            exitMultiSelect()
            if (FirewallVpnService.isRunning) restartVpnService()
            updateBlockedCountBadge()
            Snackbar.make(binding.root, "Unblocked $count app${if (count > 1) "s" else ""}", Snackbar.LENGTH_SHORT).show()
        }

        binding.btnCancelSelect.setOnClickListener {
            exitMultiSelect()
        }
    }

    // ─── App Loading ─────────────────────────────────────────────────────────

    private fun loadApps() {
        binding.progressBar.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val blockedPkgs = prefs.getBlockedPackages()
            val showSystem = prefs.isShowSystemApps()
            val pm = packageManager

            val apps = try {
                pm.getInstalledPackages(PackageManager.GET_META_DATA)
                    .filter { pkgInfo ->
                        val isSystem = (pkgInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        if (!showSystem && isSystem) return@filter false
                        // Skip our own app
                        pkgInfo.packageName != packageName
                    }
                    .map { pkgInfo ->
                        val appInfo = pkgInfo.applicationInfo
                        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        AppModel(
                            packageName = pkgInfo.packageName,
                            appName = pm.getApplicationLabel(appInfo).toString(),
                            icon = try { pm.getApplicationIcon(pkgInfo.packageName) } catch (e: Exception) { null },
                            uid = appInfo.uid,
                            isBlocked = pkgInfo.packageName in blockedPkgs,
                            isSystemApp = isSystem
                        )
                    }
                    .sortedWith(
                        compareBy<AppModel> { !it.isBlocked } // blocked first
                            .thenBy { it.appName.lowercase() }
                    )
            } catch (e: Exception) {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                adapter.setApps(apps)
                binding.progressBar.visibility = View.GONE
                binding.recyclerView.visibility = View.VISIBLE
                updateBlockedCountBadge()
                updateEmptyState(apps)
            }
        }
    }

    private fun updateEmptyState(apps: List<AppModel>) {
        if (apps.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }
    }

    // ─── VPN Control ─────────────────────────────────────────────────────────

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            // Need user consent
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            // Already have permission
            startVpnService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                startVpnService()
            } else {
                Toast.makeText(this, "VPN permission denied. Cannot activate firewall.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startVpnService() {
        val blocked = prefs.getBlockedPackages()
        if (blocked.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No Apps Blocked")
                .setMessage("You haven't blocked any apps yet. Toggle the switches next to apps you want to block, then start the firewall.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        prefs.setVpnEnabled(true)
        val intent = Intent(this, FirewallVpnService::class.java).apply {
            action = FirewallVpnService.ACTION_START
        }
        startForegroundService(intent)

        // Small delay then update UI
        binding.root.postDelayed({ updateVpnStatusUI() }, 500)
        Snackbar.make(binding.root, "🔒 Firewall activated — ${blocked.size} app${if (blocked.size == 1) "" else "s"} blocked", Snackbar.LENGTH_LONG).show()
    }

    private fun stopVpnService() {
        prefs.setVpnEnabled(false)
        val intent = Intent(this, FirewallVpnService::class.java).apply {
            action = FirewallVpnService.ACTION_STOP
        }
        startService(intent)
        binding.root.postDelayed({ updateVpnStatusUI() }, 300)
        Snackbar.make(binding.root, "🔓 Firewall deactivated", Snackbar.LENGTH_SHORT).show()
    }

    private fun restartVpnService() {
        if (!FirewallVpnService.isRunning) return
        val intent = Intent(this, FirewallVpnService::class.java).apply {
            action = FirewallVpnService.ACTION_UPDATE
        }
        startForegroundService(intent)
    }

    private fun showStopConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Stop Firewall")
            .setMessage("All apps will regain internet access. Stop the firewall?")
            .setPositiveButton("Stop") { _, _ -> stopVpnService() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateVpnStatusUI() {
        val running = FirewallVpnService.isRunning
        if (running) {
            binding.fab.setImageResource(android.R.drawable.ic_lock_lock)
            binding.fab.backgroundTintList = ContextCompat.getColorStateList(this, R.color.fab_active)
            binding.statusBanner.visibility = View.VISIBLE
            binding.statusBanner.setBackgroundColor(ContextCompat.getColor(this, R.color.green_active))
            binding.tvStatus.text = "🔒 Firewall Active"
        } else {
            binding.fab.setImageResource(android.R.drawable.ic_lock_idle_lock)
            binding.fab.backgroundTintList = ContextCompat.getColorStateList(this, R.color.fab_inactive)
            binding.statusBanner.visibility = View.VISIBLE
            binding.statusBanner.setBackgroundColor(ContextCompat.getColor(this, R.color.gray_inactive))
            binding.tvStatus.text = "🔓 Firewall Inactive — Tap ▶ to activate"
        }
    }

    private fun updateBlockedCountBadge() {
        val count = prefs.getBlockedPackages().size
        supportActionBar?.subtitle = if (count > 0) "$count app${if (count == 1) "" else "s"} blocked" else "No apps blocked"
    }

    // ─── Multi-select ─────────────────────────────────────────────────────────

    private fun updateMultiSelectBar(selectedCount: Int) {
        if (selectedCount > 0 || adapter.isMultiSelectMode) {
            binding.multiSelectBar.visibility = View.VISIBLE
            binding.tvSelectedCount.text = "$selectedCount selected"
            updateSelectAllButton()
        } else {
            binding.multiSelectBar.visibility = View.GONE
        }
        invalidateOptionsMenu()
    }

    private fun updateSelectAllButton() {
        binding.btnSelectAll.text = if (adapter.isAllSelected()) "Deselect All" else "Select All"
    }

    private fun exitMultiSelect() {
        adapter.exitMultiSelectMode()
        binding.multiSelectBar.visibility = View.GONE
        invalidateOptionsMenu()
    }

    // ─── Options Menu ─────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = "Search apps..."
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = false
            override fun onQueryTextChange(text: String?): Boolean {
                adapter.filter(text ?: "")
                return true
            }
        })
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val showSystem = prefs.isShowSystemApps()
        menu.findItem(R.id.action_toggle_system)?.title =
            if (showSystem) "Hide System Apps" else "Show System Apps"
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_system -> {
                val current = prefs.isShowSystemApps()
                prefs.setShowSystemApps(!current)
                loadApps()
                invalidateOptionsMenu()
                true
            }
            R.id.action_unblock_all -> {
                AlertDialog.Builder(this)
                    .setTitle("Unblock All Apps")
                    .setMessage("Allow internet access for all apps?")
                    .setPositiveButton("Unblock All") { _, _ ->
                        prefs.setBlockedPackages(emptySet())
                        loadApps()
                        if (FirewallVpnService.isRunning) stopVpnService()
                        Snackbar.make(binding.root, "All apps unblocked", Snackbar.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
            R.id.action_sort_blocked -> {
                // Reload to re-sort (blocked first)
                loadApps()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onBackPressed() {
        if (adapter.isMultiSelectMode) {
            exitMultiSelect()
        } else {
            super.onBackPressed()
        }
    }
}
