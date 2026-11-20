package com.lobo.app.util

/** 后缀 -> MIME 映射（纯 Kotlin，供 HTTP 下载/上传与网页端共用）。 */
object MimeTypes {

    private val MAP: Map<String, String> = mapOf(
        "html" to "text/html; charset=utf-8",
        "htm" to "text/html; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "js" to "application/javascript; charset=utf-8",
        "mjs" to "application/javascript; charset=utf-8",
        "json" to "application/json; charset=utf-8",
        "txt" to "text/plain; charset=utf-8",
        "md" to "text/markdown; charset=utf-8",
        "log" to "text/plain; charset=utf-8",
        "csv" to "text/csv; charset=utf-8",
        "xml" to "application/xml; charset=utf-8",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "ico" to "image/x-icon",
        "heic" to "image/heic",
        "avif" to "image/avif",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "flac" to "audio/flac",
        "ogg" to "audio/ogg",
        "opus" to "audio/opus",
        "wav" to "audio/wav",
        "amr" to "audio/amr",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "mov" to "video/quicktime",
        "avi" to "video/x-msvideo",
        "3gp" to "video/3gpp",
        "ts" to "video/mp2t",
        "flv" to "video/x-flv",
        "wmv" to "video/x-ms-wmv",
        "mpg" to "video/mpeg",
        "mpeg" to "video/mpeg",
        "ogv" to "video/ogg",
        "rmvb" to "application/vnd.rn-realmedia-vbr",
        "m3u8" to "application/vnd.apple.mpegurl",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "apk" to "application/vnd.android.package-archive",
        "exe" to "application/vnd.microsoft.portable-executable",
        "msi" to "application/x-msi",
        "deb" to "application/vnd.debian.binary-package",
        "rpm" to "application/x-rpm",
        "iso" to "application/x-iso9660-image",
        "img" to "application/octet-stream",
        "bin" to "application/octet-stream",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "epub" to "application/epub+zip",
        "torrent" to "application/x-bittorrent",
        "srt" to "application/x-subrip",
        "ass" to "text/x-ssa; charset=utf-8",
        "vtt" to "text/vtt; charset=utf-8"
    )

    fun forFileName(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MAP[ext] ?: "application/octet-stream"
    }

    fun isTextLike(mime: String): Boolean =
        mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") || mime.contains("javascript")

    fun isHlsPlaylist(fileName: String): Boolean = fileName.lowercase().endsWith(".m3u8")
}
