package com.example.notes.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/* =====================================================================
 * V6.0 — Theme redesign · MaterialTheme.shapes
 *
 * One central token table the rest of the UI references. Cards default
 * to "medium" (16dp); large/hero cards override with `large` (20dp);
 * bottom sheets use `extraLarge` (28dp). Chips and small buttons use
 * `RoundedCornerShape(50)` (pill) directly, not a shape token.
 *
 * Numbers chosen so a 1dp `outlineVariant` border reads as a deliberate
 * card edge instead of cropping around a different radius.
 * ===================================================================== */
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(16.dp),
    large      = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** Chip / pill radius (used for search bar, chips, primary buttons). */
val PillShape: Dp = 28.dp

/** Convenience: full pill — pass straight into `clip(...)` / `shape = …`. */
val PillClip = RoundedCornerShape(50)

/* --------------------------------------------------------------------- */
/* Convenience accessors so screens don't have to walk through Shapes.   */
/* --------------------------------------------------------------------- */

/** Card surface — 16dp by default, 20dp for the "hero" editor card. */
val CardCornerMedium: Dp get() = 16.dp
val CardCornerLarge:  Dp get() = 20.dp

/** Bottom-sheet top corner — 28dp. */
val SheetCorner: Dp get() = 28.dp

/** Pills (search bar, chips, full-width primary buttons). */
val ChipCorner: Dp get() = 24.dp

/** Small floating elements (swatch, FAB shape overrides, mini avatars). */
val Swatch: Dp get() = 999.dp

/**
 * Helper exported because Theme.shapes.* expects `CornerBasedShape`, not
 * `Dp`. Use this only when you need a `CornerBasedShape` directly.
 */
@Composable
fun pillCorner(): RoundedCornerShape = RoundedCornerShape(ChipCorner)
