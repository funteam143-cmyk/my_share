package com.example.util

import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

object NetworkUtils {
    const val DEFAULT_BEACON_PORT = 52345
    const val DEFAULT_TRANSFER_PORT = 52346

    /**
     * Resolves the primary local IPv4 address of the active Wi-Fi or Hotspot interface.
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // Prioritize Wi-Fi and P2P interfaces (wlan0, p2p0, ap0)
            for (intf in interfaces.sortedByDescending { isPreferredInterface(it.name) }) {
                if (!intf.isUp || intf.isLoopback) continue
                val addresses = Collections.list(intf.inetAddresses)
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (!host.startsWith("127.")) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun isPreferredInterface(name: String): Int {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.contains("p2p") -> 3
            lower.contains("wlan") -> 5
            lower.contains("ap") || lower.contains("swlan") -> 4
            else -> 0
        }
    }

    /**
     * Derives a friendly device name (e.g. "FK-Pixel 8", "FK-Galaxy S24").
     */
    fun getFriendlyDeviceName(): String {
        val model = Build.MODEL ?: "Android"
        val manufacturer = Build.MANUFACTURER ?: ""
        val combined = if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }.trim()
        val clean = combined.replace(Regex("[^a-zA-Z0-9 -]"), "").take(20)
        return "FK-$clean"
    }

    /**
     * Returns broadcast address for the local subnet.
     */
    fun getBroadcastAddress(): InetAddress {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (intfAddr in intf.interfaceAddresses) {
                    val broadcast = intfAddr.broadcast
                    if (broadcast != null && broadcast is Inet4Address) {
                        return broadcast
                    }
                }
            }
        } catch (_: Exception) {}
        return InetAddress.getByName("255.255.255.255")
    }
}
