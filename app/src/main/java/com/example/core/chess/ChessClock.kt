package com.example.core.chess

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import com.example.domain.model.PieceColor
import com.example.domain.model.TimeControl

/**
 * Abstraction over Android's monotonic uptime clock (`SystemClock.elapsedRealtime()`).
 * Using a monotonic clock avoids wall-clock jumps (NTP/timezone) and eliminates coroutine drift.
 */
interface MonotonicTimeProvider {
    fun elapsedRealtimeMs(): Long
}

class SystemMonotonicTimeProvider : MonotonicTimeProvider {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}

@Immutable
data class ClockSnapshot(
    val timeControl: TimeControl,
    val whiteRemainingMs: Long,
    val blackRemainingMs: Long,
    val activeColor: PieceColor? = null,
    val isRunning: Boolean = false,
    val flaggedColor: PieceColor? = null,
    val lastUpdatedElapsedMs: Long = 0L
) {
    val isUnlimited: Boolean
        get() = timeControl.isUnlimited

    fun remainingFor(color: PieceColor): Long =
        if (color == PieceColor.WHITE) whiteRemainingMs else blackRemainingMs
}

/**
 * Authoritative chess clock driven exclusively by monotonic timestamps (`elapsedRealtimeMs`).
 *
 * Never decrements time by fixed coroutine intervals. Remaining time is always derived as:
 * `baseRemainingMs - (nowElapsedMs - turnStartedAtElapsedMs)`.
 *
 * Ready for Stage 2.4+ Host/Client LAN synchronization via [syncFromAuthoritative].
 */
