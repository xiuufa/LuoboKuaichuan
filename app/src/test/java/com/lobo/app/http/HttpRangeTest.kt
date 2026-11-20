package com.lobo.app.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpRangeTest {

    @Test
    fun `no header means full content`() {
        assertEquals(RangeSpec.None, HttpRange.parse(null, 1024))
        assertEquals(RangeSpec.None, HttpRange.parse("", 1024))
        assertEquals(RangeSpec.None, HttpRange.parse("bytes=", 1024))
    }

    @Test
    fun `closed range`() {
        val spec = HttpRange.parse("bytes=0-99", 1000) as RangeSpec.Single
        assertEquals(0L, spec.start)
        assertEquals(99L, spec.end)
        assertEquals(100L, spec.length)
    }

    @Test
    fun `open ended range`() {
        val spec = HttpRange.parse("bytes=100-", 1000) as RangeSpec.Single
        assertEquals(100L, spec.start)
        assertEquals(999L, spec.end)
        assertEquals(900L, spec.length)
    }

    @Test
    fun `suffix range`() {
        val spec = HttpRange.parse("bytes=-100", 1000) as RangeSpec.Single
        assertEquals(900L, spec.start)
        assertEquals(999L, spec.end)
        assertEquals(100L, spec.length)
    }

    @Test
    fun `end beyond size is clamped`() {
        val spec = HttpRange.parse("bytes=900-5000", 1000) as RangeSpec.Single
        assertEquals(900L, spec.start)
        assertEquals(999L, spec.end)
        assertEquals(100L, spec.length)
    }

    @Test
    fun `start beyond size is unsatisfiable`() {
        assertEquals(RangeSpec.Unsatisfiable, HttpRange.parse("bytes=2000-3000", 1000))
        assertEquals(RangeSpec.Unsatisfiable, HttpRange.parse("bytes=-0", 1000))
    }

    @Test
    fun `multiple ranges are rejected`() {
        assertEquals(RangeSpec.Multiple, HttpRange.parse("bytes=0-10,20-30", 1000))
    }

    @Test
    fun `invalid header is rejected`() {
        assertEquals(RangeSpec.None, HttpRange.parse("chunks=0-10", 1000))
        assertEquals(RangeSpec.None, HttpRange.parse("bytes=abc-def", 1000))
    }

    @Test
    fun `content range formatting`() {
        assertEquals("bytes 0-99/1000", HttpRange.contentRange(0, 99, 1000))
        assertEquals("bytes */1000", HttpRange.unsatisfiedRange(1000))
    }
}
