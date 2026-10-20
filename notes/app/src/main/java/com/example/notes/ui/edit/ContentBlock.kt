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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
 *
 * B9 — on-disk format moved to JSON v2. Each note's content is
 *   `{"v":2,"blocks":[{...},{...}]}` so multi-line TextBlocks survive
 *   a save/reopen round-trip and per-line fields (fontSizesByLine,
 *   listTypesByLine) round-trip too. Legacy `|||IMG:...|||` notes are
 *   still parsed by the fallback path; they are re-saved in the v2
 *   format on the next save.
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
 * B9 — recognises the v2 JSON envelope first, then falls back to the
 * legacy `|||IMG:...|||` line-prefixed format. Field-by-field:
 *   - v2 JSON:    {"v":2,"blocks":[{...},{...}]}  (multi-line safe)
 *   - V2.6 header: <s|a|l|k>S<encoded ranges>body
 *   - V2.5 header: <b|i|u|s|a|c|l|k>body  (b/i/u/c are block-level flags
 *     used by V2.5 — we honor them by converting them into a single
 *     StyleRange covering the entire block)
 *   - Plain text: body with no header
 */
fun parseBlocksFromContent(raw: String): List<ContentBlock> {
    if (raw.isEmpty()) return emptyList()
    // B9 — JSON v2 envelope detection. Trimming the leading whitespace
    // is intentional: a future migration that prepends a BOM or
    // accidentally injects a leading \n shouldn't break the detection.
    val trimmed = raw.trimStart()
    if (trimmed.startsWith("{\"v\":2") || trimmed.startsWith("{\"v\": 2")) {
        return parseBlocksFromJsonV2(trimmed)
    }
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
 *
 * B9 — always emits the v2 JSON envelope so multi-line TextBlocks,
 * `fontSizesByLine`, and `listTypesByLine` round-trip correctly. The
 * legacy `<flags>S<ranges>body` / `|||IMG:...|||` parser stays in
 * place so old notes still open, but the next save will overwrite
 * their content in the new format.
 */
fun serializeBlocksToContent(blocks: List<ContentBlock>): String {
    if (blocks.isEmpty()) return ""
    return serializeBlocksToJsonV2(blocks)
}

/* -----------------------------------------------------------------------
 * v2 JSON serialization
 *
 * Format:
 *   {"v":2,"blocks":[
 *     {"t":"text","text":"...","fontSize":1,"align":0,"list":0,
 *      "check":false,"ranges":[...],"fontSizesByLine":{...},
 *      "listTypesByLine":{...}},
 *     {"t":"img","uri":"..."}
 *   ]}
 *
 * Numeric keys (fontSize, align, list) are kept short to minimise
 * the on-disk size — these payloads go through the backend's 1 MB
 * `content` cap.
 * --------------------------------------------------------------------- */

@Serializable
private data class JsonBlockText(
    val t: String = "text",
    val text: String = "",
    val fontSize: Int = 1,
    val align: Int = 0,
    val list: Int = 0,
    val check: Boolean = false,
    val ranges: List<StyleRange> = emptyList(),
    val fontSizesByLine: Map<Int, Int> = emptyMap(),
    val listTypesByLine: Map<Int, Int> = emptyMap()
)

@Serializable
private data class JsonBlockImage(
    val t: String = "img",
    val uri: String
)

private val v2Json: Json = Json {
    // V2 format only has known fields; if a newer build adds one we
    // don't want an older build to crash, we want a silent skip.
    ignoreUnknownKeys = true
    // Encode booleans as `true`/`false` (not `0`/`1`).
    encodeDefaults = true
}

private fun serializeBlocksToJsonV2(blocks: List<ContentBlock>): String {
    val out = buildJsonArray {
        for (b in blocks) {
            when (b) {
                is ContentBlock.TextBlock -> add(
                    v2Json.encodeToJsonElement(
                        JsonBlockText.serializer(),
                        JsonBlockText(
                            text = b.text,
                            fontSize = b.fontSize,
                            align = b.textAlign,
                            list = b.listType,
                            check = b.isChecklist,
                            ranges = b.styleRanges,
                            fontSizesByLine = b.fontSizesByLine,
                            listTypesByLine = b.listTypesByLine
                        )
                    )
                )
                is ContentBlock.ImageBlock -> add(
                    v2Json.encodeToJsonElement(
                        JsonBlockImage.serializer(),
                        JsonBlockImage(uri = b.uri)
                    )
                )
            }
        }
    }
    return buildJsonObject {
        put("v", 2)
        put("blocks", out)
    }.toString()
}

private fun parseBlocksFromJsonV2(raw: String): List<ContentBlock> {
    return try {
        val root = Json.parseToJsonElement(raw).jsonObject
        val arr = root["blocks"]?.jsonArray ?: return emptyList()
        val out = mutableListOf<ContentBlock>()
        for (el in arr) {
            val obj = el.jsonObject
            val t = obj["t"]?.jsonPrimitive?.contentOrNull
            when (t) {
                "text" -> {
                    val text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val fontSize = obj["fontSize"]?.jsonPrimitive?.intOrNull ?: 1
                    val align = obj["align"]?.jsonPrimitive?.intOrNull ?: 0
                    val list = obj["list"]?.jsonPrimitive?.intOrNull ?: 0
                    val check = obj["check"]?.jsonPrimitive?.boolean ?: false
                    val ranges = obj["ranges"]?.jsonArray?.mapNotNull {
                        runCatching { v2Json.decodeFromJsonElement(StyleRange.serializer(), it) }
                            .getOrNull()
                    } ?: emptyList()
                    val fontSizesByLine = parseIntMap(obj["fontSizesByLine"])
                    val listTypesByLine = parseIntMap(obj["listTypesByLine"])
                    out += ContentBlock.TextBlock(
                        text = text,
                        styleRanges = ranges,
                        fontSize = fontSize.coerceIn(0, TEXT_SIZE_VALUES.lastIndex),
                        textAlign = align.coerceIn(0, 2),
                        listType = list.coerceIn(0, 2),
                        isChecklist = check,
                        fontSizesByLine = fontSizesByLine,
                        listTypesByLine = listTypesByLine
                    )
                }
                "img" -> {
                    val uri = obj["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (uri.isNotEmpty()) {
                        out += ContentBlock.ImageBlock(uri = uri)
                    }
                }
                else -> {
                    // Unknown block type — skip rather than crash.
                }
            }
        }
        out
    } catch (t: Throwable) {
        // Malformed JSON — fall back to legacy parser. The legacy
        // parser will produce at least the text/IMG tokens it can
        // recognise; anything it can't will be lost, but a parse
        // error shouldn't lose the whole note.
        parseBlocksFromLegacy(raw)
    }
}

private fun parseIntMap(el: kotlinx.serialization.json.JsonElement?): Map<Int, Int> {
    if (el == null) return emptyMap()
    val obj = el as? JsonObject ?: return emptyMap()
    val out = mutableMapOf<Int, Int>()
    for ((k, v) in obj) {
        val ki = k.toIntOrNull() ?: continue
        val vi = (v as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: continue
        out[ki] = vi
    }
    return out
}

/** Fallback when JSON v2 parsing fails — re-uses the legacy
 *  `<flags>S<ranges>body` / `|||IMG:...|||` parser. Public so the
 *  test suite can hit it directly. */
fun parseBlocksFromLegacy(raw: String): List<ContentBlock> {
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