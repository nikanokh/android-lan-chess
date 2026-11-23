package com.example.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.annotation.RawRes
import com.example.R
import com.example.core.chess.EngineMove
import com.example.domain.model.GameResult
import com.example.domain.model.PieceColor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class ChessSoundEffect(
    @RawRes val rawRes: Int,
    val volume: Float = 1.0f,
    val rate: Float = 1.0f
) {
    GAME_START(R.raw.sfx_game_start, volume = 0.95f),
    GAME_WIN(R.raw.sfx_game_win, volume = 1.0f),
    GAME_LOSE(R.raw.sfx_game_lose, volume = 1.0f),
    GAME_DRAW(R.raw.sfx_game_draw, volume = 0.95f),
    GAME_END(R.raw.sfx_game_end, volume = 0.95f),
    MOVE_SELF(R.raw.sfx_move_opponent, volume = 0.92f, rate = 1.08f),
    MOVE_OPPONENT(R.raw.sfx_move_opponent, volume = 0.95f, rate = 1.0f),
    CAPTURE(R.raw.sfx_capture, volume = 1.0f),
    CASTLE(R.raw.sfx_castle, volume = 1.0f),
    PROMOTE(R.raw.sfx_promote, volume = 1.0f),
    MOVE_CHECK(R.raw.sfx_move_check, volume = 1.0f),
    PREMOVE(R.raw.sfx_premove, volume = 0.92f),
    ILLEGAL(R.raw.sfx_illegal, volume = 0.92f),
    TEN_SECONDS(R.raw.sfx_tenseconds, volume = 1.0f),
    DRAW_OFFER(R.raw.sfx_drawoffer, volume = 0.95f),
    NOTIFICATION(R.raw.sfx_notification, volume = 0.95f),
    CLICK(R.raw.sfx_click, volume = 0.72f)
}

interface ChessSoundPlayer {
    fun play(effect: ChessSoundEffect)
}

object ChessSoundSelector {
    fun selectMoveSound(
        move: EngineMove,
        isOpponentMove: Boolean,
        isPremoveExecution: Boolean = false
    ): ChessSoundEffect = when {
        move.isCheck -> ChessSoundEffect.MOVE_CHECK
        move.promotion != null -> ChessSoundEffect.PROMOTE
        move.isCastling -> ChessSoundEffect.CASTLE
        move.capturedPiece != null || move.isEnPassant -> ChessSoundEffect.CAPTURE
        isPremoveExecution -> ChessSoundEffect.PREMOVE
        isOpponentMove -> ChessSoundEffect.MOVE_OPPONENT
        else -> ChessSoundEffect.MOVE_SELF
    }

    fun selectGameOverSound(
        result: GameResult,
        playerSide: PieceColor
    ): ChessSoundEffect = when (result) {
        GameResult.DRAW -> ChessSoundEffect.GAME_DRAW
        GameResult.ABORTED, GameResult.IN_PROGRESS -> ChessSoundEffect.GAME_END
        GameResult.WHITE_WIN -> if (playerSide == PieceColor.WHITE) {
            ChessSoundEffect.GAME_WIN
        } else {
            ChessSoundEffect.GAME_LOSE
        }
        GameResult.BLACK_WIN -> if (playerSide == PieceColor.BLACK) {
            ChessSoundEffect.GAME_WIN
        } else {
            ChessSoundEffect.GAME_LOSE
        }
    }
}

@Singleton
class AndroidChessSoundPlayer @Inject constructor(
    @ApplicationContext private val context: Context
) : ChessSoundPlayer {

    private val rawResToSoundId = ConcurrentHashMap<Int, Int>()
    private val loadedSoundIds = ConcurrentHashMap.newKeySet<Int>()
    private val pendingPlayBySoundId = ConcurrentHashMap<Int, ChessSoundEffect>()

    private val soundPool: SoundPool? = runCatching {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(attributes)
            .build()
            .apply {
                setOnLoadCompleteListener { pool, sampleId, status ->
                    if (status == 0) {
                        loadedSoundIds.add(sampleId)
                        pendingPlayBySoundId.remove(sampleId)?.let { pendingEffect ->
                            pool.play(
                                sampleId,
                                pendingEffect.volume,
                                pendingEffect.volume,
                                1,
                                0,
                                pendingEffect.rate
                            )
                        }
                    } else {
                        pendingPlayBySoundId.remove(sampleId)
                    }
                }
            }
    }.getOrNull()

    init {
        preloadAllSounds()
    }

    private fun preloadAllSounds() {
        val pool = soundPool ?: return
        val distinctRawIds = ChessSoundEffect.entries.map { it.rawRes }.distinct()
        for (rawRes in distinctRawIds) {
            runCatching {
                val soundId = pool.load(context, rawRes, 1)
                if (soundId > 0) {
                    rawResToSoundId[rawRes] = soundId
                }
            }
        }
    }

    override fun play(effect: ChessSoundEffect) {
        val pool = soundPool ?: return
        val soundId = rawResToSoundId[effect.rawRes] ?: run {
            val loaded = runCatching { pool.load(context, effect.rawRes, 1) }.getOrDefault(0)
            if (loaded > 0) {
                rawResToSoundId[effect.rawRes] = loaded
            }
            loaded
        }
        if (soundId <= 0) return

        if (loadedSoundIds.contains(soundId)) {
            runCatching {
                pool.play(soundId, effect.volume, effect.volume, 1, 0, effect.rate)
            }
        } else {
            pendingPlayBySoundId[soundId] = effect
        }
    }
}
