package com.example.core.network

import kotlinx.serialization.Serializable

@Serializable
enum class ConnectionType {
    WIFI_LAN,
    HOTSPOT,
    WIFI_DIRECT
}
