package com.example.notes.ui.theme

import androidx.compose.ui.graphics.Color

// Light theme
val LightPrimary = Color(0xFF1976D2)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightBackground = Color(0xFFFAFAFA)
val LightOnBackground = Color(0xFF1C1B1F)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF1C1B1F)
val LightSurfaceVariant = Color(0xFFF5F5F5)
val LightOnSurfaceVariant = Color(0xFF6B6B6B)
// V5.0.1: header uses Material 3 surfaceContainer instead of a hand-rolled gray.
// Combined with the elevated shadow under the title this gives a more
// "card-stack" feel without us having to draw the divider ourselves.
val LightHeaderBg = Color(0xFFF7F7F8)
val LightPageBg = Color(0xFFE8E8E8)    // Page background outside card

// Dark theme
val DarkPrimary = Color(0xFF90CAF9)
val DarkOnPrimary = Color(0xFF000000)
val DarkBackground = Color(0xFF121212)
val DarkOnBackground = Color(0xFFE0E0E0)
val DarkSurface = Color(0xFF1E1E1E)
val DarkOnSurface = Color(0xFFE0E0E0)
val DarkSurfaceVariant = Color(0xFF2C2C2C)
val DarkOnSurfaceVariant = Color(0xFFB0B0B0)
val DarkHeaderBg = Color(0xFF161618)
val DarkPageBg = Color(0xFF0A0A0A)

// Note background colors
val NoteColorDefault = Color(0xFFFFFFFF)   // 0 - white
val NoteColorYellow = Color(0xFFFFF59D)    // 1 - yellow
val NoteColorBlue = Color(0xFFB3E5FC)      // 2 - blue
val NoteColorPink = Color(0xFFF8BBD0)      // 3 - pink
// V4.5: extended palette to match Google Keep / Apple Notes.
val NoteColorGreen = Color(0xFFC8E6C9)     // 4 - green
val NoteColorOrange = Color(0xFFFFCC80)    // 5 - orange
val NoteColorPurple = Color(0xFFE1BEE7)    // 6 - purple
val NoteColorRed = Color(0xFFFFCDD2)       // 7 - red

val NoteColorDefaultDark = Color(0xFF2A2A2A)
val NoteColorYellowDark = Color(0xFF6B5F2D)
val NoteColorBlueDark = Color(0xFF2C5670)
val NoteColorPinkDark = Color(0xFF6B3F58)
val NoteColorGreenDark = Color(0xFF3D5C3F)
val NoteColorOrangeDark = Color(0xFF7A5328)
val NoteColorPurpleDark = Color(0xFF4E3B57)
val NoteColorRedDark = Color(0xFF6B3838)