package com.example

import com.example.core.chess.ChessClock
import com.example.core.chess.MonotonicTimeProvider
import com.example.domain.model.PieceColor
import com.example.domain.model.TimeControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeMonotonicTimeProvider(var nowMs: Long = 10_000L) : MonotonicTimeProvider {
    override fun elapsedRealtimeMs(): Long = nowMs
    fun advance(deltaMs: Long) {
        nowMs += deltaMs
    }
}

class ChessClockTest {

    @Test
    fun allElevenTimeControls_initializeExactRemainingMillis() {
        val fakeClock = FakeMonotonicTimeProvider()
        for (preset in TimeControl.AllPresets) {
            val clock = ChessClock(preset, fakeClock)
            val snap = clock.snapshot()
            assertEquals(preset.isUnlimited, snap.isUnlimited)
            assertEquals(preset.initialSeconds * 1_000L, snap.whiteRemainingMs)
            assertEquals(preset.initialSeconds * 1_000L, snap.blackRemainingMs)
            assertFalse(snap.isRunning)
            assertNull(snap.flaggedColor)
        }
    }

    @Test
    fun monotonicElapsedCalculation_switchesTurnsAndAppliesIncrementAccurately() {
        val fakeTime = FakeMonotonicTimeProvider(nowMs = 1_000L)
        val clock = ChessClock(TimeControl.BLITZ_3_2, fakeTime) // 180s + 2s increment

        // White plays move 1 -> starts Black's clock
        var snap = clock.onMoveCompleted(PieceColor.WHITE)
        assertEquals(PieceColor.BLACK, snap.activeColor)
        assertTrue(snap.isRunning)
        assertEquals(180_000L, snap.whiteRemainingMs)
        assertEquals(180_000L, snap.blackRemainingMs)

        // Black thinks for 4.350 seconds and moves -> 180_000 - 4_350 + 2_000 = 177_650 ms
        fakeTime.advance(4_350L)
        snap = clock.onMoveCompleted(PieceColor.BLACK)
        assertEquals(PieceColor.WHITE, snap.activeColor)
        assertEquals(177_650L, snap.blackRemainingMs)
        assertEquals(180_000L, snap.whiteRemainingMs)

        // White thinks for 10.125 seconds (live snapshot without move yet)
        fakeTime.advance(10_125L)
        snap = clock.snapshot()
        assertEquals(169_875L, snap.whiteRemainingMs)
        assertEquals(177_650L, snap.blackRemainingMs)

        // White moves -> 169_875 + 2_000 = 171_875 ms
        snap = clock.onMoveCompleted(PieceColor.WHITE)
        assertEquals(171_875L, snap.whiteRemainingMs)
        assertEquals(PieceColor.BLACK, snap.activeColor)
    }

    @Test
    fun timeoutFlag_detectedWithoutAddingIncrementAfterExpiry() {
        val fakeTime = FakeMonotonicTimeProvider(nowMs = 5_000L)
        val clock = ChessClock(TimeControl.BULLET_2_1, fakeTime) // 120s + 1s

        clock.start(PieceColor.WHITE)
        fakeTime.advance(120_500L) // Exceeds 120s

        val snap = clock.snapshot()
        assertEquals(0L, snap.whiteRemainingMs)
        assertEquals(PieceColor.WHITE, snap.flaggedColor)
        assertFalse(snap.isRunning)

        // Attempting to complete move after flag does NOT resurrect clock with increment
        val afterLateMove = clock.onMoveCompleted(PieceColor.WHITE)
        assertEquals(0L, afterLateMove.whiteRemainingMs)
        assertEquals(PieceColor.WHITE, afterLateMove.flaggedColor)
    }

