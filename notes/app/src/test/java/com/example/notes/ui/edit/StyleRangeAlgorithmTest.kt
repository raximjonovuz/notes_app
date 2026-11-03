package com.example.notes.ui.edit

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleRangeAlgorithmTest {

    @Test
    fun `bold 0 to 10 then underline 3 to 5 splits into 3 ranges and preserves bold outside`() {
        val initial = listOf(
            StyleRange(start = 0, end = 10, bold = true)
        )
        // Underline 3..5
        val result = StyleRanges.toggleAttribute(initial, TextRange(3, 5), StyleAttribute.UNDERLINE)

        // Expected:
        // 0..3: bold
        // 3..5: bold + underline
        // 5..10: bold
        assertEquals(3, result.size)

        assertEquals(0, result[0].start)
        assertEquals(3, result[0].end)
        assertTrue(result[0].bold)
        assertFalse(result[0].underline)

        assertEquals(3, result[1].start)
        assertEquals(5, result[1].end)
        assertTrue(result[1].bold)
        assertTrue(result[1].underline)

        assertEquals(5, result[2].start)
        assertEquals(10, result[2].end)
        assertTrue(result[2].bold)
        assertFalse(result[2].underline)
    }

    @Test
    fun `toggle on unstyled text creates a new span for the whole selection`() {
        val empty = emptyList<StyleRange>()
        val result = StyleRanges.toggleAttribute(empty, TextRange(4, 9), StyleAttribute.BOLD)

        assertEquals(1, result.size)
        assertEquals(4, result[0].start)
        assertEquals(9, result[0].end)
        assertTrue(result[0].bold)
    }

    @Test
    fun `toggle on partially styled selection turns all to styled`() {
        // 2..6 is already bold. User selects 0..10 and clicks bold.
        // Since not all is bold, all 0..10 should become bold.
        val initial = listOf(
            StyleRange(start = 2, end = 6, bold = true)
        )
        val result = StyleRanges.toggleAttribute(initial, TextRange(0, 10), StyleAttribute.BOLD)

        assertEquals(1, result.size)
        assertEquals(0, result[0].start)
        assertEquals(10, result[0].end)
        assertTrue(result[0].bold)
    }

    @Test
    fun `toggle on fully styled selection turns attribute off`() {
        // 0..10 is bold. User selects 2..6 and toggles bold off.
        val initial = listOf(
            StyleRange(start = 0, end = 10, bold = true)
        )
        val result = StyleRanges.toggleAttribute(initial, TextRange(2, 6), StyleAttribute.BOLD)

        // Expected: 0..2 bold, 6..10 bold
        assertEquals(2, result.size)
        assertEquals(0, result[0].start)
        assertEquals(2, result[0].end)
        assertTrue(result[0].bold)

        assertEquals(6, result[1].start)
        assertEquals(10, result[1].end)
        assertTrue(result[1].bold)
    }

    @Test
    fun `setColor across selection splits and updates colorIndex without losing styles`() {
        val initial = listOf(
            StyleRange(start = 0, end = 8, bold = true)
        )
        val result = StyleRanges.setColor(initial, TextRange(2, 6), colorIndex = 3)

        assertEquals(3, result.size)
        assertEquals(0, result[0].start)
        assertEquals(2, result[0].end)
        assertTrue(result[0].bold)
        assertEquals(0, result[0].colorIndex)

        assertEquals(2, result[1].start)
        assertEquals(6, result[1].end)
        assertTrue(result[1].bold)
        assertEquals(3, result[1].colorIndex)

        assertEquals(6, result[2].start)
        assertEquals(8, result[2].end)
        assertTrue(result[2].bold)
        assertEquals(0, result[2].colorIndex)
    }

    @Test
    fun `active state query and renderer match for overlapping and canonical ranges`() {
        val ranges = listOf(
            StyleRange(start = 0, end = 5, bold = true),
            StyleRange(start = 3, end = 8, underline = true)
        )
        // Canonical form representation
        val canonical = StyleRanges.canonical(ranges)
        // At position 4: both bold and underline are active
        val attrAt4 = StyleRanges.attributeAt(canonical, 4)
        assertTrue(attrAt4.bold)
        assertTrue(attrAt4.underline)

        // Fully styled check
        assertTrue(StyleRanges.isFullyStyled(canonical, TextRange(0, 5), 10, StyleAttribute.BOLD))
        assertFalse(StyleRanges.isFullyStyled(canonical, TextRange(0, 8), 10, StyleAttribute.BOLD))
        assertTrue(StyleRanges.isFullyStyled(canonical, TextRange(3, 8), 10, StyleAttribute.UNDERLINE))
    }
}
