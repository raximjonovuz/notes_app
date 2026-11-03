package com.example.notes.ui.edit

object RichDocumentAdapter {

    /**
     * Map legacy integer color index (1..5) to stable color key, or null for default.
     */
    fun colorIndexToKey(colorIndex: Int): String? = when (colorIndex) {
        1 -> "yellow"
        2 -> "blue"
        3 -> "pink"
        4 -> "green"
        5 -> "orange"
        6 -> "purple"
        7 -> "red"
        8 -> "teal"
        9 -> "gray"
        else -> null
    }

    /**
     * Map stable color key back to legacy integer index (1..5 / 1..9), or 0 for default.
     */
    fun colorKeyToIndex(colorKey: String?): Int = when (colorKey?.lowercase()) {
        "yellow" -> 1
        "blue" -> 2
        "pink" -> 3
        "green" -> 4
        "orange" -> 5
        "purple" -> 6
        "red" -> 7
        "teal" -> 8
        "gray" -> 9
        else -> 0
    }

    /**
     * Convert legacy List<ContentBlock> to RichDocumentV3.
     */
    fun fromLegacy(blocks: List<ContentBlock>): RichDocumentV3 {
        val v3Blocks = mutableListOf<V3Block>()
        for (b in blocks) {
            when (b) {
                is ContentBlock.ImageBlock -> {
                    v3Blocks += V3Block.Image(uri = b.uri)
                }
                is ContentBlock.TextBlock -> {
                    val rawText = b.text
                    val lines = rawText.split('\n')
                    val paragraphs = mutableListOf<ParagraphRun>()
                    val cleanTextBuilder = StringBuilder()

                    var originalOffset = 0
                    var cleanOffset = 0
                    // Track offset mapping for spans if prefix is stripped
                    val offsetRemap = mutableListOf<Pair<Int, Int>>() // original to clean

                    for (lineIdx in lines.indices) {
                        val line = lines[lineIdx]
                        val isLast = lineIdx == lines.lastIndex

                        val isLineChecklist = b.isChecklist
                        val lineSize = b.fontSizesByLine[lineIdx] ?: b.fontSize

                        var kind = when (lineSize) {
                            2 -> ParagraphKind.H1
                            3 -> ParagraphKind.H2
                            else -> if (isLineChecklist) ParagraphKind.CHECKLIST else ParagraphKind.BODY
                        }

                        var checked = false
                        var lineContent = line

                        // If line has checklist markdown prefix "- [ ] " or "- [x] ", parse and strip
                        if (kind == ParagraphKind.CHECKLIST || line.startsWith("- [ ] ") || line.startsWith("- [x] ")) {
                            kind = ParagraphKind.CHECKLIST
                            if (line.startsWith("- [x] ") || line.startsWith("- [X] ")) {
                                checked = true
                                lineContent = line.substring(6)
                                originalOffset += 6
                            } else if (line.startsWith("- [ ] ")) {
                                checked = false
                                lineContent = line.substring(6)
                                originalOffset += 6
                            }
                        }

                        val pStart = cleanOffset
                        cleanTextBuilder.append(lineContent)
                        cleanOffset += lineContent.length
                        val pEnd = cleanOffset

                        paragraphs += ParagraphRun(
                            start = pStart,
                            end = pEnd,
                            kind = kind,
                            checked = checked
                        )

                        if (!isLast) {
                            cleanTextBuilder.append('\n')
                            cleanOffset += 1
                            originalOffset += (line.length - lineContent.length) + 1
                        }
                    }

                    // Convert StyleRange to CharSpan
                    // Note: If text wasn't shifted by prefixes, offsets match directly
                    val cleanText = cleanTextBuilder.toString()
                    val spans = b.styleRanges.mapNotNull { r ->
                        val start = r.start.coerceIn(0, cleanText.length)
                        val end = r.end.coerceIn(0, cleanText.length)
                        if (start >= end) return@mapNotNull null
                        CharSpan(
                            start = start,
                            end = end,
                            bold = r.bold,
                            italic = r.italic,
                            underline = r.underline,
                            strikethrough = r.strikethrough,
                            colorKey = colorIndexToKey(r.colorIndex)
                        )
                    }

                    v3Blocks += V3Block.Text(
                        text = cleanText,
                        paragraphs = paragraphs,
                        spans = spans
                    )
                }
            }
        }
        return RichDocumentV3(v = 3, blocks = v3Blocks)
    }

    /**
     * Convert RichDocumentV3 to legacy List<ContentBlock> so existing editors continue to work seamlessly.
     */
    fun toLegacy(doc: RichDocumentV3): List<ContentBlock> {
        val out = mutableListOf<ContentBlock>()
        for (b in doc.blocks) {
            when (b) {
                is V3Block.Image -> {
                    out += ContentBlock.ImageBlock(uri = b.uri)
                }
                is V3Block.Text -> {
                    val rawText = b.text
                    val lines = rawText.split('\n')
                    val fontSizesByLine = mutableMapOf<Int, Int>()
                    var hasChecklist = false

                    // If paragraphs define checklist or headings, reconstruct lines and metadata
                    val rebuiltLines = mutableListOf<String>()
                    var textChangedWithPrefixes = false

                    for (pIdx in b.paragraphs.indices) {
                        val p = b.paragraphs[pIdx]
                        if (p.kind == ParagraphKind.H1) {
                            fontSizesByLine[pIdx] = 2
                        } else if (p.kind == ParagraphKind.H2) {
                            fontSizesByLine[pIdx] = 3
                        }
                        if (p.kind == ParagraphKind.CHECKLIST) {
                            hasChecklist = true
                        }
                    }

                    // Map CharSpan back to StyleRange
                    val styleRanges = b.spans.map { s ->
                        StyleRange(
                            start = s.start,
                            end = s.end,
                            bold = s.bold,
                            italic = s.italic,
                            underline = s.underline,
                            strikethrough = s.strikethrough,
                            colorIndex = colorKeyToIndex(s.colorKey)
                        )
                    }

                    val baseFontSize = if (fontSizesByLine.values.any { it == 2 }) 2
                    else if (fontSizesByLine.values.any { it == 3 }) 3
                    else 1

                    out += ContentBlock.TextBlock(
                        text = rawText,
                        styleRanges = styleRanges,
                        fontSize = baseFontSize,
                        isChecklist = hasChecklist,
                        fontSizesByLine = fontSizesByLine
                    )
                }
            }
        }
        return out
    }
}