    @Test
    fun pauseResumeAndAuthoritativeHostSync_preserveExactState() {
        val fakeTime = FakeMonotonicTimeProvider(nowMs = 2_000L)
        val clock = ChessClock(TimeControl.RAPID_10_0, fakeTime)

        clock.start(PieceColor.WHITE)
        fakeTime.advance(15_000L)
        var snap = clock.pause()
        assertEquals(585_000L, snap.whiteRemainingMs)
        assertFalse(snap.isRunning)

        // Elapsed time while paused must NOT deduct
        fakeTime.advance(30_000L)
        snap = clock.snapshot()
        assertEquals(585_000L, snap.whiteRemainingMs)

        // Resume deducts only new elapsed time
        clock.resume()
        fakeTime.advance(5_000L)
        snap = clock.snapshot()
        assertEquals(580_000L, snap.whiteRemainingMs)

        // Authoritative sync from Host overwrites client drift cleanly
        snap = clock.syncFromAuthoritative(
            whiteRemainingMs = 540_200L,
            blackRemainingMs = 575_800L,
            activeSide = PieceColor.BLACK,
            isRunning = true
        )
        assertEquals(540_200L, snap.whiteRemainingMs)
        assertEquals(575_800L, snap.blackRemainingMs)
        assertEquals(PieceColor.BLACK, snap.activeColor)
        assertTrue(snap.isRunning)
    }

    @Test
    fun unlimitedTimeControl_neverTicksOrFlags() {
        val fakeTime = FakeMonotonicTimeProvider(nowMs = 1_000L)
        val clock = ChessClock(TimeControl.UNLIMITED, fakeTime)
        clock.start(PieceColor.WHITE)
        fakeTime.advance(999_999L)
        val snap = clock.snapshot()
        assertTrue(snap.isUnlimited)
        assertFalse(snap.isRunning)
        assertNull(snap.flaggedColor)
        assertEquals("∞", ChessClock.formatClockTime(snap.whiteRemainingMs, isUnlimited = true))
    }

    @Test
    fun formatClockTime_handlesNormalLowTimeAndHours() {
        assertEquals("∞", ChessClock.formatClockTime(0L, isUnlimited = true))
        assertEquals("05:00", ChessClock.formatClockTime(300_000L, isUnlimited = false))
        assertEquals("00:20", ChessClock.formatClockTime(20_000L, isUnlimited = false))
        assertEquals("0:19.9", ChessClock.formatClockTime(19_950L, isUnlimited = false))
        assertEquals("0:04.2", ChessClock.formatClockTime(4_210L, isUnlimited = false))
        assertEquals("0:00.0", ChessClock.formatClockTime(0L, isUnlimited = false))
        assertEquals("1:05:00", ChessClock.formatClockTime(3_900_000L, isUnlimited = false))
    }

    @Test
    fun switchActiveSide_immediatelySwitchesTickingClockForUndoSync() {
        val fakeTime = FakeMonotonicTimeProvider(nowMs = 1_000L)
        val clock = ChessClock(TimeControl.BLITZ_5_0, fakeTime)

        // White plays -> Black starts ticking
        clock.onMoveCompleted(PieceColor.WHITE)
        fakeTime.advance(3_000L)
        // Black plays -> White starts ticking (Black at 297s)
        clock.onMoveCompleted(PieceColor.BLACK)
        fakeTime.advance(4_000L)
        // White plays -> Black starts ticking (White at 296s)
        clock.onMoveCompleted(PieceColor.WHITE)
        fakeTime.advance(2_000L)

        // Undo White's move -> now White's turn again: switchActiveSide(WHITE) must tick WHITE, not BLACK
        val switched = clock.switchActiveSide(PieceColor.WHITE)
        assertEquals(PieceColor.WHITE, switched.activeColor)
        assertTrue(switched.isRunning)
        assertEquals(295_000L, switched.blackRemainingMs)
        assertEquals(296_000L, switched.whiteRemainingMs)

        // Advance 5s -> ONLY White's time decreases, Black stays frozen at 295s
        fakeTime.advance(5_000L)
        val afterWait = clock.snapshot()
        assertEquals(291_000L, afterWait.whiteRemainingMs)
        assertEquals(295_000L, afterWait.blackRemainingMs)
        assertEquals(PieceColor.WHITE, afterWait.activeColor)
    }
}
