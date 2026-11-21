package com.example.data.local.mapper

import com.example.core.database.dao.GameWithMoveCount
import com.example.core.network.ConnectionType
import com.example.domain.model.GameResult
import com.example.domain.model.GameSummary

fun GameWithMoveCount.toDomain(): GameSummary {
    val parsedResult = GameResult.entries.firstOrNull { it.name == game.result }
        ?: GameResult.IN_PROGRESS
    val parsedConnection = ConnectionType.entries.firstOrNull { it.name == game.connectionType }
        ?: ConnectionType.WIFI_LAN
    return GameSummary(
        id = game.id,
        gameUuid = game.gameUuid,
        createdAt = game.createdAt,
        finishedAt = game.finishedAt,
        whitePlayer = game.whitePlayer,
        blackPlayer = game.blackPlayer,
        result = parsedResult,
        timeControlLabel = game.timeControl,
        moveCount = moveCount,
        connectionType = parsedConnection,
        pgn = game.pgn
    )
}
