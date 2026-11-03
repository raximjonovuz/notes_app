package com.example.notes.ui.edit

import org.junit.Assert.assertEquals
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
}
