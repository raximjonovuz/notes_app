package com.example.notes.ui.theme

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import android.os.Build

/* =====================================================================
 * V6.0 — Theme redesign · Theme entry point + ThemeManager
 *
 *   · `ThemeManager` keeps three pieces of state:
 *       - themeMode : System / Light / Dark    (default = SYSTEM)
 *       - dynamicColorEnabled : Boolean        (default = false)
 *       - darkMode (Boolean) : back-compat snapshot, derived from
 *         `themeMode`. Kept so legacy callers (`setDarkMode`,
 *         `notesHeaderBackground`, `noteCardColor`, etc.) keep working.
 *
 *   · `NotesTheme` resolves the effective dark-mode flag at composition
 *     time (so SYSTEM mode tracks `isSystemInDarkTheme()`) and exposes
 *     it via [LocalNotesTheme]. New screens should read theme state
 *     through the Local instead of reaching into [ThemeManager].
 *
 *   · Persistence:
 *       KEY_THEME_MODE   → "SYSTEM" / "LIGHT" / "DARK"
 *       KEY_DYNAMIC_COLOR → Boolean
 *       KEY_DARK_MODE     → legacy Boolean (read once for migration)
 *
 *   · System dark-mode handling: because the LEGACY callers
 *     (`notesHeaderBackground`, `noteCardColor`) read `ThemeManager.darkMode`
 *     directly inside @Composable contexts, we update that snapshot
 *     inside `NotesTheme` after resolving the SYSTEM branch via a
 *     public, non-persisting mutator helper. This keeps the existing
 *     helpers accurate without spinning up new APIs at every call site.
 * ===================================================================== */

/* -- Theme mode ------------------------------------------------------- */

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/* -- ColorScheme presets (static, spec-faithful "Deep Forest") ------- */

private val ForestDarkColorScheme = darkColorScheme(
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer
)

private val ForestLightColorScheme = lightColorScheme(
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer
)

/* -- CompositionLocal ------------------------------------------------- */

/**
 * Effective per-composition theme state. Read via `LocalNotesTheme.current.isDark`
 * from inside any screen wrapped in `NotesTheme { … }`.
 */
data class NotesThemeState(
    val isDark: Boolean,
    val themeMode: ThemeMode,
    val useDynamicColor: Boolean
)
val LocalNotesTheme = compositionLocalOf<NotesThemeState> {
    error("NotesTheme was not applied at this call site")
}

/* -- ThemeManager ----------------------------------------------------- */

object ThemeManager {
    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    private const val KEY_DARK_MODE = "dark_mode"
    private const val KEY_DYNAMIC_COLOR_OLD = "use_dynamic_color"

    /** Active mode. Defaults to LIGHT for clean white background. */
    var themeMode by mutableStateOf(ThemeMode.LIGHT)
        private set

    /** Dynamic color on/off — Android 12+. Defaults to true for device colors. */
    var dynamicColorEnabled by mutableStateOf(true)
        private set

    /**
     * Snapshot of the effective dark flag.
     *
     * For SYSTEM mode, this is updated by [updateDarkSnapshot]
     * every time NotesTheme resolves (i.e. on every recomposition
     * triggered by configuration change or by the user picking a new
     * mode). For LIGHT/DARK it's pinned at the time the user picked.
     */
    var darkMode by mutableStateOf(false)
        private set

