package com.lobo.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {

    @Test
    fun roundTripObjectWithLongs() {
        val original = Json.obj(
            "version" to 3L,
            "size" to 12L,
            "createdAt" to 1793337399412L,
            "text" to "hello 萝卜 🥕",
            "ok" to true,
            "items" to Json.arr(Json.obj("id" to "abc", "size" to 24L))
        )
        val encoded = Json.stringify(original)
        println("ENCODED=$encoded")
        @Suppress("UNCHECKED_CAST")
        val decoded = Json.parse(encoded) as Map<String, Any?>
        println("DECODED=$decoded")
        assertEquals(3L, decoded["version"])
        assertEquals(12L, decoded["size"])
        assertEquals(1793337399412L, decoded["createdAt"])
        assertEquals("hello 萝卜 🥕", decoded["text"])
        assertEquals(true, decoded["ok"])
        @Suppress("UNCHECKED_CAST")
        val items = decoded["items"] as List<Map<String, Any?>>
        assertEquals(24L, items[0]["size"])
    }

    @Test
    fun parseSimpleNumbers() {
        @Suppress("UNCHECKED_CAST")
        val m = Json.parse("""{"a":12,"b":12.5,"c":-7,"d":1e3}""") as Map<String, Any?>
        println("NUMS=$m")
        assertEquals(12L, m["a"])
        assertEquals(12.5, m["b"])
        assertEquals(-7L, m["c"])
        assertEquals(1000.0, m["d"])
    }

    @Test
    fun parseUnicodeEscape() {
        assertEquals("萝卜", Json.parse("\"\\u841d\\u535c\""))
    }

    @Test
    fun parseNested() {
        val v = Json.parse("""{"ok":true,"items":[{"size":9}],"n":null}""")
        assertTrue(v is Map<*, *>)
    }
}
