package com.lobo.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 展示格式化工具。 */
object FormatUtils {

    /** 1.2 GB / 345 MB / 12 KB / 980 B */
    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "0 B"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var index = 0
        while (value >= 1024 && index < units.size - 1) {
            value /= 1024.0
            index++
        }
        return if (value >= 100) {
            String.format(Locale.US, "%.0f %s", value, units[index])
        } else if (value >= 10) {
            String.format(Locale.US, "%.1f %s", value, units[index])
        } else {
            String.format(Locale.US, "%.2f %s", value, units[index])
        }
    }

    /** 毫秒 -> m:ss / h:mm:ss */
    fun formatDuration(millis: Long): String {
        if (millis <= 0) return "0:00"
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** 时间戳 -> 刚刚 / HH:mm / 昨天 HH:mm / MM-dd HH:mm */
    fun formatTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        if (diff in 0..60_000) return "刚刚"
        val date = Date(timestamp)
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(now))
        val target = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(date)
        return when {
            today == target -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
            target == yesterday(now) -> "昨天 " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
            else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(date)
        }
    }

    private fun yesterday(now: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(now - 24 * 60 * 60 * 1000L))
}
