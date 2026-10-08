package com.example.notes.ui.edit

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import java.util.UUID

/**
 * A piece of a note. A note is a list of blocks rendered in order, like
 * Telegram / WhatsApp attachments:
 *
 *   [TextBlock("first..."), ImageBlock("content://..."), TextBlock("after image...")]
 *
 * V2.6 — per-character rich-text:
 *
 *   Each [TextBlock] carries:
 *     - text:           the underlying plain String (no style tokens)
 *     - selection:      current caret / selection range in the field
 *     - styleRanges:    list of [StyleRange] covering styled spans
 *     - fontSize:       index into [TEXT_SIZE_VALUES] (0..4)
 *     - textAlign:      0=left, 1=center, 2=right
 *     - listType:       0=none, 1=bullet, 2=numbered
 *     - isChecklist:    render lines as checkable items
 *
 *   Per-character bold / italic / underline / color are stored in
 *   [styleRanges]; the previous V2.5 block-level flags are gone.
 */
sealed class ContentBlock {
    abstract val id: String

    data class TextBlock(
        override val id: String = UUID.randomUUID().toString(),
        val text: String = "",
        val selection: TextRange = TextRange.Zero,
        val styleRanges: List<StyleRange> = emptyList(),
        /** 0 = Small, 1 = Medium, 2 = Large, 3 = XLarge, 4 = XXLarge */
        val fontSize: Int = 1,
        /** 0 = left, 1 = center, 2 = right */
        val textAlign: Int = 0,
        /** 0 = none, 1 = bullet, 2 = numbered — the BLOCK-LEVEL default. */
        val listType: Int = 0,
        /** When true, lines are rendered as checklist items. */
        val isChecklist: Boolean = false,
        // V5.1.7 — per-line overrides so the toolbar can target "the
        // relevant part" (the line containing the caret) instead of
        // mutating the whole block. Maps are keyed by line index; a
        // missing entry means "fall back to the block default above".
        val fontSizesByLine: Map<Int, Int> = emptyMap(),
        val listTypesByLine: Map<Int, Int> = emptyMap()
    ) : ContentBlock() {
        /** Compose [TextFieldValue] view of this block. */
        val value: TextFieldValue get() = TextFieldValue(text, selection)

        /** Effective font size for line index [lineIdx] (fallback to the
         *  block-level default when no per-line override exists). */
        fun fontSizeForLine(lineIdx: Int): Int =
            fontSizesByLine[lineIdx] ?: fontSize

        /** Effective list type for line index [lineIdx]. */
        fun listTypeForLine(lineIdx: Int): Int =
            listTypesByLine[lineIdx] ?: listType
    }

    data class ImageBlock(
        override val id: String = UUID.randomUUID().toString(),
        val uri: String
    ) : ContentBlock()
}

/* -----------------------------------------------------------------------
 * Style helpers (used by NoteEditScreen to render a TextBlock)
 * --------------------------------------------------------------------- */

/** Spacing values (sp) for the 5 text sizes. */
val TEXT_SIZE_VALUES = listOf(14, 16, 18, 20, 22)

/** Label shown on the toolbar's "current size" button. */
fun textSizeLabelFor(index: Int): String = when (index.coerceIn(0, TEXT_SIZE_VALUES.lastIndex)) {
    0 -> "14"
    1 -> "16"
    2 -> "18"
    3 -> "20"
    4 -> "22"
    else -> "16"
}

/** Convert a 0..4 index into a TextUnit (sp). */
fun fontSizeSpFor(index: Int): TextUnit =
    TextUnit(
        TEXT_SIZE_VALUES[index.coerceIn(0, TEXT_SIZE_VALUES.lastIndex)].toFloat(),
        TextUnitType.Sp
    )

/** Convert a 0..2 textAlign index into Compose [TextAlign]. */
fun textAlignFor(index: Int): TextAlign = when (index.coerceIn(0, 2)) {
    1 -> TextAlign.Center
    2 -> TextAlign.Right
    else -> TextAlign.Left
}

/** Resolve a 0..5 colorIndex into a Compose [Color]. 0 = unspecified (auto). */
fun textColorFor(index: Int): Color = StyleRanges.colorFor(index)

