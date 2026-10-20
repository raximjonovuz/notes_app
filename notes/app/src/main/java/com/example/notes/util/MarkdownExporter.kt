package com.example.notes.util

import com.example.notes.ui.edit.ContentBlock

/**
 * V5.0 — convert a serialized note content string (or pre-parsed blocks)
 * to a Markdown document. Inverse of `markdownToBlocks` which is
 * intentionally not implemented yet — Markdown *import* would need a
 * full block parser and is scheduled for V5.1.
 *
 * The export is intentionally lossy:
 *   - Bold/italic/underline/strikethrough are emitted as `**`, `_`,
 *     `<u>`, `~~` inline markdown (most renderers support these).
 *   - Per-character color is dropped (Markdown has no equivalent; the
 *     colour is exported as a single CSS variable on a wrapping span
 *     so a styled preview still gets the visual cue).
 *   - Lists are emitted as `-` (bullet) or `1.` (numbered) prefixes; one
 *     block = one list.
 *   - Images become `![alt](uri)` — alt is empty because we don't store
 *     alt text.
 *   - Background color is dropped.
 */
object MarkdownExporter {

    /** Render a serialized [content] string as a Markdown document. */
    fun export(content: String): String {
        val blocks = com.example.notes.ui.edit.parseBlocksFromContent(content)
        return exportBlocks(blocks)
    }

    fun exportBlocks(blocks: List<ContentBlock>): String {
        val sb = StringBuilder()
        for (block in blocks) {
            when (block) {
                is ContentBlock.TextBlock -> {
                    val body = emitBlockBody(block)
                    if (body.isBlank() && sb.isNotEmpty() && sb.last() != '\n') {
                        sb.append('\n')
                    } else if (body.isNotBlank()) {
                        sb.append(body).append("\n\n")
                    }
                }
                is ContentBlock.ImageBlock -> {
                    sb.append("![](").append(block.uri).append(")\n\n")
                }
            }
        }
        return sb.toString().trimEnd()
    }

    /**
     * Render a TextBlock's plain text with inline style markers. We
     * walk the text once, batching adjacent characters that share the
     * exact same style tuple so the output uses a single `<tag>…</tag>`
     * pair instead of one tag per character. Markdown's special
     * characters (`*`, `_`, `#`, `[`, `]`, `` ` ``, `\\`) are escaped
     * in the inline text so the round-trip is readable rather than
     * silently turning the text into bold/italic/headings.
     */
    private fun emitBlockBody(block: ContentBlock.TextBlock): String {
        val plain = block.text
        if (block.styleRanges.isEmpty() || plain.isEmpty()) {
            // No inline styles — just prepend list markers if needed.
            return prefixLines(block, plain)
        }

        val len = plain.length
        val out = StringBuilder()
        var pos = 0
        while (pos < len) {
            val cover = block.styleRanges.firstOrNull { it.start <= pos && pos < it.end }
            val bold = cover?.bold == true
            val italic = cover?.italic == true
            val underline = cover?.underline == true
            val strike = cover?.strikethrough == true
            val colorIndex = cover?.colorIndex ?: 0

            // Extend the run while the style tuple stays identical so we
            // emit one tag pair (not one per character).
            var next = pos + 1
            while (next < len) {
                val c2 = block.styleRanges.firstOrNull { it.start <= next && next < it.end }
                if (c2?.bold != bold ||
                    c2?.italic != italic ||
                    c2?.underline != underline ||
                    c2?.strikethrough != strike ||
                    (c2?.colorIndex ?: 0) != colorIndex
                ) break
                next++
            }

            appendOpenTags(out, bold, italic, underline, strike, colorIndex)
            for (k in pos until next) {
                out.append(escapeMarkdownChar(plain[k]))
            }
            appendCloseTags(out, bold, italic, underline, strike, colorIndex)

            pos = next
        }
        return prefixLines(block, out.toString())
    }

    private fun appendOpenTags(
        out: StringBuilder,
        bold: Boolean,
        italic: Boolean,
        underline: Boolean,
        strike: Boolean,
        colorIndex: Int
    ) {
        if (bold) out.append("**")
        if (italic) out.append("_")
        if (underline) out.append("<u>")
        if (strike) out.append("~~")
        if (colorIndex != 0) {
            out.append("<span style=\"color:").append(colorHex(colorIndex)).append("\">")
        }
    }

    private fun appendCloseTags(
        out: StringBuilder,
        bold: Boolean,
        italic: Boolean,
        underline: Boolean,
        strike: Boolean,
        colorIndex: Int
    ) {
        if (colorIndex != 0) out.append("</span>")
        if (strike) out.append("~~")
        if (underline) out.append("</u>")
        if (italic) out.append("_")
        if (bold) out.append("**")
    }

    /**
     * Resolve a `colorIndex` (0..5, see [com.example.notes.ui.edit.StyleRanges.colorFor])
     * to a CSS hex string. Returns `"#000000"` for the unspecified index
     * — we still emit a span so the colour cue survives the export.
     */
    private fun colorHex(colorIndex: Int): String {
        val c = com.example.notes.ui.edit.StyleRanges.colorFor(colorIndex)
        val argb = (c.value and 0xFFFFFFFFu).toLong()
        return "#%06x".format((argb shr 8) and 0xFFFFFF)
    }

    /**
     * Escape a single inline character so the resulting Markdown is
     * unambiguous when the user types reserved characters. We backslash
     * only the characters that the CommonMark inline parser treats as
     * special; everything else passes through unchanged.
     */
    private fun escapeMarkdownChar(ch: Char): String = when (ch) {
        '\\' -> "\\\\"
        '`' -> "\\`"
        '*' -> "\\*"
        '_' -> "\\_"
        '{' -> "\\{"
        '}' -> "\\}"
        '[' -> "\\["
        ']' -> "\\]"
        '(' -> "\\("
        ')' -> "\\)"
        '#' -> "\\#"
        '+' -> "\\+"
        '-' -> "\\-"
        '.' -> "\\."
        '!' -> "\\!"
        '|' -> "\\|"
        else -> ch.toString()
    }

    private fun prefixLines(block: ContentBlock.TextBlock, raw: String): String {
        val lines = raw.split('\n')
        return when {
            block.isChecklist -> lines.withIndex().joinToString("\n") { (_, line) ->
                val checkbox = if (line.startsWith("- [x] ") || line.startsWith("- [X] ")) "- [x] " else "- [ ] "
                val text = line.removePrefix("- [x] ").removePrefix("- [X] ").removePrefix("- [ ] ")
                "$checkbox$text"
            }
            block.listType == 1 -> lines.withIndex().joinToString("\n") { (i, line) -> "- $line" }
            block.listType == 2 -> lines.withIndex().joinToString("\n") { (i, line) -> "${i + 1}. $line" }
            else -> raw
        }
    }
}