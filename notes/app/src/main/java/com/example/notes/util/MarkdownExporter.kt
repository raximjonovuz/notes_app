package com.example.notes.util

import com.example.notes.ui.edit.ContentBlock
import com.example.notes.ui.edit.blocksToPlainText

/**
 * V5.0 — convert a serialized note content string (or pre-parsed blocks)
 * to a Markdown document. Inverse of [markdownToBlocks] which is
 * intentionally not implemented yet — Markdown *import* would need a
 * full block parser and is scheduled for V5.1.
 *
 * The export is intentionally lossy:
 *   - Bold/italic/underline/strikethrough are emitted as `**`, `_`,
 *     `<u>`, `~~` inline markdown (most renderers support these).
 *   - Per-character color/anchors are dropped (no equivalent in plain
 *     Markdown).
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
     * intentionally don't try to merge per-character styles with
     * surrounding plain text — each character either carries the style
     * or doesn't, and we emit `<style>…</style>` HTML tags for anything
     * Markdown can't express inline.
     */
    private fun emitBlockBody(block: ContentBlock.TextBlock): String {
        val plain = block.text
        if (block.styleRanges.isEmpty() || plain.isEmpty()) {
            // No inline styles — just prepend list markers if needed.
            return prefixLines(block, plain)
        }

        // Build a per-character style string.
        val len = plain.length
        val out = StringBuilder()
        for (i in 0 until len) {
            val ch = plain[i]
            val cover = block.styleRanges.firstOrNull { it.start <= i && i < it.end }
            val bold = cover?.bold == true
            val italic = cover?.italic == true
            val underline = cover?.underline == true
            val strike = cover?.strikethrough == true
            val color = cover?.colorIndex ?: 0

            // Open markdown tags. We close them per character which is
            // wasteful but keeps the logic simple — exported notes are
            // typically read once and then discarded.
            if (bold) out.append("**")
            if (italic) out.append("_")
            if (underline) out.append("<u>")
            if (strike) out.append("~~")
            if (color != 0) out.append("<span style=\"color:").append(color).append("\">")
            out.append(ch)
            if (color != 0) out.append("</span>")
            if (strike) out.append("~~")
            if (underline) out.append("</u>")
            if (italic) out.append("_")
            if (bold) out.append("**")
        }
        return prefixLines(block, out.toString())
    }

    private fun prefixLines(block: ContentBlock.TextBlock, raw: String): String {
        val lines = raw.split('\n')
        return when {
            block.isChecklist -> lines.withIndex().joinToString("\n") { (i, line) ->
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