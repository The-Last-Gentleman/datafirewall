package com.datafire.app

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "DataFirewallPrefs"
        private const val KEY_BLOCKED = "blocked_packages"
        private const val KEY_VPN_ENABLED = "vpn_enabled"
        private const val KEY_SHOW_SYSTEM = "show_system_apps"
    }

    fun getBlockedPackages(): Set<String> {
        return prefs.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()
    }

    fun setBlockedPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_BLOCKED, packages).apply()
    }

    fun addBlockedPackage(packageName: String) {
        val current = getBlockedPackages().toMutableSet()
        current.add(packageName)
        setBlockedPackages(current)
    }

    fun removeBlockedPackage(packageName: String) {
        val current = getBlockedPackages().toMutableSet()
        current.remove(packageName)
        setBlockedPackages(current)
    }

    fun isPackageBlocked(packageName: String): Boolean {
        return getBlockedPackages().contains(packageName)
    }

    fun isVpnEnabled(): Boolean {
        return prefs.getBoolean(KEY_VPN_ENABLED, false)
    }

    fun setVpnEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VPN_ENABLED, enabled).apply()
    }

    fun isShowSystemApps(): Boolean {
        return prefs.getBoolean(KEY_SHOW_SYSTEM, false)
    }

    fun setShowSystemApps(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_SYSTEM, show).apply()
    }
}
