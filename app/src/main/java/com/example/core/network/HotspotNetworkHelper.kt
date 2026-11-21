package com.example.core.network

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Dynamic Hotspot & Wi-Fi network helper for Stage 2.5.
 *
 * Resolves Hotspot AP interfaces (`ap*`, `swlan*`, `softap*`) on the Host and dynamically
 * discovers the active Wi-Fi DHCP gateway IPv4 on the Client — with zero hardcoded IPs
 * and zero location permissions.
 */
object HotspotNetworkHelper {

    /**
     * Resolves the Host's active local IPv4 address, prioritizing tethering/AP interfaces
     * when [preferHotspotInterface] is true.
     */
    fun resolveHostIpv4(preferHotspotInterface: Boolean): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var apCandidate: String? = null
            var wifiCandidate: String? = null
            var anyPrivateCandidate: String? = null

            for (netIf in interfaces) {
                if (!netIf.isUp || netIf.isLoopback || netIf.isVirtual) continue
                val name = netIf.name.lowercase()
                val isAp = name.startsWith("ap") || name.startsWith("swlan") ||
                    name.startsWith("softap") || name.startsWith("rndis")
                val isWifi = name.startsWith("wlan") || name.startsWith("p2p")

                val addresses = netIf.inetAddresses
                for (addr in addresses) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val ip = addr.hostAddress ?: continue
                    if (!addr.isSiteLocalAddress) continue

                    if (isAp && apCandidate == null) apCandidate = ip
                    else if (isWifi && wifiCandidate == null) wifiCandidate = ip
                    else if (anyPrivateCandidate == null) anyPrivateCandidate = ip
                }
            }

            if (preferHotspotInterface) {
                apCandidate ?: wifiCandidate ?: anyPrivateCandidate
            } else {
                wifiCandidate ?: apCandidate ?: anyPrivateCandidate
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Dynamically reads the Client device's current Wi-Fi default gateway IPv4 address via
     * [ConnectivityManager.getLinkProperties]. When connected to another phone's Mobile Hotspot,
     * this gateway IP is almost always the Host phone's exact IPv4 address.
     */
    fun resolveWifiGatewayIpv4(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            val activeNetwork = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

            val linkProps = cm.getLinkProperties(activeNetwork) ?: return null
            val defaultGateway = linkProps.routes
                .firstOrNull { route ->
                    route.isDefaultRoute && route.gateway is Inet4Address &&
                        !(route.gateway?.isLoopbackAddress ?: true)
                }?.gateway?.hostAddress

            defaultGateway
                ?: linkProps.routes
                    .mapNotNull { it.gateway as? Inet4Address }
                    .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                    ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    fun openHotspotSettings(context: Context) {
        val candidates = listOf(
            Intent("android.settings.TETHER_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_WIFI_SETTINGS)
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolved = intent.resolveActivity(context.packageManager)
            if (resolved != null) {
                runCatching {
                    context.startActivity(intent)
                    return
                }
            }
        }
    }

    fun openWifiSettings(context: Context) {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}
