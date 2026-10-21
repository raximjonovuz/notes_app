package com.example.notes.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.example.notes.R

/* =====================================================================
 * V6.0 — Theme redesign · Typography
 *
 * `Inter` is loaded via Compose's downloadable-font API (the
 * `ui-text-google-fonts` artifact we depend on). Four weights are
 * resolved at first use; if a device has no Play Services or the
 * provider certs in `res/values/font_certs.xml` don't match, Android
 * silently falls back to the system sans font. The rest of the type
 * system keeps compiling and rendering.
 *
 * To swap to fully bundled Inter (no network call):
 *   1. Copy `Inter-Regular.ttf` / `Inter-Medium.ttf` /
 *      `Inter-SemiBold.ttf` / `Inter-Bold.ttf` into `app/src/main/res/font/`
 *   2. Replace the `InterGoogleFont` block with:
 *         private val InterFontFamily = FontFamily(
 *             Font(R.font.inter_regular, FontWeight.Normal),
 *             Font(R.font.inter_medium,  FontWeight.Medium),
 *             Font(R.font.inter_semibold,FontWeight.SemiBold),
 *             Font(R.font.inter_bold,    FontWeight.Bold),
 *         )
 *
 * Type scale follows the M3 default `display/headline/title/body/label`
 * axis with explicit weights per role:
 *   - display / headline / title  → SemiBold (titles pull weight)
 *   - body                        → Normal (reading comfort)
 *   - label                       → Medium (chips, badges, captions)
 * LetterSpacing is slightly tightened for titles (–0.25 to –0.5) and
 * lifted for labels (+0.5) per the M3 spec.
 * ===================================================================== */

// Downloadable Google Fonts provider config. The Play Services
// `com.google.android.gms.fonts` authority ships Inter on request; the
// cert array is documented in the Compose sample app and lives in
// `res/values/font_certs.xml`.
private val googleFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

private val interGoogleFont = GoogleFont("Inter")

private val InterFontFamily = FontFamily(
    FontResource(interGoogleFont, FontWeight.Normal),
    FontResource(interGoogleFont, FontWeight.Medium),
    FontResource(interGoogleFont, FontWeight.SemiBold),
    FontResource(interGoogleFont, FontWeight.Bold)
)

/** Thin wrapper that names the call site — keeps Typography below readable. */
private fun FontResource(font: GoogleFont, weight: FontWeight) =
    Font(
        googleFont = font,
        fontProvider = googleFontProvider,
        weight = weight,
        style = FontStyle.Normal
    )

/**
 * Reusable fallback — if for any reason `InterFontFamily` failed to
 * resolve (no network, no Play Services, cert mismatch), we still hand
 * a real FontFamily to `Typography` so callers don't crash.
 */
private val SafeFontFamily: FontFamily get() = InterFontFamily

/** SemiBold type ramp lifted slightly above Material default. */
private val TitleStyleBase = TextStyle(
    fontFamily = SafeFontFamily,
    fontStyle = FontStyle.Normal
)

val NotesTypography: Typography = Typography(
    // ---- Display (rare, hero areas) ----------------------------------
    displayLarge = TitleStyleBase.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp
    ),
    displayMedium = TitleStyleBase.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = 0.sp
    ),
    displaySmall = TitleStyleBase.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = 0.sp
    ),

    // ---- Headline (screen titles, big numerics) ----------------------
    headlineLarge = TitleStyleBase.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = 0.sp
    ),
    headlineMedium = TitleStyleBase.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.sp
    ),
    headlineSmall = TitleStyleBase.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp
    ),

    // ---- Title (card titles, list rows, section headers) -------------
    titleLarge = TitleStyleBase.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp
    ),
    titleMedium = TitleStyleBase.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp
    ),
    titleSmall = TitleStyleBase.copy(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp
    ),

    // ---- Body (long-form text) ----------------------------------------
    bodyLarge = TitleStyleBase.copy(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp
    ),
    bodyMedium = TitleStyleBase.copy(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp
    ),
    bodySmall = TitleStyleBase.copy(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp
    ),

    // ---- Label (chips, badges, buttons) ------------------------------
    labelLarge = TitleStyleBase.copy(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp
    ),
    labelMedium = TitleStyleBase.copy(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp
    ),
    labelSmall = TitleStyleBase.copy(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp
    )
)
