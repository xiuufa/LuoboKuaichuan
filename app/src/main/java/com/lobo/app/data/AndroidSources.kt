package com.lobo.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.lobo.app.util.MimeTypes
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/** 普通文件上传源。 */
class FileUploadSource(
    private val file: File,
    override val mime: String = MimeTypes.forFileName(file.name)
) : Uploader.Source() {
    override val name: String get() = file.name
    override val size: Long get() = file.length()
    override fun open(): InputStream = FileInputStream(file)
}

/**
 * content:// Uri 上传源（系统分享、文件选择器、其它应用发来的文件）。
 * 名称与大小从 OpenableColumns 查询；大小未知时由调用方先拷贝到缓存目录。
 */
class UriUploadSource(
    private val context: Context,
    private val uri: Uri,
    fallbackName: String = "shared_file"
) : Uploader.Source() {

    private val resolver = context.contentResolver
    private val query: Pair<String, Long> = queryMeta(fallbackName)

    override val name: String get() = query.first
    override val size: Long get() = query.second
    override val mime: String get() = resolver.getType(uri) ?: MimeTypes.forFileName(name)

    override fun open(): InputStream =
        resolver.openInputStream(uri) ?: throw IOException("无法读取文件: $uri")

    private fun queryMeta(fallbackName: String): Pair<String, Long> {
        var name = guessName(fallbackName)
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                            name = cleanName(cursor.getString(nameIndex)) ?: name
                        }
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                            size = cursor.getLong(sizeIndex)
                        }
                    }
                }
        } catch (_: Throwable) {
        }
        return name to size
    }

    private fun guessName(fallbackName: String): String {
        val fromUri = when (uri.scheme) {
            "file" -> uri.path?.let { File(it).name }
            else -> uri.lastPathSegment
        }
        return cleanName(fromUri) ?: cleanName(fallbackName) ?: "shared_file"
    }

    private fun cleanName(raw: String?): String? {
        val name = raw?.trim()?.substringAfterLast('/')?.substringAfterLast('\\')
        return if (name.isNullOrEmpty() || name == "." || name == "..") null else name
    }

    companion object {
        /** 把 content:// 拷贝到缓存目录，返回普通文件源（用于大小未知或需要稳定路径的场景）。 */
        fun copyToCache(context: Context, uri: Uri, name: String? = null): FileUploadSource {
            val source = UriUploadSource(context, uri, name ?: "shared_file")
            val dir = File(context.cacheDir, "incoming").apply { mkdirs() }
            // 缓存文件名尽量保留原始文件名，重名时才加时间戳前缀。
            val plainName = source.name
            var target = File(dir, plainName)
            if (target.exists()) target = File(dir, "${System.currentTimeMillis()}_$plainName")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
            } ?: throw IOException("无法读取文件: $uri")
            return FileUploadSource(target)
        }
    }
}
