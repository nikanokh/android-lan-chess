package com.example

import android.Manifest
import com.example.core.network.LanServiceAdvertisement
import com.example.core.network.WifiDirectManager
import com.example.domain.model.TimeControl
import com.example.domain.model.TimeControlCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeControlAndWifiDirectTest {

    @Test
    fun timeControls_areGroupedIntoThreeDirectionsPlusExpandedClassical() {
        assertEquals(listOf("1+0", "1+1", "2+1"), TimeControl.BulletPresets.map { it.id })
        assertEquals(listOf("3+0", "3+2", "5+0", "5+3"), TimeControl.BlitzPresets.map { it.id })
        assertEquals(listOf("10+0", "10+5", "15+10"), TimeControl.RapidPresets.map { it.id })
        assertEquals(
            listOf("30+0", "30+20", "45+15", "60+0", "60+30", "unlimited"),
            TimeControl.ClassicalPresets.map { it.id }
        )

        assertTrue(TimeControl.BulletPresets.all { it.category == TimeControlCategory.BULLET })
        assertTrue(TimeControl.BlitzPresets.all { it.category == TimeControlCategory.BLITZ })
        assertTrue(TimeControl.RapidPresets.all { it.category == TimeControlCategory.RAPID })
        assertTrue(TimeControl.CLASSICAL_45_15.category == TimeControlCategory.CLASSICAL)
        assertTrue(TimeControl.UNLIMITED.isUnlimited)
    }

    @Test
    fun customTimeControl_andFromId_parseAndCategorizeAccurately() {
        val bulletCustom = TimeControl.custom(minutes = 2, incrementSeconds = 0)
        assertEquals("2+0", bulletCustom.id)
        assertEquals(120L, bulletCustom.initialSeconds)
        assertEquals(0L, bulletCustom.incrementSeconds)
        assertEquals(TimeControlCategory.BULLET, bulletCustom.category)

        val blitzCustom = TimeControl.custom(minutes = 7, incrementSeconds = 4)
        assertEquals("7+4", blitzCustom.id)
        assertEquals(420L, blitzCustom.initialSeconds)
        assertEquals(4L, blitzCustom.incrementSeconds)
        assertEquals(TimeControlCategory.BLITZ, blitzCustom.category)

        val rapidCustom = TimeControl.custom(minutes = 20, incrementSeconds = 5)
        assertEquals("20+5", rapidCustom.id)
        assertEquals(TimeControlCategory.RAPID, rapidCustom.category)

        val classicalCustom = TimeControl.custom(minutes = 90, incrementSeconds = 30)
        assertEquals("90+30", classicalCustom.id)
        assertEquals(TimeControlCategory.CLASSICAL, classicalCustom.category)

        assertEquals(blitzCustom, TimeControl.fromId("7+4"))
        assertEquals(TimeControl.CLASSICAL_45_15, TimeControl.fromId("45+15"))
        assertEquals(TimeControl.UNLIMITED, TimeControl.fromId("unlimited"))
        assertEquals(TimeControl.UNLIMITED, TimeControl.fromId("∞"))
        assertEquals(TimeControl.RAPID_10_0, TimeControl.fromId("invalid_tc"))
    }

    @Test
    fun wifiDirect_requiredPermissions_respectAndroid13NearbyDevices() {
        val permsApi33 = WifiDirectManager.requiredRuntimePermissions(33).toList()
        assertEquals(listOf(Manifest.permission.NEARBY_WIFI_DEVICES), permsApi33)
        assertFalse(permsApi33.contains(Manifest.permission.ACCESS_FINE_LOCATION))

        val permsApi36 = WifiDirectManager.requiredRuntimePermissions(36).toList()
        assertEquals(listOf(Manifest.permission.NEARBY_WIFI_DEVICES), permsApi36)

        val permsApi32 = WifiDirectManager.requiredRuntimePermissions(32).toList()
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), permsApi32)
    }

    @Test
    fun wifiDirect_txtRecord_roundtripsAndRejectsInvalidPorts() {
        val ad = LanServiceAdvertisement(
            roomId = "room-p2p-1",
            roomCode = "7744",
            hostName = "Алексей",
            timeControlId = "45+15",
            timeControlLabel = "45+15",
            port = 52123,
            connectedPlayers = 1,
            roomSecret = "sec-xyz-99"
        )

        val txt = WifiDirectManager.buildTxtRecord(ad)
        val parsed = WifiDirectManager.parseWifiDirectTxtRecord(
            txtRecord = txt,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            fallbackName = "Android Device"
        )

        assertNotNull(parsed)
        requireNotNull(parsed)
        assertEquals("AA:BB:CC:DD:EE:FF", parsed.deviceAddress)
        assertEquals("room-p2p-1", parsed.roomId)
        assertEquals("7744", parsed.roomCode)
        assertEquals("Алексей", parsed.hostName)
        assertEquals("45+15", parsed.timeControlId)
        assertEquals("45+15", parsed.timeControlLabel)
        assertEquals(52123, parsed.port)
        assertEquals("sec-xyz-99", parsed.roomSecret)

        assertNull(
            WifiDirectManager.parseWifiDirectTxtRecord(
                txtRecord = mapOf("port" to "not_a_number"),
                deviceAddress = "AA:BB:CC:DD:EE:FF"
            )
        )
        assertNull(
            WifiDirectManager.parseWifiDirectTxtRecord(
                txtRecord = mapOf("port" to "70000"),
                deviceAddress = "AA:BB:CC:DD:EE:FF"
            )
        )
        assertNull(
            WifiDirectManager.parseWifiDirectTxtRecord(
                txtRecord = emptyMap(),
                deviceAddress = "AA:BB:CC:DD:EE:FF"
            )
        )
    }
}
