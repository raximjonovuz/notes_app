package com.example.notes.ui.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RichDocumentV3Test {

    @Test
    fun `v3 document serialize and parse roundtrip`() {
        val original = RichDocumentV3(
            v = 3,
            blocks = listOf(
                V3Block.Text(
                    text = "Title\nParagraph body text",
                    paragraphs = listOf(
                        ParagraphRun(start = 0, end = 5, kind = ParagraphKind.H1),
                        ParagraphRun(start = 6, end = 25, kind = ParagraphKind.BODY)
                    ),
                    spans = listOf(
                        CharSpan(start = 0, end = 5, bold = true),
                        CharSpan(start = 6, end = 15, italic = true, colorKey = "blue")
                    )
                ),
                V3Block.Image(uri = "file:///storage/emulated/0/Pictures/test.jpg")
            )
        )

        val json = serializeV3(original)
        assertTrue(json.contains("\"v\":3") || json.contains("\"v\": 3"))
        val parsed = parseV3(json)

        assertNotNull(parsed)
        assertEquals(3, parsed!!.v)
        assertEquals(2, parsed.blocks.size)

        val textBlock = parsed.blocks[0] as V3Block.Text
        assertEquals("Title\nParagraph body text", textBlock.text)
        assertEquals(2, textBlock.paragraphs.size)
        assertEquals(ParagraphKind.H1, textBlock.paragraphs[0].kind)
        assertEquals(ParagraphKind.BODY, textBlock.paragraphs[1].kind)
        assertEquals(2, textBlock.spans.size)
        assertTrue(textBlock.spans[0].bold)
        assertEquals("blue", textBlock.spans[1].colorKey)

        val imgBlock = parsed.blocks[1] as V3Block.Image
        assertEquals("file:///storage/emulated/0/Pictures/test.jpg", imgBlock.uri)
    }

    @Test
    fun `adapter preserves image URI and formatting during conversion`() {
        val legacyBlocks = listOf(
            ContentBlock.TextBlock(
                text = "Hello world",
                styleRanges = listOf(StyleRange(start = 0, end = 5, bold = true))
            ),
            ContentBlock.ImageBlock(uri = "content://media/external/images/media/42")
        )

        val v3Doc = RichDocumentAdapter.fromLegacy(legacyBlocks)
        assertEquals(2, v3Doc.blocks.size)
        val img = v3Doc.blocks[1] as V3Block.Image
        assertEquals("content://media/external/images/media/42", img.uri)

        val convertedBack = RichDocumentAdapter.toLegacy(v3Doc)
        assertEquals(2, convertedBack.size)
        val textBlock = convertedBack[0] as ContentBlock.TextBlock
        assertEquals("Hello world", textBlock.text)
        assertTrue(textBlock.styleRanges[0].bold)
        val imgBlock = convertedBack[1] as ContentBlock.ImageBlock
        assertEquals("content://media/external/images/media/42", imgBlock.uri)
    }

    @Test
    fun `parseBlocksFromContent loads legacy v2 and converts to v3 on save`() {
        val legacyV2 = """{"v":2,"blocks":[{"t":"text","text":"Legacy text","fontSize":1,"ranges":[{"start":0,"end":6,"bold":true,"italic":false,"underline":false,"strikethrough":false,"colorIndex":0}]}]}"""
        val blocks = parseBlocksFromContent(legacyV2)

        assertEquals(1, blocks.size)
        val tb = blocks[0] as ContentBlock.TextBlock
        assertEquals("Legacy text", tb.text)
        assertTrue(tb.styleRanges[0].bold)

        // Save emits V3
        val saved = serializeBlocksToContent(blocks)
        assertTrue(saved.contains("\"v\":3") || saved.contains("\"v\": 3"))

        // Reopen V3
        val reopened = parseBlocksFromContent(saved)
        assertEquals(1, reopened.size)
        assertEquals("Legacy text", (reopened[0] as ContentBlock.TextBlock).text)
    }

    @Test
    fun `legacy block with 3 lines and block-level fontSize 2 converts only line 1 to H1 and rest to BODY`() {
        val legacyBlock = ContentBlock.TextBlock(
            text = "First Line Heading\nSecond Line Normal\nThird Line Normal",
            fontSize = 2, // Legacy Heading size applied to entire block
            fontSizesByLine = emptyMap() // Empty line map
        )

        val v3Doc = RichDocumentAdapter.fromLegacy(listOf(legacyBlock))
        assertEquals(1, v3Doc.blocks.size)

        val textBlock = v3Doc.blocks[0] as V3Block.Text
        assertEquals(3, textBlock.paragraphs.size)

        assertEquals("First paragraph must be H1", ParagraphKind.H1, textBlock.paragraphs[0].kind)
        assertEquals(0, textBlock.paragraphs[0].start)
        assertEquals(18, textBlock.paragraphs[0].end)

        assertEquals("Second paragraph must be BODY", ParagraphKind.BODY, textBlock.paragraphs[1].kind)
        assertEquals("Third paragraph must be BODY", ParagraphKind.BODY, textBlock.paragraphs[2].kind)
    }

    @Test
    fun `legacy block with fontSizesByLine preserves explicit per-line heading levels`() {
        val legacyBlock = ContentBlock.TextBlock(
            text = "Line 1\nLine 2\nLine 3",
            fontSize = 1,
            fontSizesByLine = mapOf(1 to 3) // Line 2 is H2
        )

        val v3Doc = RichDocumentAdapter.fromLegacy(listOf(legacyBlock))
        val textBlock = v3Doc.blocks[0] as V3Block.Text
        assertEquals(3, textBlock.paragraphs.size)

        assertEquals(ParagraphKind.BODY, textBlock.paragraphs[0].kind)
        assertEquals(ParagraphKind.H2, textBlock.paragraphs[1].kind)
        assertEquals(ParagraphKind.BODY, textBlock.paragraphs[2].kind)
    }

    @Test
    fun `benchmark 5000 character document serialization and parsing`() {
        val longText = (1..500).joinToString("\n") { i -> "Line $i: A moderately long text block with some words and formatting." }
        assertTrue(longText.length >= 5000)

        val textBlock = ContentBlock.TextBlock(
            text = longText,
            styleRanges = listOf(
                StyleRange(start = 0, end = 100, bold = true),
                StyleRange(start = 200, end = 300, italic = true, colorIndex = 2),
                StyleRange(start = 1000, end = 2000, underline = true)
            )
        )

        // Warm up JIT
        serializeBlocksToContent(listOf(textBlock))

        val start = System.currentTimeMillis()
        val content = serializeBlocksToContent(listOf(textBlock))
        val parsed = parseBlocksFromContent(content)
        val duration = System.currentTimeMillis() - start

        assertEquals(1, parsed.size)
        assertEquals(longText.length, (parsed[0] as ContentBlock.TextBlock).text.length)
        assertTrue("Duration should be fast (< 500ms), was ${duration}ms", duration < 500)
    }

    @Test
    fun `checklist 3 items check second and reorder maintains exactly 3 paragraphs without duplicate checkboxes`() {
        // 1. Initial 3 checklist items in legacy block (as typed/edited in ChecklistBlockEditor)
        val initialLegacy = ContentBlock.TextBlock(
            text = "- [ ] Item 1\n- [ ] Item 2\n- [ ] Item 3",
            isChecklist = true
        )

        // 2. User checks the 2nd item -> "- [x] Item 2"
        val checkedLegacy = ContentBlock.TextBlock(
            text = "- [ ] Item 1\n- [x] Item 2\n- [ ] Item 3",
            isChecklist = true
        )

        // 3. Convert to V3 document via adapter
        val v3Doc = RichDocumentAdapter.fromLegacy(listOf(checkedLegacy))
        val v3Text = v3Doc.blocks[0] as V3Block.Text
        assertEquals(3, v3Text.paragraphs.size)
        assertFalse(v3Text.paragraphs[0].checked)
        assertTrue(v3Text.paragraphs[1].checked)
        assertFalse(v3Text.paragraphs[2].checked)

        // 4. Reorder checked items using ChecklistReducer (unchecked first, checked moved to bottom)
        val reorderedRuns = ChecklistReducer.reorderChecked(v3Text.paragraphs)
        assertEquals(3, reorderedRuns.size)
        assertFalse("1st run must be unchecked", reorderedRuns[0].checked)
        assertFalse("2nd run must be unchecked", reorderedRuns[1].checked)
        assertTrue("3rd run must be checked", reorderedRuns[2].checked)

        // Reorder document text so that text lines match the reordered runs
        val textLines = listOf(
            v3Text.text.substring(reorderedRuns[0].start, reorderedRuns[0].end),
            v3Text.text.substring(reorderedRuns[1].start, reorderedRuns[1].end),
            v3Text.text.substring(reorderedRuns[2].start, reorderedRuns[2].end)
        )
        assertEquals("Item 1", textLines[0])
        assertEquals("Item 3", textLines[1])
        assertEquals("Item 2", textLines[2])

        // Build reordered V3 document
        val reorderedText = textLines.joinToString("\n")
        var off = 0
        val alignedRuns = reorderedRuns.mapIndexed { idx, run ->
            val len = textLines[idx].length
            val r = run.copy(start = off, end = off + len)
            off += len + 1
            r
        }
        val reorderedDoc = RichDocumentV3(
            v = 3,
            blocks = listOf(
                V3Block.Text(
                    text = reorderedText,
                    paragraphs = alignedRuns,
                    spans = emptyList()
                )
            )
        )

        // 5. Roundtrip to legacy (for existing editors / Room compatibility)
        val legacyBlocks = RichDocumentAdapter.toLegacy(reorderedDoc)
        assertEquals(1, legacyBlocks.size)
        val legacyBlock = legacyBlocks[0] as ContentBlock.TextBlock

        // Legacy block text MUST preserve checklist markdown prefixes so ChecklistBlockEditor can parse them!
        // Expecting: "- [ ] Item 1\n- [ ] Item 3\n- [x] Item 2"
        // But currently toLegacy returns rawText without prefixes: "Item 1\nItem 3\nItem 2"
        assertTrue(
            "Legacy block text must retain checklist prefixes to avoid losing checkboxes in ChecklistBlockEditor",
            legacyBlock.text.startsWith("- [ ] ")
        )
        assertEquals(
            "- [ ] Item 1\n- [ ] Item 3\n- [x] Item 2",
            legacyBlock.text
        )

        // 6. Roundtrip back to V3 from legacy — must not duplicate checkboxes or paragraphs
        val roundtripDoc = RichDocumentAdapter.fromLegacy(legacyBlocks)
        val roundtripText = roundtripDoc.blocks[0] as V3Block.Text
        assertEquals("Must have exactly 3 paragraphs", 3, roundtripText.paragraphs.size)
        assertEquals("Item 1\nItem 3\nItem 2", roundtripText.text)
        assertFalse(roundtripText.paragraphs[0].checked)
        assertFalse(roundtripText.paragraphs[1].checked)
        assertTrue(roundtripText.paragraphs[2].checked)
    }

    @Test
    fun `span offsets preserved accurately during checklist prefix roundtrip with bold and underline`() {
        // Line 0: "- [ ] First item" (len 16, content "First item" len 10)
        // Line 1: "- [x] Second bold and underline item" (starts at 17)
        //   Prefix is 17..22 ("- [x] ")
        //   Content "Second bold and underline item" starts at 23.
        //   "bold" is at 23 + 7 = 30..34
        //   "underline" is at 23 + 16 = 39..48
        val legacyText = "- [ ] First item\n- [x] Second bold and underline item"
        val initialLegacy = ContentBlock.TextBlock(
            text = legacyText,
            styleRanges = listOf(
                StyleRange(start = 30, end = 34, bold = true),
                StyleRange(start = 39, end = 48, underline = true)
            ),
            isChecklist = true
        )

        // 1. Convert to V3
        val v3Doc = RichDocumentAdapter.fromLegacy(listOf(initialLegacy))
        val v3Text = v3Doc.blocks[0] as V3Block.Text
        assertEquals("First item\nSecond bold and underline item", v3Text.text)
        assertEquals(2, v3Text.spans.size)

        // In V3 clean text:
        // Line 0 is "First item" (0..10), '\n' at 10.
        // Line 1 "Second bold and underline item" starts at 11.
        // "bold" starts at 11 + 7 = 18..22.
        // "underline" starts at 11 + 16 = 27..36.
        val boldSpan = v3Text.spans.first { it.bold }
        assertEquals(18, boldSpan.start)
        assertEquals(22, boldSpan.end)
        assertEquals("bold", v3Text.text.substring(boldSpan.start, boldSpan.end))

        val underlineSpan = v3Text.spans.first { it.underline }
        assertEquals(27, underlineSpan.start)
        assertEquals(36, underlineSpan.end)
        assertEquals("underline", v3Text.text.substring(underlineSpan.start, underlineSpan.end))

        // 2. Convert back to legacy
        val legacyBlocks = RichDocumentAdapter.toLegacy(v3Doc)
        val legacyBlock = legacyBlocks[0] as ContentBlock.TextBlock
        assertEquals(legacyText, legacyBlock.text)
        assertEquals(2, legacyBlock.styleRanges.size)

        val legBold = legacyBlock.styleRanges.first { it.bold }
        assertEquals(30, legBold.start)
        assertEquals(34, legBold.end)
        assertEquals("bold", legacyBlock.text.substring(legBold.start, legBold.end))

        val legUnderline = legacyBlock.styleRanges.first { it.underline }
        assertEquals(39, legUnderline.start)
        assertEquals(48, legUnderline.end)
        assertEquals("underline", legacyBlock.text.substring(legUnderline.start, legUnderline.end))

        // 3. Roundtrip back to V3 again
        val v3Roundtrip = RichDocumentAdapter.fromLegacy(legacyBlocks)
        val v3Text2 = v3Roundtrip.blocks[0] as V3Block.Text
        assertEquals(v3Text.text, v3Text2.text)
        assertEquals(v3Text.spans, v3Text2.spans)
        assertEquals(v3Text.paragraphs, v3Text2.paragraphs)
    }

    @Test
    fun `duplicate prefix protection strips multiple markdown prefixes and roundtrip is idempotent`() {
        // Given legacy text with duplicate consecutive prefixes: "- [ ] - [ ] Double item"
        val legacyWithDupes = ContentBlock.TextBlock(
            text = "- [ ] - [ ] Double item\n- [x] - [ ] Mixed double item",
            styleRanges = listOf(
                // In "- [ ] - [ ] Double item", "Double" is at 12..18
                StyleRange(start = 12, end = 18, bold = true)
            ),
            isChecklist = true
        )

        // 1. fromLegacy should strip all duplicate prefixes into a single checklist paragraph
        val v3Doc = RichDocumentAdapter.fromLegacy(listOf(legacyWithDupes))
        val v3Text = v3Doc.blocks[0] as V3Block.Text
        assertEquals("Double item\nMixed double item", v3Text.text)
        assertEquals(2, v3Text.paragraphs.size)
        assertEquals(ParagraphKind.CHECKLIST, v3Text.paragraphs[0].kind)
        assertFalse("1st item was unchecked", v3Text.paragraphs[0].checked)
        assertEquals(ParagraphKind.CHECKLIST, v3Text.paragraphs[1].kind)
        assertTrue("2nd item had - [x] prefix so it must be checked", v3Text.paragraphs[1].checked)

        // Span "Double" in clean text must be at 0..6
        assertEquals(1, v3Text.spans.size)
        assertEquals(0, v3Text.spans[0].start)
        assertEquals(6, v3Text.spans[0].end)
        assertEquals("Double", v3Text.text.substring(0, 6))

        // 2. toLegacy creates clean, single-prefix legacy output
        val legacyOnce = RichDocumentAdapter.toLegacy(v3Doc)
        val blockOnce = legacyOnce[0] as ContentBlock.TextBlock
        assertEquals("- [ ] Double item\n- [x] Mixed double item", blockOnce.text)

        // In "- [ ] Double item", "Double" is at 6..12
        assertEquals(1, blockOnce.styleRanges.size)
        assertEquals(6, blockOnce.styleRanges[0].start)
        assertEquals(12, blockOnce.styleRanges[0].end)

        // 3. Repeated roundtrip (legacy -> V3 -> legacy -> V3) is completely idempotent
        val v3Doc2 = RichDocumentAdapter.fromLegacy(legacyOnce)
        val legacyTwice = RichDocumentAdapter.toLegacy(v3Doc2)
        val blockTwice = legacyTwice[0] as ContentBlock.TextBlock
        assertEquals(blockOnce.text, blockTwice.text)
        assertEquals(blockOnce.styleRanges, blockTwice.styleRanges)

        val v3Doc3 = RichDocumentAdapter.fromLegacy(legacyTwice)
        assertEquals(v3Doc.blocks, v3Doc3.blocks)
    }

    @Test
    fun `mixed block with body and checklist paragraphs only prepends prefixes to checklist lines`() {
        val mixedDoc = RichDocumentV3(
            v = 3,
            blocks = listOf(
                V3Block.Text(
                    text = "Header title\nFirst checklist\nSecond checked\nFooter note",
                    paragraphs = listOf(
                        ParagraphRun(start = 0, end = 12, kind = ParagraphKind.H1),
                        ParagraphRun(start = 13, end = 28, kind = ParagraphKind.CHECKLIST, checked = false),
                        ParagraphRun(start = 29, end = 43, kind = ParagraphKind.CHECKLIST, checked = true),
                        ParagraphRun(start = 44, end = 55, kind = ParagraphKind.BODY)
                    ),
                    spans = listOf(
                        // "title" in Header title (7..12)
                        CharSpan(start = 7, end = 12, italic = true),
                        // "checked" in Second checked (36..43)
                        CharSpan(start = 36, end = 43, bold = true)
                    )
                )
            )
        )

        // 1. toLegacy must only prepend prefixes to lines 1 and 2
        val legacyBlocks = RichDocumentAdapter.toLegacy(mixedDoc)
        assertEquals(1, legacyBlocks.size)
        val legacyBlock = legacyBlocks[0] as ContentBlock.TextBlock

        val expectedLegacyText = "Header title\n- [ ] First checklist\n- [x] Second checked\nFooter note"
        assertEquals(expectedLegacyText, legacyBlock.text)
        assertTrue(legacyBlock.isChecklist)

        // In legacy text:
        // Line 0: "Header title" (0..12) -> "title" is at 7..12
        // Line 1: "- [ ] First checklist" (starts at 13, len 21)
        // Line 2: "- [x] Second checked" (starts at 35)
        //   Prefix is 35..40 ("- [x] ")
        //   "Second checked" starts at 41.
        //   "checked" is at 41 + 7 = 48..55
        val legItalic = legacyBlock.styleRanges.first { it.italic }
        assertEquals(7, legItalic.start)
        assertEquals(12, legItalic.end)
        assertEquals("title", legacyBlock.text.substring(legItalic.start, legItalic.end))

        val legBold = legacyBlock.styleRanges.first { it.bold }
        assertEquals(48, legBold.start)
        assertEquals(55, legBold.end)
        assertEquals("checked", legacyBlock.text.substring(legBold.start, legBold.end))

        // 2. Roundtrip back to V3 preserves mixed paragraph kinds and spans
        val roundtripDoc = RichDocumentAdapter.fromLegacy(legacyBlocks)
        val roundtripText = roundtripDoc.blocks[0] as V3Block.Text
        assertEquals("Header title\nFirst checklist\nSecond checked\nFooter note", roundtripText.text)
        assertEquals(ParagraphKind.H1, roundtripText.paragraphs[0].kind)
        assertEquals(ParagraphKind.CHECKLIST, roundtripText.paragraphs[1].kind)
        assertFalse(roundtripText.paragraphs[1].checked)
        assertEquals(ParagraphKind.CHECKLIST, roundtripText.paragraphs[2].kind)
        assertTrue(roundtripText.paragraphs[2].checked)
        assertEquals(ParagraphKind.BODY, roundtripText.paragraphs[3].kind)

        assertEquals(2, roundtripText.spans.size)
        val rtItalic = roundtripText.spans.first { it.italic }
        assertEquals(7, rtItalic.start)
        assertEquals(12, rtItalic.end)

        val rtBold = roundtripText.spans.first { it.bold }
        assertEquals(36, rtBold.start)
        assertEquals(43, rtBold.end)
    }
}
