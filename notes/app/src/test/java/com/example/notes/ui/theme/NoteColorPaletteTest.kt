package com.example.notes.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

class NoteColorPaletteTest {

    @Test
    fun `all 12 note colors meet WCAG 2_1 AA contrast ratio 4_5 to 1 in both light and dark themes`() {
        val lightOn = noteOnColor(isDark = false)
        val darkOn = noteOnColor(isDark = true)

        for (token in NotePalette) {
            val lightRatio = contrastRatio(token.light, lightOn)
            val darkRatio = contrastRatio(token.dark, darkOn)

            assertTrue(
                "Light contrast for ${token.key.key} must be >= 4.5:1, was ${String.format("%.2f", lightRatio)}:1",
                lightRatio >= 4.5
            )
            assertTrue(
                "Dark contrast for ${token.key.key} must be >= 4.5:1, was ${String.format("%.2f", darkRatio)}:1",
                darkRatio >= 4.5
            )
        }
    }
}
