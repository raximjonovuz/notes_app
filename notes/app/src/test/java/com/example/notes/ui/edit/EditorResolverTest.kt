package com.example.notes.ui.edit

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorResolverTest {

    @Test
    fun `computeParagraphRuns splits text by newline correctly`() {
        val text = "First line\nSecond line\nThird"
        val runs = EditorResolver.computeParagraphRuns(text)

        assertEquals(3, runs.size)
        assertEquals(0, runs[0].start)
        assertEquals(10, runs[0].end)
        assertEquals(ParagraphKind.BODY, runs[0].kind)

        assertEquals(11, runs[1].start)
        assertEquals(22, runs[1].end)

        assertEquals(23, runs[2].start)
        assertEquals(28, runs[2].end)
    }

    @Test
    fun `applyParagraphKind only affects paragraphs intersecting selection`() {
        val text = "Line 1\nLine 2\nLine 3"
        val runs = EditorResolver.computeParagraphRuns(text)

        // Select part of Line 2 (offsets: Line 1 is 0..6, Line 2 is 7..13, Line 3 is 14..20)
        val selection = TextRange(8, 10)
        val updated = EditorResolver.applyParagraphKind(text, runs, selection, ParagraphKind.H1)

        assertEquals(3, updated.size)
        assertEquals(ParagraphKind.BODY, updated[0].kind)
        assertEquals(ParagraphKind.H1, updated[1].kind)
        assertEquals(ParagraphKind.BODY, updated[2].kind)
    }

    @Test
    fun `multi-paragraph selection updates all intersected paragraphs`() {
        val text = "Line 1\nLine 2\nLine 3"
        val runs = EditorResolver.computeParagraphRuns(text)

        // Select across Line 1 and Line 2
        val selection = TextRange(2, 9)
        val updated = EditorResolver.applyParagraphKind(text, runs, selection, ParagraphKind.H2)

        assertEquals(ParagraphKind.H2, updated[0].kind)
        assertEquals(ParagraphKind.H2, updated[1].kind)
        assertEquals(ParagraphKind.BODY, updated[2].kind)
    }

    @Test
    fun `resolveToolbarState returns correct state for cursor inside styled span`() {
        val text = "Hello world"
        val runs = listOf(ParagraphRun(0, 11, ParagraphKind.BODY))
        val spans = listOf(
            CharSpan(start = 0, end = 5, bold = true, colorKey = "blue"),
            CharSpan(start = 6, end = 11, italic = true)
        )

        // Cursor at position 3 (inside "Hello")
        val state1 = EditorResolver.resolveToolbarState(text, runs, spans, TextRange(3, 3))
        assertTrue(state1.bold)
        assertFalse(state1.italic)
        assertEquals("blue", state1.colorKey)
        assertEquals(ParagraphKind.BODY, state1.paragraph)

        // Cursor at position 8 (inside "world")
        val state2 = EditorResolver.resolveToolbarState(text, runs, spans, TextRange(8, 8))
        assertFalse(state2.bold)
        assertTrue(state2.italic)
        assertEquals(null, state2.colorKey)
    }
}
