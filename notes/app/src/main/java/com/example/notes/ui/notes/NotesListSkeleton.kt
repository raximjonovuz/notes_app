package com.example.notes.ui.notes

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.example.notes.ui.theme.LocalNotesTheme

private data class SkeletonCardData(
    val linesCount: Int,
    val widthFactor: Float
)

/**
 * High-performance drawBehind-based shimmer modifier.
 * Reads the animated state ONLY during the Draw phase, completely avoiding
 * Compose recompositions and layout passes on 120Hz displays.
 */
private fun Modifier.shimmerPlaceholder(
    offsetProvider: () -> Float,
    baseColor: Color,
    highlightColor: Color,
    shape: Shape = RoundedCornerShape(8.dp)
): Modifier = this
    .clip(shape)
    .drawBehind {
        val offset = offsetProvider()
        val brush = Brush.linearGradient(
            colors = listOf(baseColor, highlightColor, baseColor),
            start = Offset(offset - 300f, offset - 300f),
            end = Offset(offset + 300f, offset + 300f)
        )
        drawRect(brush = brush)
    }

/**
 * Ultra high-performance Skeleton Loader (Theme aware).
 * Pure GPU draw phase — zero recomposition churn on 120Hz displays.
 */
@Composable
fun NotesListSkeleton(
    modifier: Modifier = Modifier
) {
    val isDark = LocalNotesTheme.current.isDark

    // Light mode: Oq fonda oqga yaqin och kulrang (soft grey close to white)
    val borderColor = if (isDark) Color(0xFF242C24) else Color(0xFFE4E7EA)
    val cardBg = if (isDark) Color(0xFF161C16) else Color.White

    val baseColor = if (isDark) Color(0xFF1E241E) else Color(0xFFE8EAED)
    val highlightColor = if (isDark) Color(0xFF2C352C) else Color(0xFFF8F9FA)

    // SINGLE shared transition — state read strictly inside drawBehind
    val transition = rememberInfiniteTransition(label = "sharedShimmerTransition")
    val translateAnim = transition.animateFloat(
        initialValue = -400f,
        targetValue = 1600f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )

    val offsetProvider: () -> Float = { translateAnim.value }

    val skeletonItems = remember {
        listOf(
            SkeletonCardData(linesCount = 3, widthFactor = 0.85f),
            SkeletonCardData(linesCount = 2, widthFactor = 0.65f),
            SkeletonCardData(linesCount = 4, widthFactor = 0.95f),
            SkeletonCardData(linesCount = 2, widthFactor = 0.70f),
            SkeletonCardData(linesCount = 3, widthFactor = 0.80f),
            SkeletonCardData(linesCount = 1, widthFactor = 0.50f)
        )
    }

    // Staggered Cards Skeleton Grid
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalItemSpacing = 8.dp,
        userScrollEnabled = false
    ) {
        skeletonItems.forEach { skeletonItem ->
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(cardBg)
                        .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        // Title skeleton bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(skeletonItem.widthFactor)
                                .height(18.dp)
                                .shimmerPlaceholder(
                                    offsetProvider,
                                    baseColor,
                                    highlightColor,
                                    RoundedCornerShape(9.dp)
                                )
                        )

                        Spacer(Modifier.height(12.dp))

                        // Body lines skeleton
                        repeat(skeletonItem.linesCount) { index ->
                            val lineWidth = when (index) {
                                0 -> 0.92f
                                1 -> 0.78f
                                else -> 0.55f
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(lineWidth)
                                    .height(12.dp)
                                    .shimmerPlaceholder(
                                        offsetProvider,
                                        baseColor,
                                        highlightColor,
                                        RoundedCornerShape(6.dp)
                                    )
                            )
                            if (index < skeletonItem.linesCount - 1) {
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
