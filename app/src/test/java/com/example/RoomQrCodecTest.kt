package com.example

import com.example.core.network.CHESS_LAN_PROTOCOL_VERSION
import com.example.core.network.ConnectionType
import com.example.core.network.QrBitmapGenerator
import com.example.core.network.RoomQrCodec
import com.example.core.network.RoomQrPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomQrCodecTest {

    @Test
    fun encodeAndDecode_roundTripsFullHotspotPayloadWithCyrillicName() {
        val original = RoomQrPayload(
            hostAddress = "192.168.43.1",
            port = 51234,
            roomCode = "LAN-8421",
            roomSecret = "SECRET99",
            connectionType = ConnectionType.HOTSPOT,
            hostName = "Алексей Петров",
            timeControlId = "5+3",
            protocolVersion = CHESS_LAN_PROTOCOL_VERSION
        )

        val encoded = RoomQrCodec.encode(original)
        assertTrue(encoded.startsWith("chesslan://join?"))

        val decoded = RoomQrCodec.decode(encoded).getOrThrow()
        assertEquals(original, decoded)
    }

    @Test
    fun decode_supportsPlainHostPortFallback() {
        val decoded = RoomQrCodec.decode("192.168.43.1:54321").getOrThrow()
        assertEquals("192.168.43.1", decoded.hostAddress)
        assertEquals(54321, decoded.port)
        assertEquals(ConnectionType.WIFI_LAN, decoded.connectionType)
    }

    @Test
    fun decode_rejectsInvalidInputsAndIncompatibleProtocol() {
        assertTrue(RoomQrCodec.decode("").isFailure)
        assertTrue(RoomQrCodec.decode("   ").isFailure)
        assertTrue(RoomQrCodec.decode("https://example.com/room").isFailure)
        assertTrue(RoomQrCodec.decode("chesslan://join?v=1&h=192.168.1.2&p=0").isFailure)
        assertTrue(RoomQrCodec.decode("chesslan://join?v=1&h=192.168.1.2&p=70000").isFailure)
        assertTrue(RoomQrCodec.decode("chesslan://join?v=99&h=192.168.1.2&p=8080").isFailure)
        assertTrue(RoomQrCodec.decode("192.168.1.2:notaport").isFailure)
    }

    @Test
    fun qrBitmapGenerator_encodesNonEmptySquareBitMatrix() {
        val uri = RoomQrCodec.encode(
            RoomQrPayload(
                hostAddress = "10.0.0.5",
                port = 48123,
                roomCode = "4829",
                roomSecret = "ABCD1234",
                connectionType = ConnectionType.WIFI_LAN,
                hostName = "Мария",
                timeControlId = "10+0"
            )
        )

        val matrix = QrBitmapGenerator.encodeBitMatrix(uri, sizePx = 256)
        assertEquals(256, matrix.width)
        assertEquals(256, matrix.height)

        var darkCount = 0
        var lightCount = 0
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) darkCount++ else lightCount++
            }
        }
        assertTrue("Expected dark QR modules", darkCount > 500)
        assertTrue("Expected light QR modules", lightCount > 500)
    }
}