/** Render the resolved Color for [index] against the active MaterialTheme. */
@Composable
fun resolvedTextColor(index: Int): Color {
    if (index == 0) return MaterialTheme.colorScheme.onSurface
    return textColorFor(index)
}

/**
 * Compose the per-block TextStyle for the bits that aren't per-character:
 * size, alignment, and the *fallback* color when no range is active.
 * Per-character color/bold/italic/underline is layered on top via the
 * BasicTextField's AnnotatedString.
 */
@Composable
fun textStyleFor(block: ContentBlock.TextBlock): TextStyle {
    return TextStyle(
        fontSize = fontSizeSpFor(block.fontSize),
        textAlign = textAlignFor(block.textAlign),
        color = resolvedTextColor(0)
    )
}

/** Non-composable variant of [textStyleFor] for places without MaterialTheme. */
fun textStyleForStatic(
    block: ContentBlock.TextBlock,
    fallbackColor: Color = Color.Unspecified
): TextStyle {
    return TextStyle(
        fontSize = fontSizeSpFor(block.fontSize),
        textAlign = textAlignFor(block.textAlign),
        color = fallbackColor
    )
}

/* -----------------------------------------------------------------------
 * Serialization helpers
 *
 * V2.6 layout:
 *   Each TextBlock is serialized as:
 *     <s|a|l|k>S<range-payload>text
 *   where the prefix `s|a|l|k` is fixed-order 1-digit-per-flag:
 *     s = fontSize (0..4)
 *     a = textAlign (0..2)
 *     l = listType  (0..2)
 *     k = checklist (0|1)
 *
 *   `<` opens the header; an uppercase `S` marks the start of the encoded
 *   style-range payload (a compact, escaped string, terminated by `>`);
 *   the body (text) follows.
 *
 *   The `<...>body` shape is fully backwards-compatible with V2.5 —
 *   legacy headers (`<b|i|u|s|a|c|l|k>`) are still parseable.
 * --------------------------------------------------------------------- */

internal const val IMG_TOKEN_PREFIX = "\n|||IMG:"
internal const val IMG_TOKEN_SUFFIX = "|||\n"

/** Marker that begins the V2.6 style-range payload inside a header. */
internal const val STYLE_PAYLOAD_MARKER = "S"
internal const val TEXT_BLOCK_HEADER_PREFIX = "<"
internal const val TEXT_BLOCK_HEADER_SUFFIX = ">"

/**
 * Parse a serialized content String back into a list of ContentBlocks.
 * Supports V2.6 (with `S<range-payload>` after the per-block header) and
 * V2.5 (`<b|i|u|s|a|c|l|k>` headers) and pure text. Field-by-field:
 *   - V2.6 header: <s|a|l|k>S<encoded ranges>body
 *   - V2.5 header: <b|i|u|s|a|c|l|k>body  (b/i/u/c are block-level flags
 *     used by V2.5 — we honor them by converting them into a single
 *     StyleRange covering the entire block)
 *   - Plain text: body with no header
 */
fun parseBlocksFromContent(raw: String): List<ContentBlock> {
    if (raw.isEmpty()) return emptyList()
    val blocks = mutableListOf<ContentBlock>()

    var i = 0
    val len = raw.length
    val textBuf = StringBuilder()

    fun flushTextBuffer() {
        val chunk = textBuf.toString()
        textBuf.setLength(0)
        if (chunk.isEmpty()) return
        val lines = chunk.split('\n')
        var plainAccumulator = StringBuilder()
        fun flushPlain() {
            val text = plainAccumulator.toString()
            plainAccumulator.setLength(0)
            if (text.isNotEmpty()) {
                blocks += ContentBlock.TextBlock(text = text)
            }
        }
        for (line in lines) {
            if (line.isEmpty()) {
                flushPlain()
                blocks += ContentBlock.TextBlock(text = "")
                continue
            }
            val parsed = parseTextBlockHeader(line)
            if (parsed != null) {
                flushPlain()
                blocks += parsed
            } else {
                if (plainAccumulator.isNotEmpty()) plainAccumulator.append('\n')
                plainAccumulator.append(line)
            }
        }
        flushPlain()
    }

    while (i < len) {
        val imgIdx = raw.indexOf(IMG_TOKEN_PREFIX, i)
        if (imgIdx < 0) {
            textBuf.append(raw.substring(i))
            i = len
            break
        }
        if (imgIdx > i) {
            textBuf.append(raw.substring(i, imgIdx))
        }
        flushTextBuffer()

        val uriStart = imgIdx + IMG_TOKEN_PREFIX.length
        val endIdx = raw.indexOf(IMG_TOKEN_SUFFIX, uriStart)
        if (endIdx < 0) {
            textBuf.append(raw.substring(imgIdx))
            i = len
            break
        }
        val uri = raw.substring(uriStart, endIdx)
        blocks += ContentBlock.ImageBlock(uri = uri)
        i = endIdx + IMG_TOKEN_SUFFIX.length
    }
    flushTextBuffer()
    return blocks
}