    /** Apply a theme preference. Persists and updates the snapshots. */
    fun setMode(context: Context, mode: ThemeMode) {
        themeMode = mode
        val resolvedDark = when (mode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK  -> true
            ThemeMode.SYSTEM -> darkMode   // keep current; resolveDarkNow will refresh
        }
        if (mode != ThemeMode.SYSTEM) darkMode = resolvedDark
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_THEME_MODE, mode.name)
                putBoolean(KEY_DARK_MODE, darkMode)
            }
    }

    /** Back-compat alias for the old SettingsScreen boolean toggle. */
    fun setDarkMode(context: Context, value: Boolean) {
        setMode(context, if (value) ThemeMode.DARK else ThemeMode.LIGHT)
    }

    fun setDynamicColor(context: Context, value: Boolean) {
        dynamicColorEnabled = value
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_DYNAMIC_COLOR, value) }
    }

    /** Non-persisting helper used by NotesTheme to refresh the snapshot. */
    fun updateDarkSnapshot(value: Boolean) {
        darkMode = value
    }

    /**
     * Loads persisted values. Migrates from the legacy `dark_mode`
     * Boolean (V5 line) into the new `theme_mode` String when needed.
     */
    fun loadPersisted(context: Context): ThemeMode {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val v6Migrated = prefs.getBoolean("v6_white_default_applied", false)
        val mode = if (!v6Migrated) {
            prefs.edit {
                putString(KEY_THEME_MODE, ThemeMode.LIGHT.name)
                putBoolean(KEY_DARK_MODE, false)
                putBoolean(KEY_DYNAMIC_COLOR, false)
                putBoolean("v6_white_default_applied", true)
            }
            ThemeMode.LIGHT
        } else {
            when (prefs.getString(KEY_THEME_MODE, null)) {
                ThemeMode.SYSTEM.name -> ThemeMode.SYSTEM
                ThemeMode.LIGHT.name -> ThemeMode.LIGHT
                ThemeMode.DARK.name  -> ThemeMode.DARK
                else -> ThemeMode.LIGHT
            }
        }
        themeMode = mode
        // Initial guess — NotesTheme will overwrite with real value.
        darkMode = when (mode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK  -> true
            ThemeMode.SYSTEM -> false
        }
        dynamicColorEnabled = true
        return mode
    }
}

/* -- NotesTheme entry point ------------------------------------------ */

/**
 * Reads [ThemeManager] state and applies a Material3 ColorScheme +
 * Typography + Shapes to the inner content. Tracks `isSystemInDarkTheme`
 * live for SYSTEM mode.
 */
@Composable
fun NotesTheme(
    themeMode: ThemeMode = ThemeManager.themeMode,
    dynamicColor: Boolean = ThemeManager.dynamicColorEnabled,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK  -> true
        ThemeMode.SYSTEM -> systemDark
    }
    // Refresh the back-compat snapshot so legacy readers see truth.
    ThemeManager.updateDarkSnapshot(isDark)

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (isDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        isDark -> ForestDarkColorScheme
        else   -> ForestLightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insets = WindowCompat.getInsetsController(window, view)
            insets.isAppearanceLightStatusBars = !isDark
            insets.isAppearanceLightNavigationBars = !isDark
            // Edge-to-edge: transparent bars + scheme-driven body
            // means the app's `surface` color bleeds into them.
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
        }
    }

    CompositionLocalProvider(
        LocalNotesTheme provides NotesThemeState(
            isDark = isDark,
            themeMode = themeMode,
            useDynamicColor = dynamicColor
        )
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NotesTypography,
            shapes = Shapes,
            content = content
        )
    }
}

/* -- Back-compat helpers used by existing screens ------------------- */
/*    NotesListScreen / NoteEditScreen / LoginScreen / SettingsScreen */

@Composable
fun notesHeaderBackground(): Color {
    // V6 unifies: header sits on `surface`. Cards draw on
    // `surfaceContainer` so the page edge still reads as a hierarchy.
    return MaterialTheme.colorScheme.surface
}

@Composable
fun notesPageBackground(): Color {
    return MaterialTheme.colorScheme.surfaceContainerLow
}

@Composable
fun noteCardColor(index: Int): Color {
    val isDark = LocalNotesTheme.current.isDark
    return when (index) {
        1 -> if (isDark) NoteColorYellowDark else NoteColorYellow
        2 -> if (isDark) NoteColorBlueDark else NoteColorBlue
        3 -> if (isDark) NoteColorPinkDark else NoteColorPink
        4 -> if (isDark) NoteColorGreenDark else NoteColorGreen
        5 -> if (isDark) NoteColorOrangeDark else NoteColorOrange
        6 -> if (isDark) NoteColorPurpleDark else NoteColorPurple
        7 -> if (isDark) NoteColorRedDark else NoteColorRed
        8 -> if (isDark) NoteColorTealDark else NoteColorTeal
        9 -> if (isDark) NoteColorGrayDark else NoteColorGray
        else -> if (isDark) NoteColorDefaultDark else NoteColorDefault
    }
}

@Composable
fun noteCardOnColor(index: Int): Color {
    val isDark = LocalNotesTheme.current.isDark
    return if (isDark) Color(0xFFE8EAED) else Color(0xFF202124)
}
