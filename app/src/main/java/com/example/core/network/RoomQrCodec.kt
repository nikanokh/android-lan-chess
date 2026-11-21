package com.example.core.network

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Offline room invitation payload embedded in a ChessLAN QR code.
 * Works across Wi-Fi LAN, Mobile Hotspot, and Wi-Fi Direct without any internet server.
 */
data class RoomQrPayload(
    val hostAddress: String,
    val port: Int,
    val roomCode: String = "",
    val roomSecret: String = "",
    val connectionType: ConnectionType = ConnectionType.WIFI_LAN,
    val hostName: String = "",
    val timeControlId: String = "10+0",
    val protocolVersion: Int = CHESS_LAN_PROTOCOL_VERSION
)

object RoomQrCodec {

    private const val SCHEME = "chesslan"
    private const val AUTHORITY = "join"

    fun encode(payload: RoomQrPayload): String {
        val params = buildList {
            add("v=${payload.protocolVersion}")
            add("h=${urlEncode(payload.hostAddress.trim())}")
            add("p=${payload.port}")
            if (payload.roomCode.isNotBlank()) add("c=${urlEncode(payload.roomCode.trim())}")
            if (payload.roomSecret.isNotBlank()) add("s=${urlEncode(payload.roomSecret.trim())}")
            add("m=${urlEncode(payload.connectionType.name)}")
            if (payload.hostName.isNotBlank()) add("n=${urlEncode(payload.hostName.trim().take(28))}")
            if (payload.timeControlId.isNotBlank()) add("t=${urlEncode(payload.timeControlId.trim())}")
        }
        return "$SCHEME://$AUTHORITY?${params.joinToString("&")}"
    }

    fun decode(rawContent: String): Result<RoomQrPayload> = runCatching {
        val trimmed = rawContent.trim()
        require(trimmed.isNotEmpty()) { "Пустой QR-код" }

        // Support plain "ip:port" QR codes as a forgiving fallback
        if (!trimmed.contains("://")) {
            val parts = trimmed.split(":")
            require(parts.size == 2) { "Нераспознанный формат QR-кода ChessLAN" }
            val host = parts[0].trim()
            val port = parts[1].trim().toIntOrNull()
                ?: error("Некорректный порт в QR-коде")
            validateHostAndPort(host, port)
            return@runCatching RoomQrPayload(hostAddress = host, port = port)
        }

        val uri = URI(trimmed)
        require(uri.scheme.equals(SCHEME, ignoreCase = true) && uri.host.equals(AUTHORITY, ignoreCase = true)) {
            "QR-код не относится к приложению ChessLAN"
        }

        val query = uri.rawQuery.orEmpty()
        val params = mutableMapOf<String, String>()
        query.split("&")
            .filter { it.isNotBlank() }
            .forEach { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    val key = urlDecode(pair.substring(0, idx))
                    val value = urlDecode(pair.substring(idx + 1))
                    params[key] = value
                }
            }

        val version = params["v"]?.toIntOrNull() ?: CHESS_LAN_PROTOCOL_VERSION
        require(version == CHESS_LAN_PROTOCOL_VERSION) {
            "Несовместимая версия протокола ($version)"
        }

        val host = params["h"].orEmpty().trim()
        val port = params["p"]?.toIntOrNull() ?: -1
        validateHostAndPort(host, port)

        val mode = params["m"]?.let { modeName ->
            ConnectionType.entries.firstOrNull { it.name.equals(modeName, ignoreCase = true) }
        } ?: ConnectionType.WIFI_LAN

        RoomQrPayload(
            hostAddress = host,
            port = port,
            roomCode = params["c"].orEmpty().trim(),
            roomSecret = params["s"].orEmpty().trim(),
            connectionType = mode,
            hostName = params["n"].orEmpty().trim(),
            timeControlId = params["t"].orEmpty().ifBlank { "10+0" },
            protocolVersion = version
        )
    }

    private fun validateHostAndPort(host: String, port: Int) {
        require(host.isNotBlank() && !host.contains(" ")) { "Некорректный IP-адрес хоста" }
        require(port in 1..65535) { "Некорректный номер порта ($port)" }
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun urlDecode(value: String): String =
        URLDecoder.decode(value, Charsets.UTF_8.name())
}

object QrBitmapGenerator {

    fun encodeBitMatrix(content: String, sizePx: Int = 512): BitMatrix {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2
        )
        return QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    }

    fun generateImageBitmap(content: String, sizePx: Int = 512): ImageBitmap {
        val matrix = encodeBitMatrix(content, sizePx)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                pixels[rowOffset + x] = if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap.asImageBitmap()
    }
}
