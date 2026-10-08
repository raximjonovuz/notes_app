package com.example.notes.ui.edit

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * V2.6 — per-character rich-text styling.
 *
 * Each [StyleRange] is a `[start, end)` interval that applies one or more
 * of: bold, italic, underline, strikethrough, colorIndex. Multiple
 * ranges may cover the same character; the renderer unions them, with
 * the *last* range in the list winning per attribute.
 *
 * Ranges are stored *relative to the underlying plain text* of a
 * [ContentBlock.TextBlock]. When the user types or deletes text the
 * ranges are re-aligned via [StyleRanges.shiftOnTextChange].
 *
 * A range with `start == end` is treated as an "anchor" — it carries the
 * active style for the next character the user types. Anchors are stripped
 * before serialization via [StyleRanges.dropAnchors].
 *
 * V5.0: `strikethrough` was added. For backwards compatibility the
 * serialised form keeps the old layout — strikethrough is encoded as an
 * extra field `s` after `b|i|u` (e.g. `b|i|u|sN`). Older builds will
 * ignore the trailing token, newer builds will fail to decode it.
 */
@Immutable
data class StyleRange(
    val start: Int,
    val end: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    /** 0 = auto (onSurface), 1 = red, 2 = green, 3 = blue, 4 = purple, 5 = orange */
    val colorIndex: Int = 0
) {
    init {
        require(start >= 0) { "StyleRange start ($start) must be >= 0" }
        require(end >= start) { "StyleRange end ($end) must be >= start ($start)" }
    }

    /** True if any visible style attribute is set. */
    val isStyled: Boolean
        get() = bold || italic || underline || strikethrough || colorIndex != 0
}

/** Identifier for a single per-character style attribute. */
enum class StyleAttribute { BOLD, ITALIC, UNDERLINE, STRIKETHROUGH, COLOR }

/**
 * Snapshot of which of bold/italic/underline/strikethrough/color are
 * active at a given cursor position. Used to communicate "active style
 * for next typed character" between the editor and the ViewModel.
 */
data class StyleAttributeState(
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strikethrough: Boolean = false,
    val colorIndex: Int
) {
    /** True if any attribute is set. */
    val isActive: Boolean
        get() = bold || italic || underline || strikethrough || colorIndex != 0

    /** Flip a SINGLE attribute, returning a new state. */
    fun toggled(attribute: StyleAttribute, on: Boolean = true): StyleAttributeState =
        when (attribute) {
            StyleAttribute.BOLD -> copy(bold = on)
            StyleAttribute.ITALIC -> copy(italic = on)
            StyleAttribute.UNDERLINE -> copy(underline = on)
            StyleAttribute.STRIKETHROUGH -> copy(strikethrough = on)
            StyleAttribute.COLOR -> this // Color handled via setColorIndex
        }

    fun withColor(index: Int): StyleAttributeState = copy(colorIndex = index.coerceIn(0, 5))

    companion object {
        val EMPTY = StyleAttributeState(
            bold = false,
            italic = false,
            underline = false,
            strikethrough = false,
            colorIndex = 0
        )
    }
}

/* -----------------------------------------------------------------------
 * StyleRanges — utility object owning the algorithmic logic over lists of
 *               [StyleRange]. Stateless; all methods are pure.
 * --------------------------------------------------------------------- */
object StyleRanges {

    /** Resolve a 0..5 colorIndex into a Compose [Color]. 0 = unspecified (auto). */
    fun colorFor(index: Int): Color = when (index.coerceIn(0, 5)) {
        1 -> Color(0xFFD32F2F) // red
        2 -> Color(0xFF388E3C) // green
        3 -> Color(0xFF1976D2) // blue
        4 -> Color(0xFF7B1FA2) // purple
        5 -> Color(0xFFF57C00) // orange
        else -> Color.Unspecified
    }

