package com.example.core.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class LanServiceAdvertisement(
    val roomId: String,
    val roomCode: String,
    val hostName: String,
    val timeControlId: String,
    val timeControlLabel: String,
    val connectedPlayers: Int,
    val roomSecret: String,
    val port: Int
)

/**
 * Android NSD (mDNS / DNS-SD) room advertisement and discovery manager for Wi-Fi LAN & Hotspot.
 *
 * Acquires [WifiManager.MulticastLock] only while actively advertising or discovering and
 * guarantees clean release on stop/unregister. Serializes NSD resolutions to avoid Android's
 * `FAILURE_ALREADY_ACTIVE` error when multiple devices advertise simultaneously.
 */
@Singleton
class LanDiscoveryManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resolveMutex = Mutex()
    private val lock = Any()

    private val nsdManager: NsdManager? by lazy {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    private val wifiManager: WifiManager? by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registeredServiceName: String? = null

    fun registerService(
        advertisement: LanServiceAdvertisement,
        onRegistered: (serviceName: String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val manager = nsdManager
        if (manager == null) {
            onError("NSD недоступен на этом устройстве")
            return
        }

        unregisterService()
        acquireMulticastLock()

        val sanitizedCode = advertisement.roomCode.replace(Regex("[^A-Za-z0-9]"), "")
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "${SERVICE_NAME_PREFIX}_$sanitizedCode"
            serviceType = SERVICE_TYPE
            port = advertisement.port
            setAttribute(ATTR_ROOM_ID, advertisement.roomId)
            setAttribute(ATTR_ROOM_CODE, advertisement.roomCode)
            setAttribute(ATTR_HOST_NAME, advertisement.hostName.take(28))
            setAttribute(ATTR_TIME_CONTROL_ID, advertisement.timeControlId)
            setAttribute(ATTR_TIME_CONTROL_LABEL, advertisement.timeControlLabel)
            setAttribute(ATTR_PLAYERS, advertisement.connectedPlayers.toString())
            setAttribute(ATTR_ROOM_SECRET, advertisement.roomSecret)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                synchronized(lock) {
                    registeredServiceName = info.serviceName
                }
                Log.i(TAG, "NSD service registered: ${info.serviceName} on port ${advertisement.port}")
                onRegistered(info.serviceName)
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: code=$errorCode")
                onError("Не удалось опубликовать комнату в локальной сети (код $errorCode)")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD service unregistered: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD unregistration failed: code=$errorCode")
            }
        }

        synchronized(lock) {
            registrationListener = listener
        }
        try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to register NSD service", ex)
            synchronized(lock) {
                registrationListener = null
            }
            releaseMulticastLockIfIdle()
            onError(ex.message ?: "Ошибка публикации NSD")
        }
    }

    fun unregisterService() {
        val manager = nsdManager
        val listener = synchronized(lock) {
            val current = registrationListener
            registrationListener = null
            registeredServiceName = null
            current
        }
        if (manager != null && listener != null) {
            try {
                manager.unregisterService(listener)
            } catch (ex: IllegalArgumentException) {
                Log.w(TAG, "NSD listener already unregistered: ${ex.message}")
            } catch (ex: Exception) {
                Log.w(TAG, "Error unregistering NSD service", ex)
            }
        }
        releaseMulticastLockIfIdle()
    }

    fun startDiscovery(
        mode: ConnectionType = ConnectionType.WIFI_LAN,
        onRoomFound: (DiscoveredRoom) -> Unit,
        onRoomLost: (serviceName: String) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        val manager = nsdManager
        if (manager == null) {
            onError("Служба NSD недоступна на устройстве")
            return
        }

        stopDiscovery()
        acquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.i(TAG, "NSD discovery started for $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("_chesslan")) return
                val ownName = synchronized(lock) { registeredServiceName }
                if (ownName != null && serviceInfo.serviceName == ownName) return

                scope.launch {
                    val resolved = resolveServiceSafely(manager, serviceInfo) ?: return@launch
                    val room = toDiscoveredRoom(resolved, mode) ?: return@launch
                    onRoomFound(room)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "NSD service lost: ${serviceInfo.serviceName}")
                onRoomLost(serviceInfo.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "NSD discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD startDiscovery failed: code=$errorCode")
                stopDiscovery()
                onError("Не удалось запустить поиск комнат (код $errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD stopDiscovery failed: code=$errorCode")
            }
        }

        synchronized(lock) {
            discoveryListener = listener
        }
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to start NSD discovery", ex)
            synchronized(lock) {
                discoveryListener = null
            }
            releaseMulticastLockIfIdle()
            onError(ex.message ?: "Ошибка запуска поиска NSD")
        }
    }

    fun stopDiscovery() {
        val manager = nsdManager
        val listener = synchronized(lock) {
            val current = discoveryListener
            discoveryListener = null
            current
        }
        if (manager != null && listener != null) {
            try {
                manager.stopServiceDiscovery(listener)
            } catch (ex: IllegalArgumentException) {
                Log.w(TAG, "NSD discovery already stopped: ${ex.message}")
            } catch (ex: Exception) {
                Log.w(TAG, "Error stopping NSD discovery", ex)
            }
        }
        releaseMulticastLockIfIdle()
    }

    fun releaseAll() {
        unregisterService()
        stopDiscovery()
    }

    /**
     * Dynamically inspects active network interfaces for a non-loopback local IPv4 address.
     * Never returns a hardcoded IP.
     */
    fun resolveLocalIpv4Address(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var fallbackPrivate: String? = null
            for (networkInterface in interfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback || networkInterface.isVirtual) {
                    continue
                }
                val name = networkInterface.name.lowercase()
                val isWifiLike = name.startsWith("wlan") || name.startsWith("ap") ||
                    name.startsWith("swlan") || name.startsWith("p2p")
                val addresses = networkInterface.inetAddresses
                for (address in addresses) {
                    if (address !is Inet4Address || address.isLoopbackAddress) continue
                    val hostAddress = address.hostAddress ?: continue
                    if (isWifiLike && address.isSiteLocalAddress) {
                        return hostAddress
                    }
                    if (address.isSiteLocalAddress && fallbackPrivate == null) {
                        fallbackPrivate = hostAddress
                    }
                }
            }
            fallbackPrivate
        } catch (ex: Exception) {
            Log.w(TAG, "Unable to resolve local IPv4 address", ex)
            null
        }
    }

    private suspend fun resolveServiceSafely(
        manager: NsdManager,
        serviceInfo: NsdServiceInfo
    ): NsdServiceInfo? = resolveMutex.withLock {
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val callback = object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                            if (continuation.isActive) continuation.resume(null)
                        }

                        override fun onServiceUpdated(updatedInfo: NsdServiceInfo) {
                            try {
                                manager.unregisterServiceInfoCallback(this)
                            } catch (_: Exception) {
                            }
                            if (continuation.isActive) continuation.resume(updatedInfo)
                        }

                        override fun onServiceLost() {
                            try {
                                manager.unregisterServiceInfoCallback(this)
                            } catch (_: Exception) {
                            }
                            if (continuation.isActive) continuation.resume(null)
                        }

                        override fun onServiceInfoCallbackUnregistered() = Unit
                    }
                    continuation.invokeOnCancellation {
                        try {
                            manager.unregisterServiceInfoCallback(callback)
                        } catch (_: Exception) {
                        }
                    }
                    try {
                        manager.registerServiceInfoCallback(serviceInfo, Runnable::run, callback)
                    } catch (ex: Exception) {
                        Log.w(TAG, "registerServiceInfoCallback failed", ex)
                        if (continuation.isActive) continuation.resume(null)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    manager.resolveService(
                        serviceInfo,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                Log.w(TAG, "NSD resolve failed for ${info.serviceName}: code=$errorCode")
                                if (continuation.isActive) continuation.resume(null)
                            }

                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                if (continuation.isActive) continuation.resume(resolved)
                            }
                        }
                    )
                }
            }
        }
    }

    private fun toDiscoveredRoom(info: NsdServiceInfo, mode: ConnectionType): DiscoveredRoom? {
        val hostAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
                ?: info.hostAddresses.firstOrNull()?.hostAddress
        } else {
            @Suppress("DEPRECATION")
            info.host?.hostAddress
        } ?: return null

        val port = info.port
        if (port <= 0) return null

        val attrs = info.attributes
        fun attr(key: String): String =
            attrs[key]?.toString(Charsets.UTF_8)?.trim().orEmpty()

        val roomId = attr(ATTR_ROOM_ID).ifBlank { info.serviceName }
        val roomCode = attr(ATTR_ROOM_CODE)
        val hostName = attr(ATTR_HOST_NAME).ifBlank { info.serviceName }
        val timeControlId = attr(ATTR_TIME_CONTROL_ID).ifBlank { "10+0" }
        val timeControlLabel = attr(ATTR_TIME_CONTROL_LABEL).ifBlank { timeControlId }
        val players = attr(ATTR_PLAYERS).toIntOrNull() ?: 1
        val roomSecret = attr(ATTR_ROOM_SECRET)

        return DiscoveredRoom(
            roomId = roomId,
            hostName = hostName,
            hostAddress = hostAddress,
            port = port,
            timeControlLabel = timeControlLabel,
            connectedPlayers = players,
            connectionType = mode,
            roomCode = roomCode,
            roomSecret = roomSecret,
            timeControlId = timeControlId,
            serviceName = info.serviceName
        )
    }

    private fun acquireMulticastLock() {
        synchronized(lock) {
            if (multicastLock?.isHeld == true) return
            try {
                val created = wifiManager?.createMulticastLock("ChessLAN_NSD_Lock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
                multicastLock = created
            } catch (ex: Exception) {
                Log.w(TAG, "Unable to acquire Wi-Fi MulticastLock", ex)
            }
        }
    }

    private fun releaseMulticastLockIfIdle() {
        synchronized(lock) {
            if (registrationListener != null || discoveryListener != null) return
            try {
                if (multicastLock?.isHeld == true) {
                    multicastLock?.release()
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Error releasing MulticastLock", ex)
            } finally {
                multicastLock = null
            }
        }
    }

    companion object {
        private const val TAG = "LanDiscoveryManager"
        const val SERVICE_TYPE: String = "_chesslan._tcp."
        private const val SERVICE_NAME_PREFIX: String = "ChessLAN"
        private const val RESOLVE_TIMEOUT_MS: Long = 6_000L

        private const val ATTR_ROOM_ID = "rid"
        private const val ATTR_ROOM_CODE = "rc"
        private const val ATTR_HOST_NAME = "hn"
        private const val ATTR_TIME_CONTROL_ID = "tc"
        private const val ATTR_TIME_CONTROL_LABEL = "tcl"
        private const val ATTR_PLAYERS = "pl"
        private const val ATTR_ROOM_SECRET = "sec"
    }
}
