package com.example.notes.ui.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleRangeSerializerTest {

    @Test
    fun `empty payload decodes to empty list`() {
        assertEquals(emptyList<StyleRange>(), StyleRangeSerializer.decode(""))
        assertEquals(emptyList<StyleRange>(), StyleRangeSerializer.decode("   "))
    }

    @Test
    fun `encode and decode roundtrip with bold italic underline strike and color`() {
        val ranges = listOf(
            StyleRange(start = 0, end = 5, bold = true, italic = false, underline = true, strikethrough = false, colorIndex = 2),
            StyleRange(start = 10, end = 15, bold = false, italic = true, underline = false, strikethrough = true, colorIndex = 0)
        )
        val encoded = StyleRangeSerializer.encode(ranges)
        val decoded = StyleRangeSerializer.decode(encoded)

        assertEquals(2, decoded.size)
        assertEquals(0, decoded[0].start)
        assertEquals(5, decoded[0].end)
        assertTrue(decoded[0].bold)
        assertFalse(decoded[0].italic)
        assertTrue(decoded[0].underline)
        assertFalse(decoded[0].strikethrough)
        assertEquals(2, decoded[0].colorIndex)

        assertEquals(10, decoded[1].start)
        assertEquals(15, decoded[1].end)
        assertFalse(decoded[1].bold)
        assertTrue(decoded[1].italic)
        assertFalse(decoded[1].underline)
        assertTrue(decoded[1].strikethrough)
        assertEquals(0, decoded[1].colorIndex)
    }

    @Test
    fun `decode legacy 4-flag format without strike`() {
        // Legacy format: s-e:b|i|u|cN (no strikethrough flag)
        val legacyPayload = "0-4:b|-|-|c1;8-12:-|i|u|c0"
        val decoded = StyleRangeSerializer.decode(legacyPayload)

        assertEquals(2, decoded.size)
        assertEquals(0, decoded[0].start)
        assertEquals(4, decoded[0].end)
        assertTrue(decoded[0].bold)
        assertFalse(decoded[0].italic)
        assertFalse(decoded[0].underline)
        assertFalse(decoded[0].strikethrough)
        assertEquals(1, decoded[0].colorIndex)

        assertEquals(8, decoded[1].start)
        assertEquals(12, decoded[1].end)
        assertFalse(decoded[1].bold)
        assertTrue(decoded[1].italic)
        assertTrue(decoded[1].underline)
        assertFalse(decoded[1].strikethrough)
        assertEquals(0, decoded[1].colorIndex)
    }

    @Test
    fun `decode 5-flag format with strike`() {
        val payload = "2-7:b|i|u|s|c3"
        val decoded = StyleRangeSerializer.decode(payload)

        assertEquals(1, decoded.size)
        val r = decoded[0]
        assertEquals(2, r.start)
        assertEquals(7, r.end)
        assertTrue(r.bold)
        assertTrue(r.italic)
        assertTrue(r.underline)
        assertTrue(r.strikethrough)
        assertEquals(3, r.colorIndex)
    }

    @Test
    fun `malformed segments are ignored gracefully`() {
        val malformed = "invalid;0-5:b;1-2:invalid:flags;0-4:b|-|-|-|c0;bad-range"
        val decoded = StyleRangeSerializer.decode(malformed)
        assertEquals(1, decoded.size)
        assertEquals(0, decoded[0].start)
        assertEquals(4, decoded[0].end)
        assertTrue(decoded[0].bold)
    }
}
