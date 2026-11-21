package com.example.feature.analysis

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.ui.components.ChessSurfaceCard
import com.example.core.ui.components.ChessTopBar
import com.example.core.ui.components.SectionHeader
import com.example.core.ui.components.StagePillBadge
import com.example.core.ui.theme.ChessDimensions
import com.example.core.ui.theme.ChessTheme
import com.example.domain.model.MoveClassification

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalysisScreen(
    onNavigateBack: () -> Unit
) {
    val colors = ChessTheme.colors
    val spacing = ChessTheme.spacing

    val badgeMap = listOf(
        MoveClassification.BRILLIANT to R.drawable.badge_brilliant,
        MoveClassification.GREAT to R.drawable.badge_great,
        MoveClassification.BEST to R.drawable.badge_best,
        MoveClassification.EXCELLENT to R.drawable.badge_excellent,
        MoveClassification.GOOD to R.drawable.badge_good,
        MoveClassification.BOOK to R.drawable.badge_book,
        MoveClassification.FORCED to R.drawable.badge_forced,
        MoveClassification.INACCURACY to R.drawable.badge_inaccuracy,
        MoveClassification.MISTAKE to R.drawable.badge_mistake,
        MoveClassification.MISSED_CHANCE to R.drawable.badge_missed_chance,
        MoveClassification.BLUNDER to R.drawable.badge_blunder
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ChessTopBar(
                title = stringResource(R.string.analysis_title),
                onNavigateBack = onNavigateBack
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = ChessDimensions.MaxContentWidth),
                contentPadding = PaddingValues(
                    horizontal = spacing.screenHorizontal,
                    vertical = spacing.md
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.lg)
            ) {
                item(key = "intro") {
                    ChessSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            StagePillBadge(text = stringResource(R.string.stage_badge_foundation))
                            Text(
                                text = stringResource(R.string.analysis_title),
                                style = MaterialTheme.typography.headlineSmall,
                                color = colors.textPrimary
                            )
                            Text(
                                text = stringResource(R.string.analysis_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary
                            )
                        }
                    }
                }

                item(key = "badges") {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        SectionHeader(title = stringResource(R.string.analysis_badges_title))
                        ChessSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                badgeMap.forEach { (classification, drawableRes) ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = colors.backgroundSecondary
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Image(
                                                painter = painterResource(drawableRes),
                                                contentDescription = classification.title,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = classification.title,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = colors.textPrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
