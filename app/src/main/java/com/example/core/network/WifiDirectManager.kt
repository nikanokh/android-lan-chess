package com.example.core.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class WifiDirectStatus {
    IDLE,
    UNSUPPORTED,
    DISABLED,
    PERMISSIONS_REQUIRED,
    DISCOVERING,
    GROUP_OWNER_READY,
    CONNECTING_PEER,
    CONNECTED_AS_CLIENT,
    ERROR
}

data class WifiDirectServiceRecord(
    val deviceAddress: String,
    val roomId: String,
    val roomCode: String,
    val hostName: String,
    val timeControlId: String,
    val timeControlLabel: String,
    val port: Int,
    val roomSecret: String
)

data class WifiDirectPeer(
    val deviceAddress: String,
    val deviceName: String,
    val status: Int,
    val isGroupOwner: Boolean,
    val serviceRecord: WifiDirectServiceRecord? = null
) {
    val hasChessRoom: Boolean
        get() = serviceRecord != null && serviceRecord.port in 1..65535
}

data class WifiDirectState(
    val isSupported: Boolean = true,
    val isWifiP2pEnabled: Boolean = true,
    val hasPermissions: Boolean = false,
    val status: WifiDirectStatus = WifiDirectStatus.IDLE,
    val peers: List<WifiDirectPeer> = emptyList(),
    val groupFormed: Boolean = false,
    val isGroupOwner: Boolean = false,
    val groupOwnerAddress: String? = null,
    val groupNetworkName: String? = null,
    val groupPassphrase: String? = null,
    val lastError: String? = null
)

/**
 * Manages direct device-to-device Android Wi-Fi Direct (P2P) connections via [WifiP2pManager].
 *
 * Uses Android 13+ NEARBY_WIFI_DEVICES (neverForLocation) and legacy ACCESS_FINE_LOCATION (API <= 32).
 * Host creates a P2P group + DNS-SD local service advertisement (`_chesslan._tcp`), and once the P2P
 * link is established, both peers communicate over the exact same Ktor WebSocket protocol.
 */
