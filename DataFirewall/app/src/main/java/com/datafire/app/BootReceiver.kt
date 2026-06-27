package com.datafire.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = PreferencesManager(context)
            if (prefs.isVpnEnabled() && prefs.getBlockedPackages().isNotEmpty()) {
                val serviceIntent = Intent(context, FirewallVpnService::class.java).apply {
                    action = FirewallVpnService.ACTION_START
                }
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
