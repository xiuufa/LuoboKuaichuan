package com.lobo.app.core

/**
 * 轻量 JSON 解析 / 序列化（纯 Kotlin，不依赖 org.json）。
 *
 * 这样传输板存储、HTTP API、单元测试三处共用同一套实现，JVM 测试无需 Android 环境。
 *
 * 解析结果类型映射：
 * - JSON object  -> LinkedHashMap<String, Any?>
 * - JSON array   -> ArrayList<Any?>
 * - JSON string  -> String
 * - JSON number  -> Long（无小数） / Double（有小数或指数）
 * - JSON bool    -> Boolean
 * - JSON null    -> null
 */
object Json {

    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        require(parser.atEnd()) { "JSON 末尾存在多余内容，位置 ${parser.pos}" }
        return value
    }

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    fun obj(vararg pairs: Pair<String, Any?>): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
        pairs.forEach { put(it.first, it.second) }
    }

    fun arr(vararg values: Any?): List<Any?> = values.toList()

    // ==================== 序列化 ====================

    private fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Float -> writeDouble(sb, value.toDouble())
            is Double -> writeDouble(sb, value)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k?.toString() ?: "null")
                    sb.append(':')
                    write(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }
            is Array<*> -> write(sb, value.toList())
            is LongArray -> write(sb, value.toList())
            is IntArray -> write(sb, value.toList())
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeDouble(sb: StringBuilder, value: Double) {
        if (!value.isFinite()) {
            sb.append("null")
        } else if (value == value.toLong().toDouble()) {
            sb.append(value.toLong().toString())
        } else {
            sb.append(value.toString())
        }
    }

    private fun writeString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') {
                    sb.append("\\u").append(String.format("%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }

    // ==================== 解析 ====================

    private class Parser(private val s: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            require(!atEnd()) { "JSON 意外结束" }
            return when (val c = s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> if (c == '-' || c.isDigit()) parseNumber()
                else throw IllegalArgumentException("无法解析的字符 '$c'，位置 $pos")
            }
        }

        private fun parseLiteral(literal: String, value: Any?): Any? {
            require(s.startsWith(literal, pos)) { "非法字面量，位置 $pos" }
            pos += literal.length
            return value
        }

        private fun parseObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWhitespace()
            if (!atEnd() && s[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                require(!atEnd() && s[pos] == '"') { "对象 key 必须是字符串，位置 $pos" }
                val key = parseString()
                skipWhitespace()
                expect(':')
                map[key] = parseValue()
                skipWhitespace()
                require(!atEnd()) { "JSON 意外结束" }
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("对象缺少 ',' 或 '}'，位置 $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // [
            skipWhitespace()
            if (!atEnd() && s[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                require(!atEnd()) { "JSON 意外结束" }
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("数组缺少 ',' 或 ']'，位置 $pos")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(!atEnd()) { "字符串意外结束" }
                when (val c = s[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(!atEnd()) { "转义字符意外结束" }
                        when (val esc = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(pos + 4 <= s.length) { "\\u 转义不完整" }
                                val hex = s.substring(pos, pos + 4)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("非法转义 \\$esc，位置 ${pos - 1}")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = pos
            if (!atEnd() && s[pos] == '-') pos++
            while (!atEnd() && s[pos].isDigit()) pos++
            var isDouble = false
            if (!atEnd() && s[pos] == '.') {
                isDouble = true
                pos++
                while (!atEnd() && s[pos].isDigit()) pos++
            }
            if (!atEnd() && (s[pos] == 'e' || s[pos] == 'E')) {
                isDouble = true
                pos++
                if (!atEnd() && (s[pos] == '+' || s[pos] == '-')) pos++
                while (!atEnd() && s[pos].isDigit()) pos++
            }
            val raw = s.substring(start, pos)
            return if (isDouble) raw.toDouble() else raw.toLong()
        }

        private fun expect(ch: Char) {
            require(!atEnd() && s[pos] == ch) { "期望 '$ch'，位置 $pos" }
            pos++
        }
    }
}

// ==================== Map 便捷读取 ====================

fun Map<String, Any?>.str(key: String): String? = this[key] as? String

fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) {
    is Long -> v
    is Int -> v.toLong()
    is Double -> v.toLong()
    is Float -> v.toLong()
    is String -> v.toLongOrNull()
    else -> null
}

fun Map<String, Any?>.int(key: String): Int? = long(key)?.toInt()

fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.arr(key: String): List<Any?>? = this[key] as? List<Any?>
