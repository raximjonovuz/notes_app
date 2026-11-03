package com.example.notes.ui.edit

object ChecklistReducer {

    /**
     * Handles Enter key press on a paragraph run.
     * If the item is empty, exits checklist by converting to BODY.
     * Otherwise, splits into current checklist item and a new unchecked checklist item.
     */
    fun onEnter(
        currentRun: ParagraphRun,
        cursorOffset: Int
    ): Pair<ParagraphRun, ParagraphRun> {
        val isEmpty = currentRun.start == currentRun.end
        return if (currentRun.kind == ParagraphKind.CHECKLIST && isEmpty) {
            // Empty checklist item -> exit checklist mode, become normal BODY
            currentRun.copy(kind = ParagraphKind.BODY) to currentRun.copy(
                start = currentRun.end,
                end = currentRun.end,
                kind = ParagraphKind.BODY
            )
        } else {
            // Non-empty -> split at cursor, create fresh unchecked item
            val clampedCursor = cursorOffset.coerceIn(currentRun.start, currentRun.end)
            val first = currentRun.copy(end = clampedCursor)
            val second = currentRun.copy(
                start = clampedCursor,
                checked = false
            )
            first to second
        }
    }

    /**
     * Handles Backspace on an empty checklist item.
     * Converts empty checklist item back to a plain BODY paragraph.
     */
    fun onBackspaceOnEmpty(run: ParagraphRun): ParagraphRun {
        return if (run.kind == ParagraphKind.CHECKLIST && run.start == run.end) {
            run.copy(kind = ParagraphKind.BODY, checked = false)
        } else {
            run
        }
    }

    /**
     * Stable reorder of checked items: unchecked items stay first, checked items moved to bottom.
     */
    fun reorderChecked(runs: List<ParagraphRun>): List<ParagraphRun> {
        val nonChecklist = runs.filter { it.kind != ParagraphKind.CHECKLIST }
        val checklistUnchecked = runs.filter { it.kind == ParagraphKind.CHECKLIST && !it.checked }
        val checklistChecked = runs.filter { it.kind == ParagraphKind.CHECKLIST && it.checked }

        return checklistUnchecked + checklistChecked + nonChecklist
    }
}