class ChessClock(
    initialTimeControl: TimeControl,
    private val timeProvider: MonotonicTimeProvider = SystemMonotonicTimeProvider()
) {
    private val lock = Any()

    private var timeControl: TimeControl = initialTimeControl
    private var whiteBaseRemainingMs: Long = initialTimeControl.initialSeconds * 1_000L
    private var blackBaseRemainingMs: Long = initialTimeControl.initialSeconds * 1_000L
    private var incrementMs: Long = initialTimeControl.incrementSeconds * 1_000L
    private var activeColor: PieceColor? = null
    private var turnStartedAtElapsedMs: Long? = null
    private var flaggedColor: PieceColor? = null

    fun reset(newTimeControl: TimeControl = timeControl): ClockSnapshot = synchronized(lock) {
        timeControl = newTimeControl
        whiteBaseRemainingMs = newTimeControl.initialSeconds * 1_000L
        blackBaseRemainingMs = newTimeControl.initialSeconds * 1_000L
        incrementMs = newTimeControl.incrementSeconds * 1_000L
        activeColor = null
        turnStartedAtElapsedMs = null
        flaggedColor = null
        snapshotLocked(timeProvider.elapsedRealtimeMs())
    }

    /**
     * Starts counting down [sideToMove]'s clock from the current monotonic timestamp.
     * No-op for unlimited time controls or when a player has already flagged.
     */
    fun start(sideToMove: PieceColor): ClockSnapshot = synchronized(lock) {
        val now = timeProvider.elapsedRealtimeMs()
        if (timeControl.isUnlimited || flaggedColor != null) {
            return snapshotLocked(now)
        }
        activeColor = sideToMove
        turnStartedAtElapsedMs = now
        snapshotLocked(now)
    }

    /**
     * Completes a move made by [movedColor]:
     * 1. Deducts exact elapsed monotonic time if [movedColor]'s clock was running.
     * 2. If time reached 0, flags [movedColor] without adding increment.
     * 3. Otherwise applies increment to [movedColor] (once clock is running) and starts opponent's clock.
     */
    fun onMoveCompleted(movedColor: PieceColor): ClockSnapshot = synchronized(lock) {
        val now = timeProvider.elapsedRealtimeMs()
        if (timeControl.isUnlimited || flaggedColor != null) {
            return snapshotLocked(now)
        }

        val wasRunningForMover = activeColor == movedColor && turnStartedAtElapsedMs != null
        if (wasRunningForMover) {
            val elapsed = (now - (turnStartedAtElapsedMs ?: now)).coerceAtLeast(0L)
            val remaining = (baseFor(movedColor) - elapsed).coerceAtLeast(0L)
            setBaseFor(movedColor, remaining)
            if (remaining <= 0L) {
                flaggedColor = movedColor
                activeColor = null
                turnStartedAtElapsedMs = null
                return snapshotLocked(now)
            }
            if (incrementMs > 0L) {
                setBaseFor(movedColor, remaining + incrementMs)
            }
        } else if (activeColor == null && incrementMs > 0L) {
            // Standard Chess.com behavior: on White's opening move before clocks start ticking,
            // do not deduct time, start Black's clock immediately (no bonus increment before clock starts).
        }

        activeColor = movedColor.opposite()
        turnStartedAtElapsedMs = now
        snapshotLocked(now)
    }

    fun pause(): ClockSnapshot = synchronized(lock) {
        val now = timeProvider.elapsedRealtimeMs()
        commitElapsedLocked(now)
        turnStartedAtElapsedMs = null
        snapshotLocked(now)
    }

    fun resume(): ClockSnapshot = synchronized(lock) {
        val now = timeProvider.elapsedRealtimeMs()
        if (!timeControl.isUnlimited && flaggedColor == null && activeColor != null && turnStartedAtElapsedMs == null) {
            turnStartedAtElapsedMs = now
        }
        snapshotLocked(now)
    }

    fun stop(): ClockSnapshot = synchronized(lock) {
        val now = timeProvider.elapsedRealtimeMs()
        commitElapsedLocked(now)
        activeColor = null
        turnStartedAtElapsedMs = null
        snapshotLocked(now)
    }

    /**
     * Switches the active ticking clock to [sideToMove] without applying an increment.
     * Used after undo or state reconciliation to guarantee the ticking clock always matches
     * the side whose turn it currently is.
     */
    fun switchActiveSide(sideToMove: PieceColor): ClockSnapshot = synchronized(lock) {
        val now = timeProvider.elapsedRealtimeMs()
        if (timeControl.isUnlimited || flaggedColor != null) {
            return snapshotLocked(now)
        }
        commitElapsedLocked(now)
        if (flaggedColor != null) {
            return snapshotLocked(now)
        }
        activeColor = sideToMove
        turnStartedAtElapsedMs = now
        snapshotLocked(now)
    }

    /**
     * Reconciles local clock state with authoritative remaining times received from a Host
     * (used during LAN gameplay and reconnection recovery).
     */
    fun syncFromAuthoritative(
        whiteRemainingMs: Long,
        blackRemainingMs: Long,
        activeSide: PieceColor?,
        isRunning: Boolean
    ): ClockSnapshot = synchronized(lock) {
        val now = timeProvider.elapsedRealtimeMs()
        whiteBaseRemainingMs = whiteRemainingMs.coerceAtLeast(0L)
        blackBaseRemainingMs = blackRemainingMs.coerceAtLeast(0L)
        flaggedColor = when {
            timeControl.isUnlimited -> null
            whiteBaseRemainingMs <= 0L -> PieceColor.WHITE
            blackBaseRemainingMs <= 0L -> PieceColor.BLACK
            else -> null
        }
        if (flaggedColor != null || timeControl.isUnlimited || !isRunning || activeSide == null) {
            activeColor = if (flaggedColor != null) null else activeSide
            turnStartedAtElapsedMs = null
        } else {
            activeColor = activeSide
            turnStartedAtElapsedMs = now
        }
        snapshotLocked(now)
    }

    /**
     * Computes the exact live clock state at the current monotonic timestamp.
     * Automatically detects timeout flag if the active side's remaining time reached 0.
     */
    fun snapshot(): ClockSnapshot = synchronized(lock) {
        snapshotLocked(timeProvider.elapsedRealtimeMs())
    }

    private fun commitElapsedLocked(now: Long) {
        val active = activeColor ?: return
        val started = turnStartedAtElapsedMs ?: return
        if (timeControl.isUnlimited || flaggedColor != null) return
        val elapsed = (now - started).coerceAtLeast(0L)
        val remaining = (baseFor(active) - elapsed).coerceAtLeast(0L)
        setBaseFor(active, remaining)
        if (remaining <= 0L) {
            flaggedColor = active
            activeColor = null
            turnStartedAtElapsedMs = null
        }
    }

    private fun snapshotLocked(now: Long): ClockSnapshot {
        if (timeControl.isUnlimited) {
            return ClockSnapshot(
                timeControl = timeControl,
                whiteRemainingMs = 0L,
                blackRemainingMs = 0L,
                activeColor = null,
                isRunning = false,
                flaggedColor = null,
                lastUpdatedElapsedMs = now
            )
        }

        var whiteMs = whiteBaseRemainingMs
        var blackMs = blackBaseRemainingMs
        val active = activeColor
        val started = turnStartedAtElapsedMs

        if (active != null && started != null && flaggedColor == null) {
            val elapsed = (now - started).coerceAtLeast(0L)
            if (active == PieceColor.WHITE) {
                whiteMs = (whiteBaseRemainingMs - elapsed).coerceAtLeast(0L)
                if (whiteMs <= 0L) {
                    whiteBaseRemainingMs = 0L
                    flaggedColor = PieceColor.WHITE
                    activeColor = null
                    turnStartedAtElapsedMs = null
                }
            } else {
                blackMs = (blackBaseRemainingMs - elapsed).coerceAtLeast(0L)
                if (blackMs <= 0L) {
                    blackBaseRemainingMs = 0L
                    flaggedColor = PieceColor.BLACK
                    activeColor = null
                    turnStartedAtElapsedMs = null
                }
            }
        }

        return ClockSnapshot(
            timeControl = timeControl,
            whiteRemainingMs = whiteMs,
            blackRemainingMs = blackMs,
            activeColor = activeColor,
            isRunning = activeColor != null && turnStartedAtElapsedMs != null && flaggedColor == null,
            flaggedColor = flaggedColor,
            lastUpdatedElapsedMs = now
        )
    }

    private fun baseFor(color: PieceColor): Long =
        if (color == PieceColor.WHITE) whiteBaseRemainingMs else blackBaseRemainingMs

    private fun setBaseFor(color: PieceColor, valueMs: Long) {
        if (color == PieceColor.WHITE) {
            whiteBaseRemainingMs = valueMs
        } else {
            blackBaseRemainingMs = valueMs
        }
    }

    companion object {
        const val LOW_TIME_THRESHOLD_MS: Long = 20_000L

        /**
         * Formats remaining milliseconds for chess clocks:
         * - Unlimited -> "∞"
         * - Under 20 seconds -> "0:14.8" (tenths of a second for urgency)
         * - Under 1 hour -> "05:00"
         * - 1 hour+ -> "1:00:00"
         */
        fun formatClockTime(remainingMs: Long, isUnlimited: Boolean): String {
            if (isUnlimited) return "∞"
            val clamped = remainingMs.coerceAtLeast(0L)
            if (clamped < LOW_TIME_THRESHOLD_MS) {
                val totalTenths = clamped / 100L
                val tenths = totalTenths % 10L
                val totalSeconds = totalTenths / 10L
                val minutes = totalSeconds / 60L
                val seconds = totalSeconds % 60L
                return "%d:%02d.%d".format(minutes, seconds, tenths)
            }
            val totalSeconds = (clamped + 999L) / 1_000L
            val hours = totalSeconds / 3_600L
            val minutes = (totalSeconds % 3_600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0L) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%02d:%02d".format(minutes, seconds)
            }
        }
    }
}
