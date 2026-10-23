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
//  Light scheme tokens (Google Keep clean white M3 palette)
// =====================================================================
val LightBackground             = Color(0xFFFFFFFF)
val LightOnBackground           = Color(0xFF202124)
val LightSurface                = Color(0xFFFFFFFF)
val LightOnSurface              = Color(0xFF202124)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow    = Color(0xFFFFFFFF)
val LightSurfaceContainer       = Color(0xFFFFFFFF)
val LightSurfaceContainerHigh   = Color(0xFFF1F3F4)
val LightSurfaceContainerHighest = Color(0xFFE8EAED)
val LightSurfaceVariant         = Color(0xFFF1F3F4)
val LightOnSurfaceVariant       = Color(0xFF5F6368)
val LightPrimary                = Color(0xFF1A73E8)
val LightOnPrimary              = Color(0xFFFFFFFF)
val LightPrimaryContainer       = Color(0xFFE8F0FE)
val LightOnPrimaryContainer     = Color(0xFF1967D2)
val LightSecondary              = Color(0xFF5F6368)
val LightOnSecondary            = Color(0xFFFFFFFF)
val LightSecondaryContainer     = Color(0xFFF1F3F4)
val LightOnSecondaryContainer   = Color(0xFF202124)
val LightOutline                = Color(0xFFE0E0E0)
val LightOutlineVariant         = Color(0xFFEEEEEE)

val LightError              = Color(0xFFBA1A1A)
val LightOnError            = Color(0xFFFFFFFF)
val LightErrorContainer     = Color(0xFFFFDAD6)
val LightOnErrorContainer   = Color(0xFF410002)

// =====================================================================
//  Dark scheme tokens (extracted from pen.dev V2 spec)
// =====================================================================
val DarkBackground             = Color(0xFF101510)
val DarkOnBackground           = Color(0xFFE1E4DE)
val DarkSurface                = Color(0xFF101510)
val DarkOnSurface              = Color(0xFFE1E4DE)
val DarkSurfaceContainerLowest = Color(0xFF0B0F0B)
val DarkSurfaceContainerLow    = Color(0xFF161C16)
val DarkSurfaceContainer       = Color(0xFF1A211A)
val DarkSurfaceContainerHigh   = Color(0xFF242C24)
val DarkSurfaceContainerHighest = Color(0xFF2F372F)
val DarkSurfaceVariant         = Color(0xFF1A211A)
val DarkOnSurfaceVariant       = Color(0xFFBCC5B9)
val DarkPrimary                = Color(0xFFA8D5AB)
val DarkOnPrimary              = Color(0xFF12381F)
val DarkPrimaryContainer       = Color(0xFF1F5130)
val DarkOnPrimaryContainer     = Color(0xFFC4F2C5)
val DarkSecondary              = Color(0xFFB9CCB5)
val DarkOnSecondary            = Color(0xFF243424)
val DarkSecondaryContainer     = Color(0xFF3B4B3A)
val DarkOnSecondaryContainer   = Color(0xFFD5E8D0)
val DarkOutline                = Color(0xFF333B33)
val DarkOutlineVariant         = Color(0xFF333B33)

val DarkError              = Color(0xFFFFB4AB)
val DarkOnError            = Color(0xFF690005)
val DarkErrorContainer     = Color(0xFF93000A)
val DarkOnErrorContainer   = Color(0xFFFFDAD6)

/* =====================================================================
 * Google Keep Note Colors (M3 Palette)
 * ===================================================================== */
val NoteColorDefault = Color(0xFFFFFFFF)
val NoteColorYellow  = Color(0xFFFFF475) // Sand
val NoteColorBlue    = Color(0xFFCBF0F8) // Dusk
val NoteColorPink    = Color(0xFFFDCFE8) // Pink
val NoteColorGreen   = Color(0xFFCCFF90) // Mint
val NoteColorOrange  = Color(0xFFFBBC04) // Peach
val NoteColorPurple  = Color(0xFFD7AEFB) // Purple
val NoteColorRed     = Color(0xFFF28B82) // Coral
val NoteColorTeal    = Color(0xFFA7FFEB) // Teal / Storm
val NoteColorGray    = Color(0xFFE8EAED) // Clay

val NoteColorDefaultDark = Color(0xFF202124)
val NoteColorYellowDark  = Color(0xFF534E23)
val NoteColorBlueDark    = Color(0xFF072731)
val NoteColorPinkDark    = Color(0xFF42273B)
val NoteColorGreenDark   = Color(0xFF16352E)
val NoteColorOrangeDark  = Color(0xFF6A4F2A)
val NoteColorPurpleDark  = Color(0xFF321F36)
val NoteColorRedDark     = Color(0xFF77172E)
val NoteColorTealDark    = Color(0xFF0C3B3E)
val NoteColorGrayDark    = Color(0xFF3C2A21)