    /**
     * Render the given plain [text] as an [AnnotatedString] with the styles
     * in [ranges] applied per character. For overlapping positions the
     * *last* range in the list takes precedence per attribute.
     */
    fun toAnnotatedString(text: String, ranges: List<StyleRange>): AnnotatedString {
        if (ranges.isEmpty()) return AnnotatedString(text)
        val len = text.length
        if (len == 0) return AnnotatedString("")
        return buildAnnotatedString {
            var i = 0
            while (i < len) {
                val matching = ranges.filter { it.start <= i && i < it.end }
                if (matching.isEmpty()) {
                    append(text[i])
                    i++
                } else {
                    val bold = matching.any { it.bold }
                    val italic = matching.any { it.italic }
                    val underline = matching.any { it.underline }
                    val strike = matching.any { it.strikethrough }
                    val colorIdx = matching.lastOrNull { it.colorIndex != 0 }?.colorIndex ?: 0
                    // Combine underline + line-through into a single
                    // TextDecoration when both are present.
                    val decorations = buildList {
                        if (underline) add(TextDecoration.Underline)
                        if (strike) add(TextDecoration.LineThrough)
                    }
                    val span = SpanStyle(
                        color = if (colorIdx == 0) Color.Unspecified else colorFor(colorIdx),
                        fontWeight = if (bold) FontWeight.Bold else null,
                        fontStyle = if (italic) FontStyle.Italic else null,
                        textDecoration = decorations.takeIf { it.isNotEmpty() }?.let { TextDecoration.combine(it) }
                    )
                    // Extend the styled run as long as the union of styles
                    // stays identical — cheaper than per-char spans.
                    var j = i + 1
                    while (j < len) {
                        val m2 = ranges.filter { it.start <= j && j < it.end }
                        if (m2.isEmpty()) break
                        val b2 = m2.any { it.bold }
                        val i2 = m2.any { it.italic }
                        val u2 = m2.any { it.underline }
                        val s2 = m2.any { it.strikethrough }
                        val c2 = m2.lastOrNull { it.colorIndex != 0 }?.colorIndex ?: 0
                        if (b2 != bold || i2 != italic || u2 != underline || s2 != strike || c2 != colorIdx) break
                        j++
                    }
                    withStyle(span) { append(text.substring(i, j)) }
                    i = j
                }
            }
        }
    }

    /* ---------------- Active-state queries ---------------- */

    /**
     * True iff every character in the selection has [attribute] applied.
     * Collapsed selection (cursor only) returns false.
     */
    fun isFullyStyled(
        ranges: List<StyleRange>,
        selection: TextRange,
        textLength: Int,
        attribute: StyleAttribute
    ): Boolean {
        if (selection.collapsed) return false
        val s = selection.min
        val e = selection.max
        if (s >= e || s < 0 || e > textLength) return false
        for (pos in s until e) {
            val r = ranges.find { it.start <= pos && pos < it.end } ?: return false
            when (attribute) {
                StyleAttribute.BOLD -> if (!r.bold) return false
                StyleAttribute.ITALIC -> if (!r.italic) return false
                StyleAttribute.UNDERLINE -> if (!r.underline) return false
                StyleAttribute.STRIKETHROUGH -> if (!r.strikethrough) return false
                StyleAttribute.COLOR -> if (r.colorIndex == 0) return false
            }
        }
        return true
    }

    /** Resolve the per-character attribute state at a single position. */
    fun attributeAt(ranges: List<StyleRange>, pos: Int): StyleAttributeState {
        // V5.1.6 — anchors (start == end) and regular ranges covering pos
        // both contribute to the resolved state. Without the anchor
        // branch, "armed bold" mode is invisible to a re-toggle and
        // the toolbar can't tell whether the user's caret is in a bold
        // context.
        val r = ranges.find {
            (it.start <= pos && pos < it.end) || (it.start == pos && it.end == pos)
        } ?: return StyleAttributeState.EMPTY
        return StyleAttributeState(
            bold = r.bold,
            italic = r.italic,
            underline = r.underline,
            strikethrough = r.strikethrough,
            colorIndex = r.colorIndex
        )
    }

    /** Are any characters in the selection bold? Returns null for collapsed selection. */
    fun isMixed(
        ranges: List<StyleRange>,
        selection: TextRange,
        textLength: Int,
        attribute: StyleAttribute
    ): Boolean? {
        if (selection.collapsed) return null
        val s = selection.min
        val e = selection.max
        if (s >= e || s < 0 || e > textLength) return null
        val total = (e - s)
        var onCount = 0
        for (pos in s until e) {
            val r = ranges.find { it.start <= pos && pos < it.end } ?: continue
            val on = when (attribute) {
                StyleAttribute.BOLD -> r.bold
                StyleAttribute.ITALIC -> r.italic
                StyleAttribute.UNDERLINE -> r.underline
                StyleAttribute.STRIKETHROUGH -> r.strikethrough
                StyleAttribute.COLOR -> r.colorIndex != 0
            }
            if (on) onCount++
        }
        return onCount in 1 until total
    }

