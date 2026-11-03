package com.example.notes.ui.edit

import androidx.compose.ui.text.TextRange

sealed interface EditorCommand {
    data object ToggleBold : EditorCommand
    data object ToggleItalic : EditorCommand
    data object ToggleUnderline : EditorCommand
    data object ToggleStrike : EditorCommand
    data class SetColor(val colorKey: String?) : EditorCommand
    data class SetParagraph(val kind: ParagraphKind) : EditorCommand
    data object ToggleChecklist : EditorCommand
}

data class EditorToolbarState(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val colorKey: String? = null,
    val paragraph: ParagraphKind = ParagraphKind.BODY,
    val checklist: Boolean = false
)

object EditorResolver {

    /**
     * Compute paragraph runs for a given text based on '\n' delimiters.
     * Preserves existing kinds and checked states where possible.
     */
    fun computeParagraphRuns(
        text: String,
        existingRuns: List<ParagraphRun> = emptyList()
    ): List<ParagraphRun> {
        if (text.isEmpty()) {
            val defaultKind = existingRuns.firstOrNull()?.kind ?: ParagraphKind.BODY
            val defaultChecked = existingRuns.firstOrNull()?.checked ?: false
            return listOf(ParagraphRun(start = 0, end = 0, kind = defaultKind, checked = defaultChecked))
        }

        val lines = text.split('\n')
        val runs = mutableListOf<ParagraphRun>()
        var offset = 0

        for (i in lines.indices) {
            val line = lines[i]
            val pStart = offset
            val pEnd = offset + line.length
            offset = pEnd + 1 // +1 for '\n'

            // Try to find matching existing run by overlap or line index
            val existing = existingRuns.getOrNull(i)
            val kind = existing?.kind ?: ParagraphKind.BODY
            val checked = existing?.checked ?: false

            runs += ParagraphRun(
                start = pStart,
                end = pEnd,
                kind = kind,
                checked = checked
            )
        }
        return runs
    }

    /**
     * Apply paragraph style change (H1, H2, BODY, CHECKLIST) ONLY to paragraphs
     * that intersect with the given selection.
     */
    fun applyParagraphKind(
        text: String,
        runs: List<ParagraphRun>,
        selection: TextRange,
        newKind: ParagraphKind
    ): List<ParagraphRun> {
        val s = selection.min.coerceIn(0, text.length)
        val e = selection.max.coerceIn(0, text.length)

        return runs.map { p ->
            // Check intersection: either selection overlaps the paragraph interval,
            // or selection is collapsed at the boundary of the paragraph.
            val intersects = if (s == e) {
                if (p.start == p.end) s == p.start
                else s in p.start..p.end
            } else {
                maxOf(s, p.start) < minOf(e, p.end) || (s <= p.start && e >= p.end)
            }

            if (intersects) {
                p.copy(kind = newKind)
            } else {
                p
            }
        }
    }

    /**
     * Resolve toolbar state from the current cursor / selection inside a TextBlock.
     */
    fun resolveToolbarState(
        text: String,
        runs: List<ParagraphRun>,
        spans: List<CharSpan>,
        selection: TextRange
    ): EditorToolbarState {
        val s = selection.min.coerceIn(0, text.length)
        val e = selection.max.coerceIn(0, text.length)

        // Find intersecting paragraphs
        val intersectingRuns = runs.filter { p ->
            if (s == e) s in p.start..p.end
            else maxOf(s, p.start) < minOf(e, p.end) || (s <= p.start && e >= p.end)
        }
        val activeKind = intersectingRuns.firstOrNull()?.kind ?: ParagraphKind.BODY
        val isChecklist = activeKind == ParagraphKind.CHECKLIST

        // Resolve active spans
        val targetPos = if (s == e) (s - 1).coerceAtLeast(0) else s
        val activeSpans = spans.filter { it.start <= targetPos && targetPos < it.end }

        val bold = activeSpans.any { it.bold }
        val italic = activeSpans.any { it.italic }
        val underline = activeSpans.any { it.underline }
        val strike = activeSpans.any { it.strikethrough }
        val colorKey = activeSpans.lastOrNull { it.colorKey != null }?.colorKey

        return EditorToolbarState(
            bold = bold,
            italic = italic,
            underline = underline,
            strike = strike,
            colorKey = colorKey,
            paragraph = activeKind,
            checklist = isChecklist
        )
    }
}
