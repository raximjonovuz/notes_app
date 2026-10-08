package com.example.notes.ui.theme

import android.app.Activity
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.content.edit
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant
)

/**
 * Holds the current theme. Mutated through [setDarkMode] (which also writes
 * to disk). Read by composables via the [darkMode] public getter which is a
 * Compose [mutableStateOf] so any recomposition will pick up the latest value.
 *
 * Persisted in [PREFS_NAME] under [KEY_DARK_MODE]. The disk read happens
 * lazily on first [setDarkMode] call and once eagerly from
 * [com.example.notes.NotesApplication.onCreate] so the very first screen
 * the user sees already has the correct theme.
 */
object ThemeManager {
    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_DARK_MODE = "dark_mode"

    /** Backing state. Read by [notesHeaderBackground] / [noteCardColor] / etc. */
    var darkMode by mutableStateOf(false)
        private set

    /**
     * Apply a dark-mode preference. Persists to SharedPreferences so the
     * choice survives process death.
     */
    fun setDarkMode(context: Context, value: Boolean) {
        darkMode = value
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_DARK_MODE, value) }
    }

    /**
     * Loads the persisted value into [darkMode]. Idempotent — calling
     * multiple times has no adverse effect. Returns the loaded value
     * for callers that want to chain UI changes after the read.
     */
    fun loadPersisted(context: Context): Boolean {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val v = prefs.getBoolean(KEY_DARK_MODE, false)
        darkMode = v
        return v
    }
}

@Composable
fun NotesTheme(
    darkTheme: Boolean = ThemeManager.darkMode,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun notesHeaderBackground(): Color {
    return if (ThemeManager.darkMode) DarkHeaderBg else LightHeaderBg
}

@Composable
fun notesPageBackground(): Color {
    return if (ThemeManager.darkMode) DarkPageBg else LightPageBg
}

@Composable
fun noteCardColor(index: Int): Color {
    val dark = ThemeManager.darkMode
    return when (index) {
        1 -> if (dark) NoteColorYellowDark else NoteColorYellow
        2 -> if (dark) NoteColorBlueDark else NoteColorBlue
        3 -> if (dark) NoteColorPinkDark else NoteColorPink
        4 -> if (dark) NoteColorGreenDark else NoteColorGreen
        5 -> if (dark) NoteColorOrangeDark else NoteColorOrange
        6 -> if (dark) NoteColorPurpleDark else NoteColorPurple
        7 -> if (dark) NoteColorRedDark else NoteColorRed
        else -> if (dark) NoteColorDefaultDark else NoteColorDefault
    }
}