@Singleton
class WifiDirectManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false

    private val discoveredRecords = ConcurrentHashMap<String, WifiDirectServiceRecord>()
    private var activeServiceInfo: WifiP2pDnsSdServiceInfo? = null
    private var activeServiceRequest: WifiP2pDnsSdServiceRequest? = null
    private var onGroupOwnerAddressReady: ((String) -> Unit)? = null
    private var onClientConnectedToGroupOwner: ((hostIp: String, record: WifiDirectServiceRecord?) -> Unit)? = null
    private var pendingTargetDeviceAddress: String? = null

    private val _state = MutableStateFlow(
        WifiDirectState(
            isSupported = manager != null,
            hasPermissions = hasRequiredPermissions(),
            status = if (manager == null) WifiDirectStatus.UNSUPPORTED else WifiDirectStatus.IDLE
        )
    )
    val state: StateFlow<WifiDirectState> = _state.asStateFlow()

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val p2pState = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    )
                    val enabled = p2pState == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    _state.update { current ->
                        current.copy(
                            isWifiP2pEnabled = enabled,
                            status = if (!enabled) WifiDirectStatus.DISABLED else current.status,
                            lastError = if (!enabled) "Wi-Fi Direct выключен" else current.lastError
                        )
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    requestPeersInternal()
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                    } else {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }
                    if (networkInfo?.isConnected == true) {
                        requestConnectionAndGroupInfo()
                    } else {
                        _state.update { current ->
                            current.copy(
                                groupFormed = false,
                                isGroupOwner = false,
                                groupOwnerAddress = null,
                                groupNetworkName = null,
                                groupPassphrase = null
                            )
                        }
                    }
                }
            }
        }
    }

    fun hasRequiredPermissions(): Boolean {
        return requiredRuntimePermissions(Build.VERSION.SDK_INT).all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun refreshPermissionState(): Boolean {
        val granted = hasRequiredPermissions()
        _state.update { current ->
            current.copy(
                hasPermissions = granted,
                status = when {
                    !current.isSupported -> WifiDirectStatus.UNSUPPORTED
                    !granted -> WifiDirectStatus.PERMISSIONS_REQUIRED
                    current.status == WifiDirectStatus.PERMISSIONS_REQUIRED -> WifiDirectStatus.IDLE
                    else -> current.status
                }
            )
        }
        return granted
    }

    @SuppressLint("MissingPermission")
    fun startHostGroup(
        advertisement: LanServiceAdvertisement,
        onGroupOwnerIpReady: (String) -> Unit
    ) {
        onGroupOwnerAddressReady = onGroupOwnerIpReady
        val p2p = manager
        if (p2p == null) {
            _state.update {
                it.copy(
                    isSupported = false,
                    status = WifiDirectStatus.UNSUPPORTED,
                    lastError = "Устройство не поддерживает Wi-Fi Direct"
                )
            }
            return
        }
        if (!refreshPermissionState()) {
            _state.update { it.copy(status = WifiDirectStatus.PERMISSIONS_REQUIRED) }
            return
        }

        val ch = ensureChannelAndReceiver(p2p) ?: return
        val record = buildTxtRecord(advertisement)
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            "ChessLAN_${advertisement.roomCode}",
            SERVICE_TYPE,
            record
        )
        activeServiceInfo = serviceInfo

        _state.update {
            it.copy(
                status = WifiDirectStatus.DISCOVERING,
                lastError = null
            )
        }

        p2p.clearLocalServices(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                registerServiceAndCreateGroup(p2p, ch, serviceInfo)
            }

            override fun onFailure(reason: Int) {
                registerServiceAndCreateGroup(p2p, ch, serviceInfo)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun registerServiceAndCreateGroup(
        p2p: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        serviceInfo: WifiP2pDnsSdServiceInfo
    ) {
        p2p.addLocalService(ch, serviceInfo, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Wi-Fi Direct DNS-SD service added")
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "Wi-Fi Direct addLocalService failed: ${reasonCodeToText(reason)}")
            }
        })

        p2p.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Wi-Fi Direct group creation initiated")
                requestConnectionAndGroupInfo()
            }

            override fun onFailure(reason: Int) {
                if (reason == WifiP2pManager.BUSY) {
                    // Group may already be active — query existing connection/group info
                    requestConnectionAndGroupInfo()
                } else {
                    _state.update {
                        it.copy(
                            status = WifiDirectStatus.ERROR,
                            lastError = "Не удалось создать P2P-группу: ${reasonCodeToText(reason)}"
                        )
                    }
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery(
        onClientLinkedToHost: (hostIp: String, record: WifiDirectServiceRecord?) -> Unit
    ) {
        onClientConnectedToGroupOwner = onClientLinkedToHost
        val p2p = manager
        if (p2p == null) {
            _state.update { it.copy(isSupported = false, status = WifiDirectStatus.UNSUPPORTED) }
            return
        }
        if (!refreshPermissionState()) {
            _state.update { it.copy(status = WifiDirectStatus.PERMISSIONS_REQUIRED) }
            return
        }

        val ch = ensureChannelAndReceiver(p2p) ?: return
        discoveredRecords.clear()

        p2p.setDnsSdResponseListeners(
            ch,
            { _, _, _ -> },
            { fullDomainName, txtRecordMap, srcDevice ->
                if (fullDomainName.contains("chesslan", ignoreCase = true) || txtRecordMap.containsKey("port")) {
                    val parsed = parseWifiDirectTxtRecord(
                        txtRecord = txtRecordMap,
                        deviceAddress = srcDevice.deviceAddress,
                        fallbackName = srcDevice.deviceName.orEmpty()
                    )
                    if (parsed != null) {
                        discoveredRecords[srcDevice.deviceAddress] = parsed
                        mergePeerWithRecord(srcDevice, parsed)
                    }
                }
            }
        )

        val request = WifiP2pDnsSdServiceRequest.newInstance()
        activeServiceRequest = request

        _state.update {
            it.copy(
                status = WifiDirectStatus.DISCOVERING,
                lastError = null
            )
        }

        p2p.clearServiceRequests(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = startServiceAndPeerDiscovery(p2p, ch, request)
            override fun onFailure(reason: Int) = startServiceAndPeerDiscovery(p2p, ch, request)
        })
    }

    @SuppressLint("MissingPermission")
    private fun startServiceAndPeerDiscovery(
        p2p: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        request: WifiP2pDnsSdServiceRequest
    ) {
        p2p.addServiceRequest(ch, request, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                p2p.discoverServices(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit
                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "discoverServices failed: ${reasonCodeToText(reason)}")
                    }
                })
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "addServiceRequest failed: ${reasonCodeToText(reason)}")
            }
        })

        p2p.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                requestPeersInternal()
            }

            override fun onFailure(reason: Int) {
                _state.update {
                    it.copy(
                        status = WifiDirectStatus.ERROR,
                        lastError = "Поиск Wi-Fi Direct недоступен: ${reasonCodeToText(reason)}"
                    )
                }
            }
        })

        requestConnectionAndGroupInfo()
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(deviceAddress: String) {
        val p2p = manager ?: return
        if (!refreshPermissionState()) return
        val ch = ensureChannelAndReceiver(p2p) ?: return

        pendingTargetDeviceAddress = deviceAddress
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0 // Prefer connecting as Client to Host's P2P Group Owner
        }

        _state.update {
            it.copy(
                status = WifiDirectStatus.CONNECTING_PEER,
                lastError = null
            )
        }

        p2p.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Wi-Fi Direct connect initiated to $deviceAddress")
            }

            override fun onFailure(reason: Int) {
                _state.update {
                    it.copy(
                        status = WifiDirectStatus.ERROR,
                        lastError = "Не удалось подключиться по Wi-Fi Direct: ${reasonCodeToText(reason)}"
                    )
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        val p2p = manager ?: return
        val ch = channel ?: return
        runCatching {
            p2p.stopPeerDiscovery(ch, null)
            p2p.clearServiceRequests(ch, null)
        }
        activeServiceRequest = null
    }

    @SuppressLint("MissingPermission")
    fun releaseSession() {
        val p2p = manager
        val ch = channel
        if (p2p != null && ch != null) {
            runCatching { p2p.stopPeerDiscovery(ch, null) }
            runCatching { p2p.clearServiceRequests(ch, null) }
            runCatching { p2p.clearLocalServices(ch, null) }
            runCatching { p2p.removeGroup(ch, null) }
        }
        activeServiceInfo = null
        activeServiceRequest = null
        onGroupOwnerAddressReady = null
        onClientConnectedToGroupOwner = null
        pendingTargetDeviceAddress = null
        discoveredRecords.clear()

        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(p2pReceiver) }
            receiverRegistered = false
        }

        _state.update {
            WifiDirectState(
                isSupported = manager != null,
                isWifiP2pEnabled = it.isWifiP2pEnabled,
                hasPermissions = hasRequiredPermissions(),
                status = if (manager == null) WifiDirectStatus.UNSUPPORTED else WifiDirectStatus.IDLE
            )
        }
    }

    private fun ensureChannelAndReceiver(p2p: WifiP2pManager): WifiP2pManager.Channel? {
        if (channel == null) {
            channel = p2p.initialize(context, Looper.getMainLooper()) {
                _state.update { it.copy(status = WifiDirectStatus.IDLE) }
            }
        }
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(
                context,
                p2pReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        return channel
    }

    @SuppressLint("MissingPermission")
    private fun requestPeersInternal() {
        val p2p = manager ?: return
        val ch = channel ?: return
        if (!hasRequiredPermissions()) return
        p2p.requestPeers(ch) { peerList: WifiP2pDeviceList? ->
            val mapped = peerList?.deviceList?.map { device ->
                WifiDirectPeer(
                    deviceAddress = device.deviceAddress,
                    deviceName = device.deviceName.ifBlank { device.deviceAddress },
                    status = device.status,
                    isGroupOwner = device.isGroupOwner,
                    serviceRecord = discoveredRecords[device.deviceAddress]
                )
            }.orEmpty().sortedByDescending { it.hasChessRoom }
            _state.update { it.copy(peers = mapped) }
        }
    }

    private fun mergePeerWithRecord(device: WifiP2pDevice, record: WifiDirectServiceRecord) {
        _state.update { current ->
            val updated = current.peers.toMutableList()
            val idx = updated.indexOfFirst { it.deviceAddress == device.deviceAddress }
            val peer = WifiDirectPeer(
                deviceAddress = device.deviceAddress,
                deviceName = device.deviceName.ifBlank { record.hostName.ifBlank { device.deviceAddress } },
                status = device.status,
                isGroupOwner = device.isGroupOwner,
                serviceRecord = record
            )
            if (idx >= 0) {
                updated[idx] = peer
            } else {
                updated.add(0, peer)
            }
            current.copy(peers = updated.sortedByDescending { it.hasChessRoom })
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionAndGroupInfo() {
        val p2p = manager ?: return
        val ch = channel ?: return
        if (!hasRequiredPermissions()) return

        p2p.requestConnectionInfo(ch) { info: WifiP2pInfo? ->
            if (info == null || !info.groupFormed) return@requestConnectionInfo
            val goIp = info.groupOwnerAddress?.hostAddress
            _state.update { current ->
                current.copy(
                    groupFormed = true,
                    isGroupOwner = info.isGroupOwner,
                    groupOwnerAddress = goIp,
                    status = if (info.isGroupOwner) {
                        WifiDirectStatus.GROUP_OWNER_READY
                    } else {
                        WifiDirectStatus.CONNECTED_AS_CLIENT
                    },
                    lastError = null
                )
            }
            if (!goIp.isNullOrBlank()) {
                if (info.isGroupOwner) {
                    onGroupOwnerAddressReady?.invoke(goIp)
                } else {
                    val matchedRecord = pendingTargetDeviceAddress?.let { discoveredRecords[it] }
                        ?: discoveredRecords.values.firstOrNull()
                    onClientConnectedToGroupOwner?.invoke(goIp, matchedRecord)
                }
            }
        }

        p2p.requestGroupInfo(ch) { group: WifiP2pGroup? ->
            if (group != null) {
                _state.update { current ->
                    current.copy(
                        groupNetworkName = group.networkName,
                        groupPassphrase = group.passphrase
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "WifiDirectManager"
        const val SERVICE_TYPE = "_chesslan._tcp"

        fun requiredRuntimePermissions(sdkInt: Int): Array<String> =
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        fun buildTxtRecord(advertisement: LanServiceAdvertisement): Map<String, String> = mapOf(
            "roomId" to advertisement.roomId.take(24),
            "code" to advertisement.roomCode.take(12),
            "host" to advertisement.hostName.take(24),
            "tc" to advertisement.timeControlId.take(16),
            "tcLbl" to advertisement.timeControlLabel.take(16),
            "port" to advertisement.port.toString(),
            "sec" to advertisement.roomSecret.take(16)
        )

        fun parseWifiDirectTxtRecord(
            txtRecord: Map<String, String>,
            deviceAddress: String,
            fallbackName: String = ""
        ): WifiDirectServiceRecord? {
            val port = txtRecord["port"]?.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            val tcId = txtRecord["tc"]?.takeIf { it.isNotBlank() } ?: "10+0"
            return WifiDirectServiceRecord(
                deviceAddress = deviceAddress,
                roomId = txtRecord["roomId"].orEmpty().ifBlank { deviceAddress },
                roomCode = txtRecord["code"].orEmpty(),
                hostName = txtRecord["host"].orEmpty().ifBlank { fallbackName.ifBlank { "Хост" } },
                timeControlId = tcId,
                timeControlLabel = txtRecord["tcLbl"].orEmpty().ifBlank { tcId },
                port = port,
                roomSecret = txtRecord["sec"].orEmpty()
            )
        }

        fun reasonCodeToText(reason: Int): String = when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct не поддерживается"
            WifiP2pManager.BUSY -> "модуль Wi-Fi занят, повторите попытку"
            else -> "внутренняя ошибка Wi-Fi Direct ($reason)"
        }
    }
}