    /* ---------------- Selection-aware toggles ---------------- */

    /**
     * Toggle [attribute] (BOLD / ITALIC / UNDERLINE) across the selection.
     * If the selection is collapsed the list is returned unchanged; the
     * caller should use [anchorAtCursor] to set the pending style for the
     * next typed character instead.
     *
     * If every character in the selection already has [attribute] the
     * attribute is removed; otherwise it is added to every position.
     */
    fun toggleAttribute(
        ranges: List<StyleRange>,
        selection: TextRange,
        attribute: StyleAttribute
    ): List<StyleRange> {
        if (selection.collapsed) return ranges
        val s = selection.min.coerceAtLeast(0)
        val e = selection.max
        if (s >= e) return ranges

        val alreadyOn = isFullyStyled(ranges, selection, e, attribute)
        // Per-position, what's the current value of `attribute`?
        val perPosOn = (s until e).map { pos ->
            val r = ranges.find { it.start <= pos && pos < it.end }
            when (attribute) {
                StyleAttribute.BOLD -> r?.bold == true
                StyleAttribute.ITALIC -> r?.italic == true
                StyleAttribute.UNDERLINE -> r?.underline == true
                StyleAttribute.STRIKETHROUGH -> r?.strikethrough == true
                StyleAttribute.COLOR -> false
            }
        }
        // If all on, we turn all off. Otherwise, we turn all on.
        val allOn = perPosOn.all { it }
        val newOnFlag = !allOn

        // Build replacement ranges: walk through the selection and emit a
        // new StyleRange for each run of identical "other attributes" with
        // the requested attribute flipped to `newOnFlag`.
        val replacement = mutableListOf<StyleRange>()
        var pos = s
        while (pos < e) {
            // Other-attribute snapshot at this position.
            val cover = ranges.find { it.start <= pos && pos < it.end }
            val bold = if (attribute == StyleAttribute.BOLD) newOnFlag else cover?.bold == true
            val italic = if (attribute == StyleAttribute.ITALIC) newOnFlag else cover?.italic == true
            val underline = if (attribute == StyleAttribute.UNDERLINE) newOnFlag else cover?.underline == true
            val strike = if (attribute == StyleAttribute.STRIKETHROUGH) newOnFlag else cover?.strikethrough == true
            val colorIndex = cover?.colorIndex ?: 0
            // Extend the run while other attributes stay identical.
            var next = pos + 1
            while (next < e) {
                val c2 = ranges.find { it.start <= next && next < it.end }
                val b2 = if (attribute == StyleAttribute.BOLD) newOnFlag else c2?.bold == true
                val i2 = if (attribute == StyleAttribute.ITALIC) newOnFlag else c2?.italic == true
                val u2 = if (attribute == StyleAttribute.UNDERLINE) newOnFlag else c2?.underline == true
                val s2 = if (attribute == StyleAttribute.STRIKETHROUGH) newOnFlag else c2?.strikethrough == true
                val color2 = c2?.colorIndex ?: 0
                if (b2 != bold || i2 != italic || u2 != underline || s2 != strike || color2 != colorIndex) break
                next++
            }
            if (bold || italic || underline || strike || colorIndex != 0) {
                replacement += StyleRange(pos, next, bold, italic, underline, strike, colorIndex)
            }
            pos = next
        }

        val outside = ranges.filterNot { it.end > s && it.start < e }
        return mergeRanges(outside + replacement)
    }

