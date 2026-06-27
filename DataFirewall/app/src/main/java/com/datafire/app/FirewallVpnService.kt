package com.datafire.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.IOException

class FirewallVpnService : VpnService() {

    companion object {
        private const val TAG = "FirewallVpnService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "DataFirewallChannel"

        const val ACTION_START = "com.datafire.app.START_VPN"
        const val ACTION_STOP = "com.datafire.app.STOP_VPN"
        const val ACTION_UPDATE = "com.datafire.app.UPDATE_VPN"

        @Volatile
        var isRunning = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var drainThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopFirewall()
                START_NOT_STICKY
            }
            ACTION_START, ACTION_UPDATE, null -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                startFirewall()
                START_STICKY
            }
            else -> START_STICKY
        }
    }

    private fun startFirewall() {
        // Stop any existing VPN first
        stopVpnInterface()

        val prefs = PreferencesManager(this)
        val blockedPackages = prefs.getBlockedPackages()

        if (blockedPackages.isEmpty()) {
            Log.d(TAG, "No blocked packages, VPN not strictly needed but keeping alive")
        }

        try {
            val builder = Builder()
                .setSession("DataFirewall")
                .setMtu(1500)
                // Dummy VPN address — traffic goes nowhere unless forwarded
                .addAddress("10.99.99.1", 32)
                // Route ALL IPv4 traffic through the VPN
                .addRoute("0.0.0.0", 0)
                // Also route IPv6
                .addRoute("::", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")

            // Our own app must bypass VPN to avoid loops
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, "Could not exclude own package", e)
            }

            // Every ALLOWED app is added to DisallowedApplication list
            // → their traffic bypasses the VPN entirely (goes straight to internet)
            // BLOCKED apps are NOT in DisallowedApplication
            // → their traffic enters our VPN tunnel, where we simply discard it
            val pm = packageManager
            val allPackages = try {
                pm.getInstalledPackages(0).map { it.packageName }
            } catch (e: Exception) {
                emptyList()
            }

            var allowedCount = 0
            var blockedCount = 0

            for (pkg in allPackages) {
                if (pkg == packageName) continue
                if (pkg in blockedPackages) {
                    blockedCount++
                    // Do NOT add to disallowed → traffic enters VPN → gets dropped
                } else {
                    allowedCount++
                    try {
                        builder.addDisallowedApplication(pkg)
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.w(TAG, "Package not found: $pkg")
                    }
                }
            }

            Log.d(TAG, "VPN started: $allowedCount apps allowed, $blockedCount apps blocked")

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                isRunning = false
                return
            }

            isRunning = true
            updateNotification()

            // Start drain thread: read and discard all packets that come through
            // (these are packets from blocked apps that have nowhere to go)
            val pfd = vpnInterface!!
            drainThread = Thread({
                val buffer = ByteArray(32767)
                try {
                    val input = FileInputStream(pfd.fileDescriptor)
                    while (!Thread.currentThread().isInterrupted) {
                        try {
                            val length = input.read(buffer)
                            if (length <= 0) {
                                Thread.sleep(10)
                            }
                            // Packets are read and simply discarded — no forwarding
                        } catch (e: IOException) {
                            if (!Thread.currentThread().isInterrupted) {
                                Log.e(TAG, "Error reading from VPN", e)
                            }
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Drain thread error", e)
                }
                Log.d(TAG, "Drain thread exiting")
            }, "VPN-Drain")
            drainThread!!.isDaemon = true
            drainThread!!.start()

        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN", e)
            isRunning = false
        }
    }

    private fun stopFirewall() {
        stopVpnInterface()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopVpnInterface() {
        drainThread?.interrupt()
        drainThread = null

        try {
            vpnInterface?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null
        isRunning = false
    }

    override fun onDestroy() {
        stopVpnInterface()
        isRunning = false
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpnInterface()
        isRunning = false
        super.onRevoke()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Data Firewall",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Data Firewall VPN service notification"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val prefs = PreferencesManager(this)
        val blockedCount = prefs.getBlockedPackages().size

        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FirewallVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔒 Data Firewall Active")
            .setContentText("$blockedCount app${if (blockedCount == 1) "" else "s"} blocked from internet")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_delete, "Stop", pendingStop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }
}