/**
 * Try to parse a `<...>...` header at the start of [line]. Supports
 * V2.6 (`<s|a|l|k>S<encoded>`) and V2.5 (`<b|i|u|s|a|c|l|k>`) formats.
 * Returns the parsed [ContentBlock.TextBlock] or null if [line] does not
 * have a header.
 */
private fun parseTextBlockHeader(line: String): ContentBlock.TextBlock? {
    if (line.length < 3) return null
    if (line[0] != '<') return null
    val closeIdx = line.indexOf('>')
    if (closeIdx <= 1) return null
    val flags = line.substring(1, closeIdx)
    val body = line.substring(closeIdx + 1)

    // Detect format by length / content.
    return when {
        // V2.6: <s|a|l|k>S<encoded ranges>  — 4 single-digit flags + 'S' + ranges
        flags.length >= 5 && flags[4] == STYLE_PAYLOAD_MARKER[0] -> {
            val s = flags[0].digitToIntOrNull() ?: return null
            val a = flags[1].digitToIntOrNull() ?: return null
            val l = flags[2].digitToIntOrNull() ?: return null
            val k = flags[3].digitToIntOrNull() ?: return null
            val ranges = StyleRangeSerializer.decode(flags.substring(5))
            ContentBlock.TextBlock(
                text = body,
                styleRanges = ranges,
                fontSize = s.coerceIn(0, TEXT_SIZE_VALUES.lastIndex),
                textAlign = a.coerceIn(0, 2),
                listType = l.coerceIn(0, 2),
                isChecklist = k != 0
            )
        }
        // V2.5: <b|i|u|s|a|c|l|k>  — exactly 8 single-digit flags
        flags.length == 8 && flags.all { it.isDigit() } -> {
            val b = flags[0].digitToIntOrNull() ?: return null
            val it = flags[1].digitToIntOrNull() ?: return null
            val u = flags[2].digitToIntOrNull() ?: return null
            val s = flags[3].digitToIntOrNull() ?: return null
            val a = flags[4].digitToIntOrNull() ?: return null
            val c = flags[5].digitToIntOrNull() ?: return null
            val l = flags[6].digitToIntOrNull() ?: return null
            val k = flags[7].digitToIntOrNull() ?: return null
            // Build a single StyleRange covering the whole block when any
            // legacy style flag was on, so visual fidelity to old notes
            // is preserved.
            val ranges = if (b != 0 || it != 0 || u != 0 || c != 0) {
                listOf(
                    StyleRange(
                        start = 0,
                        end = body.length.coerceAtLeast(0),
                        bold = b != 0,
                        italic = it != 0,
                        underline = u != 0,
                        colorIndex = c.coerceIn(0, 5)
                    )
                )
            } else emptyList()
            ContentBlock.TextBlock(
                text = body,
                styleRanges = ranges,
                fontSize = s.coerceIn(0, TEXT_SIZE_VALUES.lastIndex),
                textAlign = a.coerceIn(0, 2),
                listType = l.coerceIn(0, 2),
                isChecklist = k != 0
            )
        }
        else -> null
    }
}

