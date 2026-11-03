package com.example.notes.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class NoteColorKey(val key: String) {
    DEFAULT("default"),
    RED("red"),
    ORANGE("orange"),
    YELLOW("yellow"),
    GREEN("green"),
    TEAL("teal"),
    BLUE("blue"),
    DARK_BLUE("dark_blue"),
    PURPLE("purple"),
    PINK("pink"),
    BROWN("brown"),
    GRAY("gray");

    companion object {
        fun fromKey(key: String?): NoteColorKey =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: DEFAULT

        fun fromIndex(index: Int): NoteColorKey = when (index) {
            1 -> YELLOW
            2 -> BLUE
            3 -> PINK
            4 -> GREEN
            5 -> ORANGE
            6 -> PURPLE
            7 -> RED
            8 -> TEAL
            9 -> GRAY
            else -> DEFAULT
        }
    }
}

data class NoteColorToken(
    val key: NoteColorKey,
    val light: Color,
    val dark: Color,
    val labelUz: String
)

val NotePalette = listOf(
    NoteColorToken(NoteColorKey.DEFAULT, Color(0xFFFFFFFF), Color(0xFF202124), "Standart"),
    NoteColorToken(NoteColorKey.RED, Color(0xFFF28B82), Color(0xFF77172E), "Qizil"),
    NoteColorToken(NoteColorKey.ORANGE, Color(0xFFFBBE83), Color(0xFF6D4C2B), "To'q sariq"),
    NoteColorToken(NoteColorKey.YELLOW, Color(0xFFFFF475), Color(0xFF534E23), "Sariq"),
    NoteColorToken(NoteColorKey.GREEN, Color(0xFFCCFB90), Color(0xFF1E4620), "Yashil"),
    NoteColorToken(NoteColorKey.TEAL, Color(0xFFA7FFEB), Color(0xFF0F464A), "Havorang-yashil"),
    NoteColorToken(NoteColorKey.BLUE, Color(0xFFCBF0F8), Color(0xFF09333F), "Moviy"),
    NoteColorToken(NoteColorKey.DARK_BLUE, Color(0xFFAFCBFA), Color(0xFF1B3A6F), "To'q ko'k"),
    NoteColorToken(NoteColorKey.PURPLE, Color(0xFFD7AEFB), Color(0xFF4A2A62), "Binafsharang"),
    NoteColorToken(NoteColorKey.PINK, Color(0xFFFDCFE8), Color(0xFF5A2E4A), "Pushti"),
    NoteColorToken(NoteColorKey.BROWN, Color(0xFFE6C9A8), Color(0xFF513A2D), "Jigarrang"),
    NoteColorToken(NoteColorKey.GRAY, Color(0xFFE8EAED), Color(0xFF3C4043), "Kulrang")
)

fun noteOnColor(isDark: Boolean): Color =
    if (isDark) Color(0xFFE8EAED) else Color(0xFF202124)

/**
 * Calculate sRGB relative luminance according to WCAG 2.1 specs.
 */
fun relativeLuminance(color: Color): Double {
    fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    val r = channel(color.red)
    val g = channel(color.green)
    val b = channel(color.blue)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/**
 * Contrast ratio (L1 + 0.05) / (L2 + 0.05)
 */
fun contrastRatio(c1: Color, c2: Color): Double {
    val l1 = relativeLuminance(c1)
    val l2 = relativeLuminance(c2)
    val lighter = max(l1, l2)
    val darker = min(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)
}
