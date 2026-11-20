package com.lobo.app.http

/**
 * HTTP Range 请求解析（支持单区间，多区间按不支持处理）。
 *
 * 例：
 * - `bytes=0-499`   -> Single(0, 499)
 * - `bytes=500-`    -> Single(500, size-1)
 * - `bytes=-500`    -> 最后 500 字节
 */
sealed class RangeSpec {
    data object None : RangeSpec()
    data object Multiple : RangeSpec()
    data object Unsatisfiable : RangeSpec()
    data class Single(val start: Long, val end: Long) : RangeSpec() {
        val length: Long get() = end - start + 1
    }
}

object HttpRange {

    fun parse(header: String?, size: Long): RangeSpec {
        if (header.isNullOrBlank()) return RangeSpec.None
        val trimmed = header.trim()
        if (!trimmed.startsWith("bytes=", ignoreCase = true)) return RangeSpec.None
        val ranges = trimmed.substring(6).split(',')
        if (ranges.size > 1) return RangeSpec.Multiple
        val spec = ranges.first().trim()
        // 语法非法时按 RFC 忽略 Range 头，返回完整内容
        if (spec.isEmpty()) return RangeSpec.None
        val dash = spec.indexOf('-')
        if (dash < 0) return RangeSpec.None
        val startRaw = spec.substring(0, dash).trim()
        val endRaw = spec.substring(dash + 1).trim()
        return if (startRaw.isEmpty()) {
            // bytes=-N：最后 N 字节
            val suffix = endRaw.toLongOrNull() ?: return RangeSpec.None
            if (suffix <= 0 || size == 0L) return RangeSpec.Unsatisfiable
            val start = (size - suffix).coerceAtLeast(0)
            RangeSpec.Single(start, size - 1)
        } else {
            val start = startRaw.toLongOrNull() ?: return RangeSpec.None
            if (start >= size || start < 0) return RangeSpec.Unsatisfiable
            val end = if (endRaw.isEmpty()) size - 1 else (endRaw.toLongOrNull() ?: return RangeSpec.None)
            if (end < start) return RangeSpec.Unsatisfiable
            RangeSpec.Single(start, end.coerceAtMost(size - 1))
        }
    }

    fun contentRange(start: Long, end: Long, size: Long): String = "bytes $start-$end/$size"

    fun unsatisfiedRange(size: Long): String = "bytes */$size"
}
