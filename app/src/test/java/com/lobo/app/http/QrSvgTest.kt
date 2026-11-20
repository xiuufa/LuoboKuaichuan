package com.lobo.app.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrSvgTest {

    @Test
    fun `generates valid svg wrapper`() {
        val svg = QrSvg.generate("http://10.119.106.214:8080")
        assertTrue("应以 <svg 开头", svg.trimStart().startsWith("<svg"))
        assertTrue("应包含结束标签", svg.trimEnd().endsWith("</svg>"))
        assertTrue("应包含白色背景", svg.contains("#FFFFFF"))
        assertTrue("应包含二维码路径", svg.contains("<path"))
    }

    @Test
    fun `respects custom colors and size`() {
        val svg = QrSvg.generate("luobo", foreground = "#111111", background = "#EEEEEE", outputSize = 320)
        assertTrue(svg.contains("#111111"))
        assertTrue(svg.contains("#EEEEEE"))
        assertTrue(svg.contains("width=\"320\""))
        assertTrue(svg.contains("height=\"320\""))
    }

    @Test
    fun `different payloads produce different svg`() {
        val a = QrSvg.generate("http://a.example:8080")
        val b = QrSvg.generate("http://b.example:8081")
        assertNotEquals(a, b)
        assertFalse(a.isEmpty())
    }
}
