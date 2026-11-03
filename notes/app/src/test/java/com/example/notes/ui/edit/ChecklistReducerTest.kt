package com.example.notes.ui.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecklistReducerTest {

    @Test
    fun `non-empty checklist item splits on Enter and creates unchecked child`() {
        val run = ParagraphRun(start = 0, end = 10, kind = ParagraphKind.CHECKLIST, checked = true)
        val (first, second) = ChecklistReducer.onEnter(run, cursorOffset = 5)

        assertEquals(0, first.start)
        assertEquals(5, first.end)
        assertEquals(ParagraphKind.CHECKLIST, first.kind)
        assertTrue(first.checked) // Original maintains its checked status

        assertEquals(5, second.start)
        assertEquals(10, second.end)
        assertEquals(ParagraphKind.CHECKLIST, second.kind)
        assertFalse(second.checked) // New item is always unchecked
    }

    @Test
    fun `empty checklist item on Enter exits checklist and converts to BODY`() {
        val run = ParagraphRun(start = 5, end = 5, kind = ParagraphKind.CHECKLIST, checked = false)
        val (first, second) = ChecklistReducer.onEnter(run, cursorOffset = 5)

        assertEquals(ParagraphKind.BODY, first.kind)
        assertEquals(ParagraphKind.BODY, second.kind)
    }

    @Test
    fun `backspace on empty checklist item converts to BODY`() {
        val run = ParagraphRun(start = 12, end = 12, kind = ParagraphKind.CHECKLIST, checked = false)
        val converted = ChecklistReducer.onBackspaceOnEmpty(run)

        assertEquals(ParagraphKind.BODY, converted.kind)
        assertFalse(converted.checked)
    }

    @Test
    fun `reorderChecked places unchecked items before checked items`() {
        val runs = listOf(
            ParagraphRun(0, 5, ParagraphKind.CHECKLIST, checked = true),
            ParagraphRun(6, 12, ParagraphKind.CHECKLIST, checked = false),
            ParagraphRun(13, 20, ParagraphKind.CHECKLIST, checked = true),
            ParagraphRun(21, 28, ParagraphKind.CHECKLIST, checked = false)
        )

        val reordered = ChecklistReducer.reorderChecked(runs)
        assertEquals(4, reordered.size)
        // 1st & 2nd should be unchecked
        assertFalse(reordered[0].checked)
        assertEquals(6, reordered[0].start)
        assertFalse(reordered[1].checked)
        assertEquals(21, reordered[1].start)
        // 3rd & 4th should be checked
        assertTrue(reordered[2].checked)
        assertEquals(0, reordered[2].start)
        assertTrue(reordered[3].checked)
        assertEquals(13, reordered[3].start)
    }
}
