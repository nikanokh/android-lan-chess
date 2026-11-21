package com.example.core.ui.board

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import com.example.R
import com.example.domain.model.PieceColor
import com.example.domain.model.PieceSet

/**
 * Exact verified mapping between chess pieces and the imported PNG drawables in res/drawable/.
 */
object ChessPieceResources {

    @DrawableRes
    fun drawableFor(piece: BoardPiece): Int = drawableFor(piece.color, piece.type)

    @DrawableRes
    fun drawableFor(color: PieceColor, type: PieceType): Int = when (color) {
        PieceColor.WHITE -> when (type) {
            PieceType.PAWN -> R.drawable.piece_wp
            PieceType.KNIGHT -> R.drawable.piece_wn
            PieceType.BISHOP -> R.drawable.piece_wb
            PieceType.ROOK -> R.drawable.piece_wr
            PieceType.QUEEN -> R.drawable.piece_wq
            PieceType.KING -> R.drawable.piece_wk
        }
        PieceColor.BLACK -> when (type) {
            PieceType.PAWN -> R.drawable.piece_bp
            PieceType.KNIGHT -> R.drawable.piece_bn
            PieceType.BISHOP -> R.drawable.piece_bb
            PieceType.ROOK -> R.drawable.piece_br
            PieceType.QUEEN -> R.drawable.piece_bq
            PieceType.KING -> R.drawable.piece_bk
        }
    }

    /**
     * Visual styling profile for PieceSet presets while reusing the high-resolution PNG sprites.
     */
    fun colorFilterFor(pieceSet: PieceSet): ColorFilter? = when (pieceSet) {
        PieceSet.NEO -> null
        PieceSet.ALPHA -> {
            val contrast = 1.14f
            val translate = (-0.5f * contrast + 0.5f) * 255f
            ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        contrast, 0f, 0f, 0f, translate,
                        0f, contrast, 0f, 0f, translate,
                        0f, 0f, contrast, 0f, translate,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        PieceSet.TOURNAMENT -> {
            // Warm tournament ivory tint
            ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        1.03f, 0f, 0f, 0f, 4f,
                        0f, 1.00f, 0f, 0f, 1f,
                        0f, 0f, 0.91f, 0f, -4f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
    }
}

@Immutable
class PieceBitmapCache(
    private val bitmaps: Map<BoardPiece, ImageBitmap>,
    val colorFilter: ColorFilter?
) {
    operator fun get(piece: BoardPiece): ImageBitmap? = bitmaps[piece]
}

/**
 * Loads all 12 piece bitmaps once and caches them across recompositions and Canvas draw passes.
 */
@Composable
fun rememberPieceBitmapCache(pieceSet: PieceSet): PieceBitmapCache {
    val resources = LocalContext.current.resources
    val colorFilter = remember(pieceSet) { ChessPieceResources.colorFilterFor(pieceSet) }
    val bitmaps = remember(resources) {
        buildMap(12) {
            for (color in PieceColor.entries) {
                for (type in PieceType.entries) {
                    val piece = BoardPiece(type = type, color = color)
                    val resId = ChessPieceResources.drawableFor(piece)
                    put(piece, ImageBitmap.imageResource(resources, resId))
                }
            }
        }
    }
    return remember(bitmaps, colorFilter) {
        PieceBitmapCache(bitmaps = bitmaps, colorFilter = colorFilter)
    }
}
