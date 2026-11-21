package com.example.core.ui.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.core.ui.theme.ChessTheme
import com.example.domain.model.PieceColor

/**
 * ChessLAN-styled promotion piece picker dialog.
 * Allows selecting Queen, Rook, Bishop, or Knight when a pawn reaches the last rank.
 */
@Composable
fun PawnPromotionDialog(
    color: PieceColor,
    onPieceSelected: (PieceType) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = ChessTheme.colors

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("pawn_promotion_dialog"),
            shape = RoundedCornerShape(ChessTheme.radii.lg),
            color = colors.surfaceCard,
            border = BorderStroke(1.dp, colors.borderSubtle)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.board_promotion_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PieceType.PromotionOptions.forEach { pieceType ->
                        val labelRes = when (pieceType) {
                            PieceType.QUEEN -> R.string.board_piece_queen
                            PieceType.ROOK -> R.string.board_piece_rook
                            PieceType.BISHOP -> R.string.board_piece_bishop
                            PieceType.KNIGHT -> R.string.board_piece_knight
                            else -> R.string.board_piece_queen
                        }
                        Surface(
                            onClick = { onPieceSelected(pieceType) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("promotion_option_${pieceType.name.lowercase()}"),
                            shape = RoundedCornerShape(ChessTheme.radii.sm),
                            color = colors.backgroundSecondary,
                            border = BorderStroke(1.dp, colors.borderSubtle)
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Image(
                                    painter = painterResource(ChessPieceResources.drawableFor(color, pieceType)),
                                    contentDescription = stringResource(labelRes),
                                    modifier = Modifier.size(44.dp)
                                )
                                Text(
                                    text = stringResource(labelRes),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.textPrimary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
