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

    private data class LegacyLineMap(
        val legacyStart: Int,
        val legacyEnd: Int,
        val strippedPrefixLen: Int,
        val cleanStart: Int,
        val cleanEnd: Int
    )

    private data class CleanLineMap(
        val cleanStart: Int,
        val cleanEnd: Int,
        val addedPrefixLen: Int,
        val legacyStart: Int,
        val legacyEnd: Int
    )

    data class ChecklistLineParseResult(
        val hasCheckbox: Boolean,
        val checked: Boolean,
        val cleanText: String,
        val strippedPrefixLen: Int
    )

    fun parseChecklistLine(line: String): ChecklistLineParseResult {
        var curr = line
        var checked = false
        var stripped = 0
        var hasCheckbox = false
        while (true) {
            when {
                curr.startsWith("- [x] ") || curr.startsWith("- [X] ") -> {
                    hasCheckbox = true
                    checked = true
                    curr = curr.substring(6)
                    stripped += 6
                }
                curr.startsWith("- [ ] ") -> {
                    hasCheckbox = true
                    curr = curr.substring(6)
                    stripped += 6
                }
                curr == "- [x]" || curr == "- [X]" -> {
                    hasCheckbox = true
                    checked = true
                    curr = ""
                    stripped += 5
                    break
                }
                curr == "- [ ]" -> {
                    hasCheckbox = true
                    curr = ""
                    stripped += 5
                    break
                }
                else -> break
            }
        }
        return ChecklistLineParseResult(
            hasCheckbox = hasCheckbox,
            checked = checked,
            cleanText = curr,
            strippedPrefixLen = stripped
        )
    }

    private fun stripChecklistPrefixes(line: String): Triple<String, Boolean, Int> {
        val res = parseChecklistLine(line)
        return Triple(res.cleanText, res.checked, res.strippedPrefixLen)
    }
    private fun mapLegacyOffsetToClean(offset: Int, lineMaps: List<LegacyLineMap>, cleanTextLen: Int): Int {
        if (lineMaps.isEmpty() || offset < 0) return 0
        for (map in lineMaps) {
            if (offset <= map.legacyEnd) {
                val contentStart = map.legacyStart + map.strippedPrefixLen
                return if (offset <= contentStart) {
                    map.cleanStart
                } else {
                    map.cleanStart + (offset - contentStart)
                }
            }
        }
        return cleanTextLen
    }

    private fun mapCleanOffsetToLegacy(offset: Int, lineMaps: List<CleanLineMap>, legacyTextLen: Int): Int {
        if (lineMaps.isEmpty() || offset < 0) return 0
        for (map in lineMaps) {
            if (offset <= map.cleanEnd) {
                val delta = offset - map.cleanStart
                return map.legacyStart + map.addedPrefixLen + delta
            }
        }
        return legacyTextLen
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
                    val legacyLineMaps = mutableListOf<LegacyLineMap>()

                    var currentLegacyStart = 0
                    var currentCleanStart = 0

                    val anyLineHasPrefix = lines.any { line ->
                        stripChecklistPrefixes(line).third > 0
                    }

                    for (lineIdx in lines.indices) {
                        val line = lines[lineIdx]
                        val isLast = lineIdx == lines.lastIndex
                        val legacyLineEnd = currentLegacyStart + line.length

                        val (cleanContent, hasCheckedPrefix, strippedPrefixLen) = stripChecklistPrefixes(line)
                        val hasPrefix = strippedPrefixLen > 0

                        val isLineChecklist = if (anyLineHasPrefix) hasPrefix else b.isChecklist

                        var kind = when {
                            b.fontSizesByLine.containsKey(lineIdx) -> when (b.fontSizesByLine[lineIdx]) {
                                2 -> ParagraphKind.H1
                                3 -> ParagraphKind.H2
                                else -> if (isLineChecklist) ParagraphKind.CHECKLIST else ParagraphKind.BODY
                            }
                            lineIdx == 0 -> when (b.fontSize) {
                                2 -> ParagraphKind.H1
                                3 -> ParagraphKind.H2
                                else -> if (isLineChecklist) ParagraphKind.CHECKLIST else ParagraphKind.BODY
                            }
                            else -> if (isLineChecklist) ParagraphKind.CHECKLIST else ParagraphKind.BODY
                        }

                        if (hasPrefix && kind == ParagraphKind.BODY) {
                            kind = ParagraphKind.CHECKLIST
                        }

                        val checked = if (kind == ParagraphKind.CHECKLIST) hasCheckedPrefix else false

                        val pStart = currentCleanStart
                        cleanTextBuilder.append(cleanContent)
                        val pEnd = pStart + cleanContent.length

                        paragraphs += ParagraphRun(
                            start = pStart,
                            end = pEnd,
                            kind = kind,
                            checked = checked
                        )

                        legacyLineMaps += LegacyLineMap(
                            legacyStart = currentLegacyStart,
                            legacyEnd = legacyLineEnd,
                            strippedPrefixLen = strippedPrefixLen,
                            cleanStart = pStart,
                            cleanEnd = pEnd
                        )

                        currentLegacyStart = legacyLineEnd + 1
                        currentCleanStart = pEnd
                        if (!isLast) {
                            cleanTextBuilder.append('\n')
                            currentCleanStart += 1
                        }
                    }

                    val cleanText = cleanTextBuilder.toString()
                    val spans = b.styleRanges.mapNotNull { r ->
                        val cleanStart = mapLegacyOffsetToClean(r.start, legacyLineMaps, cleanText.length)
                        val cleanEnd = mapLegacyOffsetToClean(r.end, legacyLineMaps, cleanText.length)
                        if (cleanStart >= cleanEnd) return@mapNotNull null
                        CharSpan(
                            start = cleanStart,
                            end = cleanEnd,
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

                    val rebuiltLines = mutableListOf<String>()
                    val cleanLineMaps = mutableListOf<CleanLineMap>()

                    var currentCleanStart = 0
                    var currentLegacyStart = 0

                    for (pIdx in lines.indices) {
                        val line = lines[pIdx]
                        val cleanLineEnd = currentCleanStart + line.length

                        val p = b.paragraphs.getOrNull(pIdx)
                        val isChecklist = p?.kind == ParagraphKind.CHECKLIST
                        if (isChecklist) {
                            hasChecklist = true
                        }

                        // Already has prefix? If so, don't duplicate.
                        val alreadyHasPrefix = line.startsWith("- [ ] ") || line.startsWith("- [x] ") ||
                                line.startsWith("- [X] ") || line == "- [ ]" || line == "- [x]" || line == "- [X]"

                        val (rebuiltLine, addedPrefixLen) = if (isChecklist && !alreadyHasPrefix) {
                            val prefix = if (p?.checked == true) "- [x] " else "- [ ] "
                            (prefix + line) to prefix.length
                        } else {
                            line to 0
                        }

                        rebuiltLines += rebuiltLine
                        val legacyLineEnd = currentLegacyStart + rebuiltLine.length

                        cleanLineMaps += CleanLineMap(
                            cleanStart = currentCleanStart,
                            cleanEnd = cleanLineEnd,
                            addedPrefixLen = addedPrefixLen,
                            legacyStart = currentLegacyStart,
                            legacyEnd = legacyLineEnd
                        )

                        currentCleanStart = cleanLineEnd + 1
                        currentLegacyStart = legacyLineEnd + 1
                    }

                    val legacyText = rebuiltLines.joinToString("\n")

                    val allH1 = b.paragraphs.isNotEmpty() && b.paragraphs.all { it.kind == ParagraphKind.H1 }
                    val allH2 = b.paragraphs.isNotEmpty() && b.paragraphs.all { it.kind == ParagraphKind.H2 }
                    val baseFontSize = when {
                        allH1 -> 2
                        allH2 -> 3
                        else -> 1
                    }

                    for (pIdx in b.paragraphs.indices) {
                        val p = b.paragraphs[pIdx]
                        val lineSize = when (p?.kind) {
                            ParagraphKind.H1 -> 2
                            ParagraphKind.H2 -> 3
                            else -> 1
                        }
                        if (lineSize != baseFontSize) {
                            fontSizesByLine[pIdx] = lineSize
                        }
                    }

                    // Map CharSpan back to StyleRange, shifting offsets for added prefixes
                    val styleRanges = b.spans.mapNotNull { s ->
                        val legacyStart = mapCleanOffsetToLegacy(s.start, cleanLineMaps, legacyText.length)
                        val legacyEnd = mapCleanOffsetToLegacy(s.end, cleanLineMaps, legacyText.length)
                        if (legacyStart >= legacyEnd) return@mapNotNull null
                        StyleRange(
                            start = legacyStart,
                            end = legacyEnd,
                            bold = s.bold,
                            italic = s.italic,
                            underline = s.underline,
                            strikethrough = s.strikethrough,
                            colorIndex = colorKeyToIndex(s.colorKey)
                        )
                    }

                    out += ContentBlock.TextBlock(
                        text = legacyText,
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