/** Serialize a single TextBlock to the V2.6 on-disk format. */
private fun serializeTextBlock(block: ContentBlock.TextBlock): String {
    val s = block.fontSize.coerceIn(0, TEXT_SIZE_VALUES.lastIndex)
    val a = block.textAlign.coerceIn(0, 2)
    val l = block.listType.coerceIn(0, 2)
    val k = if (block.isChecklist) 1 else 0
    val encodedRanges = StyleRangeSerializer.encode(block.styleRanges)
    return "${TEXT_BLOCK_HEADER_PREFIX}${s}${a}${l}${k}${STYLE_PAYLOAD_MARKER}${encodedRanges}${TEXT_BLOCK_HEADER_SUFFIX}${block.text}"
}

/**
 * Serialize a list of blocks back into a single content String for Room.
 */
fun serializeBlocksToContent(blocks: List<ContentBlock>): String {
    if (blocks.isEmpty()) return ""
    val sb = StringBuilder()
    blocks.forEach { block ->
        when (block) {
            is ContentBlock.TextBlock -> {
                if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                sb.append(serializeTextBlock(block))
            }
            is ContentBlock.ImageBlock -> {
                sb.append(IMG_TOKEN_PREFIX).append(block.uri).append(IMG_TOKEN_SUFFIX)
            }
        }
    }
    return sb.toString()
}

/** Pure-text view of blocks (for backward-compat / search / preview). */
fun blocksToPlainText(blocks: List<ContentBlock>): String {
    return blocks
        .filterIsInstance<ContentBlock.TextBlock>()
        .joinToString("\n") { it.text }
}

/* -----------------------------------------------------------------------
 * StyleRangeSerializer — encode/decode List<StyleRange> to a compact
 *                        payload string safe to embed inside a `<...>`
 *                        header.
 * --------------------------------------------------------------------- */
object StyleRangeSerializer {
    /** Field separator between ranges. Avoids `,` so it can't clash with body text. */
    private const val RANGE_SEP = ";"
    /** Field separator within a single range: `start-end:b|i|u|cc` */
    private const val FIELD_SEP = "-"
    private const val FLAG_SEP = "|"

    /**
     * Encode a list of [StyleRange] into a compact payload. Anchors
     * (start == end) are encoded as `start-:b|i|u|cN`. The payload is
     * sorted by `start` for stable on-disk form.
     *
     * Output format: `s-e:b|i|u|cN;s-e:b|i|u|cN;...`
     */
    fun encode(ranges: List<StyleRange>): String {
        if (ranges.isEmpty()) return ""
        val sorted = ranges.sortedBy { it.start }
        val sb = StringBuilder()
        for (r in sorted) {
            if (sb.isNotEmpty()) sb.append(RANGE_SEP)
            sb.append(r.start).append(FIELD_SEP).append(r.end)
            sb.append(':')
            sb.append(if (r.bold) 'b' else '-')
            sb.append(FLAG_SEP)
            sb.append(if (r.italic) 'i' else '-')
            sb.append(FLAG_SEP)
            sb.append(if (r.underline) 'u' else '-')
            sb.append(FLAG_SEP)
            sb.append('c').append(r.colorIndex.coerceIn(0, 5))
        }
        return sb.toString()
    }

    /**
     * Decode the payload produced by [encode]. Returns an empty list for
     * empty / malformed input. Malformed ranges are skipped.
     */
    fun decode(payload: String): List<StyleRange> {
        if (payload.isBlank()) return emptyList()
        val out = mutableListOf<StyleRange>()
        for (segment in payload.split(RANGE_SEP)) {
            if (segment.isBlank()) continue
            // segment: s-e:b|i|u|cN
            val parts = segment.split(':')
            if (parts.size != 5) continue
            val rangeParts = parts[0].split(FIELD_SEP)
            if (rangeParts.size != 2) continue
            val start = rangeParts[0].toIntOrNull() ?: continue
            val end = rangeParts[1].toIntOrNull() ?: continue
            if (start < 0 || end < start) continue
            val b = parts[1].length == 1 && parts[1][0] == 'b'
            val it = parts[2].length == 1 && parts[2][0] == 'i'
            val u = parts[3].length == 1 && parts[3][0] == 'u'
            val c = if (parts[4].length >= 2 && parts[4][0] == 'c') {
                parts[4].substring(1).toIntOrNull()?.coerceIn(0, 5) ?: 0
            } else 0
            if (start == end && !b && !it && !u && c == 0) continue // skip empty anchors
            out += StyleRange(start, end, b, it, u, false, c)
        }
        return out
    }
}