    /**
     * Set the [colorIndex] across the selection. If [colorIndex] == 0 the
     * selection has its color attribute removed.
     */
    fun setColor(
        ranges: List<StyleRange>,
        selection: TextRange,
        colorIndex: Int
    ): List<StyleRange> {
        if (selection.collapsed) return ranges
        val s = selection.min.coerceAtLeast(0)
        val e = selection.max
        if (s >= e) return ranges
        val ci = colorIndex.coerceIn(0, 5)

        val replacement = mutableListOf<StyleRange>()
        var pos = s
        while (pos < e) {
            val cover = ranges.find { it.start <= pos && pos < it.end }
            val bold = cover?.bold == true
            val italic = cover?.italic == true
            val underline = cover?.underline == true
            val strike = cover?.strikethrough == true
            var next = pos + 1
            while (next < e) {
                val c2 = ranges.find { it.start <= next && next < it.end }
                val b2 = c2?.bold == true
                val i2 = c2?.italic == true
                val u2 = c2?.underline == true
                val s2 = c2?.strikethrough == true
                if (b2 != bold || i2 != italic || u2 != underline || s2 != strike) break
                next++
            }
            if (bold || italic || underline || strike || ci != 0) {
                replacement += StyleRange(pos, next, bold, italic, underline, strike, ci)
            }
            pos = next
        }
        val outside = ranges.filterNot { it.end > s && it.start < e }
        return mergeRanges(outside + replacement)
    }

    /* ----------------- Pending-style anchor for cursor ----------------- */

    /**
     * Replace any anchor at [cursor] with a fresh anchor carrying [pending].
     * Returns the merged range list.
     */
    fun anchorAtCursor(
        ranges: List<StyleRange>,
        cursor: Int,
        pending: StyleAttributeState
    ): List<StyleRange> {
        if (!pending.isActive) return ranges.filterNot { it.start == cursor && it.end == cursor }
        val outside = ranges.filterNot { it.start == cursor && it.end == cursor }
        return mergeRanges(
            outside + StyleRange(
                start = cursor,
                end = cursor,
                bold = pending.bold,
                italic = pending.italic,
                underline = pending.underline,
                strikethrough = pending.strikethrough,
                colorIndex = pending.colorIndex
            )
        )
    }

    /**
     * Remove any anchor at [cursor] — used when the cursor moves to a new
     * location and the pending style should clear.
     */
    fun clearAnchorAt(ranges: List<StyleRange>, cursor: Int): List<StyleRange> =
        ranges.filterNot { it.start == cursor && it.end == cursor }

    /* ---------------- Text-change diffing ---------------- */

    /**
     * Realign the range list when the underlying text changes from
     * [oldText] to [newText]. Anchors at the cursor grow with inserted
     * characters; non-anchor ranges that overlap the changed region are
     * trimmed to the edges.
     */
    fun shiftOnTextChange(
        ranges: List<StyleRange>,
        oldText: String,
        newText: String
    ): List<StyleRange> {
        if (ranges.isEmpty()) return ranges
        if (oldText == newText) return ranges

        // Common prefix length.
        var prefix = 0
        val minLen = minOf(oldText.length, newText.length)
        while (prefix < minLen && oldText[prefix] == newText[prefix]) prefix++

        // Common suffix length (clamped so prefix+suffix <= min of lengths).
        var suffix = 0
        val maxSuffix = minOf(oldText.length - prefix, newText.length - prefix)
        while (suffix < maxSuffix &&
            oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
        ) suffix++

        val oldReplaceStart = prefix
        val oldReplaceEnd = oldText.length - suffix
        val newReplaceStart = prefix
        val delta = (newText.length - suffix) - (oldText.length - suffix)
        // The new replacement runs from `newReplaceStart` to `newReplaceStart + (oldReplaceEnd - oldReplaceStart) + delta`
        // which equals `newText.length - suffix`.

        val result = mutableListOf<StyleRange>()
        for (r in ranges) {
            // Position of r.start in the new text.
            val newStart = remapPos(r.start, oldReplaceStart, oldReplaceEnd, delta)
            val newEnd = remapPos(r.end, oldReplaceStart, oldReplaceEnd, delta)

            // Anchor at the start of the insertion: this is the V5.1.6
            // "type mode" sticky anchor. The typed characters become a
            // regular range with the armed style AND we re-place a fresh
            // anchor at the position immediately after the typed text so
            // the next keystroke will pick up the same armed style.
            val isInsertionAnchor = r.start == r.end &&
                r.start == oldReplaceStart &&
                delta > 0
            if (isInsertionAnchor) {
                val grownEnd = newStart + delta
                if (grownEnd > newStart && grownEnd <= newText.length) {
                    // The typed characters keep the armed style.
                    result += r.copy(start = newStart, end = grownEnd)
                }
                // V5.1.6 — re-arm so the next typed character also gets
                // the style. Without this the anchor grows into a
                // regular range on the first keystroke and subsequent
                // chars drop out of style (the bug "B only works for one
                // character" the user reported).
                if (grownEnd in 0..newText.length) {
                    result += r.copy(start = grownEnd, end = grownEnd)
                }
                continue
            }

            // Non-overlapping range: shift by delta if past the change.
            val overlaps = r.end > oldReplaceStart && r.start < oldReplaceEnd
            if (!overlaps) {
                val ns = if (r.start >= oldReplaceEnd) newStart else newStart
                val ne = if (r.end >= oldReplaceEnd) newEnd else newEnd
                if (ns < ne && ns >= 0 && ne <= newText.length) {
                    result += r.copy(start = ns, end = ne)
                }
                continue
            }

            // Overlapping non-anchor range: keep the part before the
            // change, keep the part after the change, drop the rest.
            val before = if (r.start < oldReplaceStart) {
                r.copy(start = r.start, end = oldReplaceStart)
            } else null
            val after = if (r.end > oldReplaceEnd) {
                // After part shifts by delta; its length stays the same.
                r.copy(
                    start = oldReplaceEnd + delta,
                    end = (r.end - oldReplaceEnd) + (oldReplaceEnd + delta)
                )
            } else null
            if (before != null) result += before
            if (after != null) result += after
        }
        return mergeRanges(result)
    }

