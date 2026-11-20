package com.lobo.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志：同时输出到 Logcat 与应用私有目录 luobo.log（超过 512KB 轮转）。 */
object LogUtils {

    private const val TAG = "Luobo"
    private const val MAX_LOG_SIZE = 512 * 1024L
    private var logFile: File? = null
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        logFile = File(context.filesDir, "luobo.log")
    }

    fun d(tag: String, message: String) = log(Log.DEBUG, tag, message, null)

    fun i(tag: String, message: String) = log(Log.INFO, tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable? = null) = log(Log.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) = log(Log.ERROR, tag, message, throwable)

    private fun log(priority: Int, tag: String, message: String, throwable: Throwable?) {
        val fullTag = "$TAG/$tag"
        try {
            when (priority) {
                Log.ERROR -> Log.e(fullTag, message, throwable)
                Log.WARN -> Log.w(fullTag, message, throwable)
                else -> Log.d(fullTag, message, throwable)
            }
        } catch (_: Throwable) {
        }
        val file = logFile ?: return
        try {
            synchronized(this) {
                if (file.isFile && file.length() > MAX_LOG_SIZE) {
                    val backup = File(file.parentFile, "luobo.log.1")
                    backup.delete()
                    file.renameTo(backup)
                }
                val text = buildString {
                    append(timeFormat.format(Date()))
                    append(' ')
                    append(if (priority == Log.ERROR) "E" else if (priority == Log.WARN) "W" else "D")
                    append('/')
                    append(tag)
                    append(": ")
                    append(message)
                    if (throwable != null) {
                        append(" | ")
                        append(throwable.javaClass.simpleName)
                        append(": ")
                        append(throwable.message)
                    }
                    append('\n')
                }
                file.appendText(text)
            }
        } catch (_: Throwable) {
        }
    }
}
