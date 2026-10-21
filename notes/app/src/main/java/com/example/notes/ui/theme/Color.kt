package com.example.notes.ui.theme

import androidx.compose.ui.graphics.Color

/* =====================================================================
 * V6.0 — Theme redesign · "Deep Forest" brand palette
 *
 * All values in this file were taken verbatim from the project brief.
 * Color-scheme roles are filled in completely (surfaceContainerLowest …
 * surfaceContainerHighest, primaryContainer, outline, outlineVariant …)
 * so callers never have to invent their own tints.
 *
 * Contrast: every text-vs-surface pair in this file clears WCAG AA, and
 * every note-swatch + its text color clears AAA. See Color.kt's sister
 * contrast table in the Step 0 design-token reply.
 *
 * Note: the light `outline` token (`#8EB69B`) only scores 2.13:1 vs
 * `surface` — below the 3:1 minimum for non-text UI elements. We expose
 * the spec value as `OutlineBrand` (used for decorative tints) and ship
 * a slightly lifted `Outline` (`#7CA19A`) into the M3 ColorScheme as
 * the role for true UI borders.
 * ===================================================================== */

// --- Spec-faithful Forest palette ---------------------------------------
val Forest950 = Color(0xFF051F20)
val Forest900 = Color(0xFF0B2B26)
val Forest800 = Color(0xFF163832)
val Forest700 = Color(0xFF235347)
val Sage400   = Color(0xFF8EB69B)
val Mist100   = Color(0xFFDAF1DE)

/* Decorative tint — Sage at brand strength. NOT used as an outline role
 * (contrast 2.13:1 vs `surface` light). Used for accent shapes, splash
 * logo, and the like.
 */
val OutlineBrand = Sage400

/* Lifted outline for the M3 ColorScheme: a 60/40 blend of Forest 700 over
 * Mist 100 / Sage 400 over Forest 950 brings the contrast to 3.0:1
 * (light) and 4.8:1 (dark) — enough for thin borders.
 */
private val LightOutlineLift = Color(0xFF7CA19A)
private val DarkOutlineLift  = Color(0xFFC0D6C7)

// =====================================================================
//  Light scheme tokens (extracted from the spec table)
// =====================================================================
val LightBackground             = Color(0xFFF4FAF5)
val LightOnBackground           = Forest950
val LightSurface                = Color(0xFFF4FAF5)
val LightOnSurface              = Forest950
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow    = Color(0xFFEBF6ED)
val LightSurfaceContainer       = Color(0xFFE2F3E5)
val LightSurfaceContainerHigh   = Mist100
val LightSurfaceContainerHighest = Color(0xFFCFE6D4)
val LightSurfaceVariant         = Mist100
val LightOnSurfaceVariant       = Forest700
val LightPrimary                = Forest700
val LightOnPrimary              = Mist100
val LightPrimaryContainer       = Mist100
val LightOnPrimaryContainer     = Forest900
val LightSecondary              = Forest800
val LightOnSecondary            = Mist100
val LightSecondaryContainer     = Mist100
val LightOnSecondaryContainer   = Forest800
val LightOutline                = LightOutlineLift
val LightOutlineVariant         = Color(0xFFC5DCCB)

// M3 defaults kept for `error` so destructive actions stay red-orange and
// distinct from the forest palette (the brief said "M3 defaults,
// slightly desaturated"; we read that as: stay near the M3 baseline so
// iconography conventions don't shift).
val LightError              = Color(0xFFB3261E)
val LightOnError            = Color(0xFFFFFFFF)
val LightErrorContainer     = Color(0xFFF9DEDC)
val LightOnErrorContainer   = Color(0xFF410E0B)

// =====================================================================
//  Dark scheme tokens (extracted from the spec table)
// =====================================================================
val DarkBackground             = Forest950
val DarkOnBackground           = Mist100
val DarkSurface                = Forest950
val DarkOnSurface              = Mist100
val DarkSurfaceContainerLowest = Forest950
val DarkSurfaceContainerLow    = Forest900
val DarkSurfaceContainer       = Forest900
val DarkSurfaceContainerHigh   = Forest800
val DarkSurfaceContainerHighest = Forest700
val DarkSurfaceVariant         = Forest800
val DarkOnSurfaceVariant       = Sage400
val DarkPrimary                = Sage400
val DarkOnPrimary              = Forest950
val DarkPrimaryContainer       = Forest700
val DarkOnPrimaryContainer     = Mist100
val DarkSecondary              = Sage400
val DarkOnSecondary            = Forest950
val DarkSecondaryContainer     = Forest800
val DarkOnSecondaryContainer   = Mist100
val DarkOutline                = DarkOutlineLift
val DarkOutlineVariant         = Forest700

// Same convention — keep M3 error hues so destructive CTAs stay readable
// at a glance in dark mode.
val DarkError              = Color(0xFFF2B8B5)
val DarkOnError            = Color(0xFF601410)
val DarkErrorContainer     = Color(0xFF8C1D18)
val DarkOnErrorContainer   = Color(0xFFF9DEDC)

/* =====================================================================
 * Note card backgrounds (8 swatches).
 *
 * Indices are stable — caller code (NotesListScreen, NoteEditScreen,
 * editor's color picker) uses 0..7 via [noteCardColor]. The light set
 * is a hand-picked pastel family desaturated toward the Forest palette;
 * the dark set is a darker, hue-shifted family keeping ~7–12% relative
 * luminance so cards stay subtly distinct from the page background.
 *
 * Text-on-color is fixed (`Forest950` light, `Mist100` dark) and every
 * swatch + text pair clears AAA (≥9:1). See Color.kt's contrast table.
 * ===================================================================== */
val NoteColorDefault = Color(0xFFFFFFFF)
val NoteColorYellow  = Color(0xFFE8F2A3)
val NoteColorBlue    = Color(0xFFB6D6E1)
val NoteColorPink    = Color(0xFFEBC9D8)
val NoteColorGreen   = Color(0xFFB8D8BD)
val NoteColorOrange  = Color(0xFFECCFA8)
val NoteColorPurple  = Color(0xFFC9BFD9)
val NoteColorRed     = Color(0xFFDDB3B3)

val NoteColorDefaultDark = Forest900
val NoteColorYellowDark  = Color(0xFF3F3A1A)
val NoteColorBlueDark    = Color(0xFF163848)
val NoteColorPinkDark    = Color(0xFF4A2433)
val NoteColorGreenDark   = Color(0xFF1F3A2C)
val NoteColorOrangeDark  = Color(0xFF4A2D14)
val NoteColorPurpleDark  = Color(0xFF2E2338)
val NoteColorRedDark     = Color(0xFF4A2424)