    /**
     * Drop anchors (start == end). Anchors are in-memory "active style"
     * markers that should not be persisted.
     */
    fun dropAnchors(ranges: List<StyleRange>): List<StyleRange> =
        ranges.filterNot { it.start == it.end }

    /* ---------------- Range merging ---------------- */

    /**
     * Merge ranges so adjacent ranges with identical flags combine. Ranges
     * with no styling are dropped. Overlapping ranges with different flags
     * are preserved (the renderer handles per-character union).
     *
     * V5.1.6 — anchors (start == end) are kept as separate entries
     * even when their position is adjacent to a preceding range with the
     * same flags. Without this guard the merge would consume the freshly
     * re-armed anchor emitted by [shiftOnTextChange] and break the
     * "B stays on for as long as I keep typing" UX.
     */
    fun mergeRanges(ranges: List<StyleRange>): List<StyleRange> {
        val styled = ranges.filter { it.isStyled }
        if (styled.isEmpty()) return emptyList()
        val sorted = styled.sortedBy { it.start }
        val merged = mutableListOf<StyleRange>()
        for (r in sorted) {
            val isAnchor = r.start == r.end
            val last = merged.lastOrNull()
            // V5.1.6 — anchors (start == end) are kept as separate
            // entries even when adjacent to a range with identical
            // attributes, otherwise [shiftOnTextChange]'s "re-arm"
            // step collapses and the sticky-typing behaviour breaks.
            val canMerge = !isAnchor &&
                last != null &&
                last.end >= r.start &&
                last.bold == r.bold &&
                last.italic == r.italic &&
                last.underline == r.underline &&
                last.strikethrough == r.strikethrough &&
                last.colorIndex == r.colorIndex
            if (canMerge) {
                merged[merged.lastIndex] = last.copy(end = maxOf(last.end, r.end))
            } else {
                merged += r
            }
        }
        return merged
    }

    /** V5.1.6 — true if the receiver is an anchor (start == end). */
    private val StyleRange.isAnchorEquivalent: Boolean
        get() = start == end

    // Reference kept for clarity; not actually invoked anywhere right
    // now since [mergeRanges] only needs to know whether `r` is an
    // anchor (which it checks inline with `r.start == r.end`).

    /* ---------------- Private helpers ---------------- */

    private fun remapPos(pos: Int, oldStart: Int, oldEnd: Int, delta: Int): Int =
        when {
            pos <= oldStart -> pos
            pos >= oldEnd -> pos + delta
            else -> oldStart + (pos - oldStart)
        }
}