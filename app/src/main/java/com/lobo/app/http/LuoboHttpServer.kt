package com.lobo.app.http

import android.content.Context
import com.lobo.app.core.AppConfig
import com.lobo.app.core.Json
import com.lobo.app.core.long
import com.lobo.app.core.str
import com.lobo.app.data.BoardItem
import com.lobo.app.data.BoardRepository
import com.lobo.app.util.LogUtils
import com.lobo.app.util.MimeTypes
import com.lobo.app.util.NetworkUtils
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 萝卜快传的局域网 HTTP 主机。
 *
 * 所有端（APP 原生界面、浏览器网页、PC/Linux、py 脚本）都是这个 HTTP 服务的客户端，
 * 它们共享同一块「传输板」，接口说明见 docs/API.md。
 */
class LuoboHttpServer(
    private val context: Context,
    private val repository: BoardRepository,
    private val deviceId: String,
    private val deviceName: () -> String,
    private val storagePath: () -> String,
    port: Int = AppConfig.HTTP_PORT
) : NanoHTTPD(port) {

    private val startedAt = System.currentTimeMillis()
    private val uploads = ConcurrentHashMap<String, UploadSession>()

    private class UploadSession(
        val uploadId: String,
        val itemId: String,
        val file: File,
        val size: Long
    ) {
        @Volatile
        var received: Long = 0L
    }

    sealed class StartResult {
        data object Started : StartResult()
        data class Failed(val message: String) : StartResult()
    }

    private val runner = ThreadPoolAsyncRunner()

    init {
        setAsyncRunner(runner)
    }

    fun startServer(): StartResult {
        return try {
            start(SOCKET_READ_TIMEOUT, false)
            StartResult.Started
        } catch (e: IOException) {
            LogUtils.e("LuoboHttpServer", "启动失败的端口 $listeningPort: ${e.message}", e)
            StartResult.Failed(e.message ?: "无法启动 HTTP 服务")
        }
    }

    fun stopServer() {
        uploads.clear()
        runCatching { stop() }
        runner.closeAll()
    }

    fun isRunning(): Boolean = isAlive

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (session.method == Method.OPTIONS) {
                return HttpUtil.withCors(
                    NanoHTTPD.newFixedLengthResponse(Status.NO_CONTENT, HttpUtil.TEXT_MIME, "")
                )
            }
            val path = normalizePath(session.uri)
            when (session.method) {
                Method.GET, Method.HEAD -> handleGet(session, path)
                Method.POST -> handlePost(session, path)
                Method.PUT -> handlePut(session, path)
                Method.DELETE -> handleDelete(session, path)
                else -> HttpUtil.error(Status.METHOD_NOT_ALLOWED, "不支持的请求方法: ${session.method}")
            }
        } catch (t: Throwable) {
            LogUtils.e("LuoboHttpServer", "处理请求失败 ${session.uri}: ${t.message}", t)
            HttpUtil.error(Status.INTERNAL_ERROR, t.message ?: "服务器内部错误")
        }
    }

    // ---------------------------------------------------------------- GET

    private fun handleGet(session: IHTTPSession, path: String): Response {
        return when {
            path == "/" || path == "/index.html" ->
                StaticAssets.serve(context, "index.html")
                    ?: HttpUtil.error(Status.NOT_FOUND, "网页资源缺失，请重新打包 APP")

            path == "/favicon.ico" || path == "/favicon.svg" ->
                HttpUtil.withCors(NanoHTTPD.newFixedLengthResponse(Status.NO_CONTENT, HttpUtil.TEXT_MIME, ""))

            path == "/api/info" -> HttpUtil.ok(infoJson())

            path == "/api/list" -> HttpUtil.ok(listJson(session))

            path.startsWith("/api/file/") ->
                fileResponse(session, path.removePrefix("/api/file/"))

            path == "/api/qr.svg" -> qrSvgResponse(session)

            else -> StaticAssets.serve(context, path)
                ?: HttpUtil.error(Status.NOT_FOUND, "未找到: $path")
        }
    }

    private fun infoJson(): Map<String, Any?> {
        val urls = NetworkUtils.urlsFor(listeningPort)
        return Json.obj(
            "ok" to true,
            "app" to AppConfig.API_NAME,
            "apiVersion" to AppConfig.API_VERSION,
            "appName" to AppConfig.APP_NAME,
            "versionName" to AppConfig.VERSION_NAME,
            "deviceName" to deviceName(),
            "deviceId" to deviceId,
            "port" to listeningPort,
            "url" to urls.firstOrNull(),
            "urls" to urls,
            "boardVersion" to repository.currentVersion(),
            "itemCount" to repository.size(),
            "maxTextBytes" to AppConfig.MAX_TEXT_BYTES,
            "maxUploadBytes" to AppConfig.MAX_FILE_BYTES,
            "chunkSize" to AppConfig.CHUNK_SIZE,
            "bigFileThreshold" to AppConfig.BIG_FILE_THRESHOLD,
            "storagePath" to storagePath(),
            "features" to listOf("text", "file", "chunked-upload", "range-download", "qr", "shared-board"),
            "uptimeSeconds" to (System.currentTimeMillis() - startedAt) / 1000L
        )
    }

    private fun listJson(session: IHTTPSession): Map<String, Any?> {
        val since = HttpUtil.queryParam(session, "since")?.toLongOrNull()
        val version = repository.currentVersion()
        // items 始终返回：文件接收进度（received）不改变 version，客户端靠轮询 items 展示进度。
        return Json.obj(
            "ok" to true,
            "version" to version,
            "itemCount" to repository.size(),
            "serverTime" to System.currentTimeMillis(),
            "unchanged" to (since != null && since == version),
            "items" to repository.listNewestFirst().map { it.toJson() }
        )
    }

    private fun fileResponse(session: IHTTPSession, rawId: String): Response {
        val id = rawId.substringBefore('?').substringBefore('/')
        val item = repository.get(id) ?: return HttpUtil.error(Status.NOT_FOUND, "条目不存在")
        if (!item.isFile) return HttpUtil.error(Status.BAD_REQUEST, "该条目不是文件")
        if (!item.isReady) return HttpUtil.error(Status.CONFLICT, "文件仍在接收中")
        val file = repository.fileOf(item) ?: return HttpUtil.error(Status.NOT_FOUND, "文件已丢失")
        if (!file.isFile) return HttpUtil.error(Status.NOT_FOUND, "文件已丢失")

        val size = file.length()
        val mime = item.mime.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: MimeTypes.forFileName(item.name)
        val download = HttpUtil.queryParam(session, "download") == "1"
        val disposition = contentDisposition(item.name, download)
        val range = HttpRange.parse(session.headers["range"], size)

        val response: Response = when (range) {
            is RangeSpec.None, is RangeSpec.Multiple -> {
                val stream = FileInputStream(file)
                NanoHTTPD.newFixedLengthResponse(Status.OK, mime, stream, size)
            }

            is RangeSpec.Single -> {
                skipFully(FileInputStream(file), range.start) { input ->
                    NanoHTTPD.newFixedLengthResponse(Status.PARTIAL_CONTENT, mime, input, range.length).also {
                        it.addHeader("Content-Range", HttpRange.contentRange(range.start, range.end, size))
                    }
                }
            }

            is RangeSpec.Unsatisfiable -> {
                val resp = NanoHTTPD.newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, HttpUtil.TEXT_MIME, "")
                resp.addHeader("Content-Range", HttpRange.unsatisfiedRange(size))
                HttpUtil.withCors(resp)
            }
        }

        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Disposition", disposition)
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("Content-Security-Policy", "default-src 'none'")
        return HttpUtil.withCors(response)
    }

    private fun qrSvgResponse(session: IHTTPSession): Response {
        val data = HttpUtil.queryParam(session, "data")
            ?: NetworkUtils.urlFor(listeningPort)
            ?: "http://127.0.0.1:$listeningPort"
        val svg = QrSvg.generate(data)
        val response = NanoHTTPD.newFixedLengthResponse(
            Status.OK, "image/svg+xml; charset=utf-8", svg
        )
        response.addHeader("Cache-Control", "no-store")
        return HttpUtil.withCors(response)
    }

    // ---------------------------------------------------------------- POST

    private fun handlePost(session: IHTTPSession, path: String): Response {
        return when {
            path == "/api/text" -> handleSendText(session)
            path == "/api/upload/init" -> handleUploadInit(session)
            path.startsWith("/api/upload/") && path.endsWith("/complete") ->
                handleUploadComplete(session, path.removePrefix("/api/upload/").removeSuffix("/complete"))

            path.startsWith("/api/item/") && path.endsWith("/resend") -> {
                HttpUtil.drainBody(session)
                val itemId = path.removePrefix("/api/item/").removeSuffix("/resend").trim('/')
                val copy = repository.resend(itemId, deviceName(), deviceId)
                if (copy == null) {
                    HttpUtil.error(Status.NOT_FOUND, "条目不存在或原文件已丢失")
                } else {
                    HttpUtil.ok(
                        Json.obj(
                            "ok" to true,
                            "item" to copy.toJson(),
                            "version" to repository.currentVersion()
                        )
                    )
                }
            }

            path == "/api/clear" -> {
                HttpUtil.drainBody(session)
                uploads.clear()
                val removed = repository.clear()
                HttpUtil.ok(Json.obj("ok" to true, "removed" to removed.size, "version" to repository.currentVersion()))
            }

            else -> HttpUtil.error(Status.NOT_FOUND, "未找到接口: $path")
        }
    }

    private fun handleSendText(session: IHTTPSession): Response {
        val body = when (val result = readJsonBody(session, AppConfig.MAX_TEXT_BYTES.toLong())) {
            is BodyResult.Ok -> result.json
            BodyResult.Invalid -> return HttpUtil.error(Status.BAD_REQUEST, "请求体必须是 JSON 对象")
            BodyResult.TooLarge -> return HttpUtil.error(Status.PAYLOAD_TOO_LARGE, "文本超过大小限制")
        }
        val text = body.str("text").orEmpty()
        if (text.isBlank()) return HttpUtil.error(Status.BAD_REQUEST, "text 不能为空")
        if (text.toByteArray(Charsets.UTF_8).size > AppConfig.MAX_TEXT_BYTES) {
            return HttpUtil.error(Status.PAYLOAD_TOO_LARGE, "文本超过大小限制")
        }
        val item = repository.addText(
            text = text,
            sender = body.str("sender") ?: deviceName(),
            senderId = body.str("senderId") ?: ""
        )
        return HttpUtil.ok(
            Json.obj(
                "ok" to true,
                "item" to item.toJson(),
                "version" to repository.currentVersion()
            )
        )
    }

    private fun handleUploadInit(session: IHTTPSession): Response {
        val body = when (val result = readJsonBody(session, 64 * 1024)) {
            is BodyResult.Ok -> result.json
            BodyResult.Invalid -> return HttpUtil.error(Status.BAD_REQUEST, "请求体必须是 JSON 对象")
            BodyResult.TooLarge -> return HttpUtil.error(Status.PAYLOAD_TOO_LARGE, "请求体过大")
        }
        val name = body.str("name")?.trim().orEmpty()
        if (name.isEmpty()) return HttpUtil.error(Status.BAD_REQUEST, "name 不能为空")
        val size = body.long("size") ?: -1L
        if (size < 0) return HttpUtil.error(Status.BAD_REQUEST, "size 无效")
        if (size > AppConfig.MAX_FILE_BYTES) {
            return HttpUtil.error(Status.PAYLOAD_TOO_LARGE, "文件超过 ${AppConfig.MAX_FILE_BYTES} 字节")
        }
        val mime = body.str("mime")?.takeIf { it.isNotBlank() } ?: MimeTypes.forFileName(name)
        val (item, file) = repository.beginFile(
            name = name,
            size = size,
            mime = mime,
            sender = body.str("sender") ?: deviceName(),
            senderId = body.str("senderId") ?: ""
        )
        val upload = UploadSession(item.id, item.id, file, size)
        uploads[item.id] = upload
        val response = Json.obj(
            "ok" to true,
            "uploadId" to item.id,
            "itemId" to item.id,
            "offset" to 0,
            "size" to size,
            "chunkSize" to AppConfig.CHUNK_SIZE,
            "item" to item.toJson(),
            "version" to repository.currentVersion()
        )
        if (size == 0L) {
            uploads.remove(item.id)
            val completed = repository.completeFile(item.id)
            return HttpUtil.ok(response + mapOf("completed" to true, "item" to completed?.toJson()))
        }
        return HttpUtil.ok(response + mapOf("completed" to false))
    }

    private fun handleUploadComplete(session: IHTTPSession, uploadId: String): Response {
        // complete 是空 body POST，必须把 body 读掉，否则复用连接的下一个请求会被污染成 400
        HttpUtil.drainBody(session)
        val item = repository.get(uploadId)
            ?: return HttpUtil.error(Status.NOT_FOUND, "上传会话不存在")
        if (item.isReady) {
            return HttpUtil.ok(
                Json.obj("ok" to true, "completed" to true, "item" to item.toJson(),
                    "version" to repository.currentVersion())
            )
        }
        if (!item.isReceiving) return HttpUtil.error(Status.CONFLICT, "该条目状态异常")
        val upload = uploads[uploadId]
            ?: return HttpUtil.error(Status.CONFLICT, "上传会话已失效，请重新发起上传")
        if (upload.received < upload.size) {
            return HttpUtil.error(Status.CONFLICT, "文件尚未接收完整（${upload.received}/${upload.size}）")
        }
        uploads.remove(uploadId)
        val completed = repository.completeFile(item.id)
            ?: return HttpUtil.error(Status.INTERNAL_ERROR, "完成上传失败")
        return HttpUtil.ok(
            Json.obj("ok" to true, "completed" to true, "item" to completed.toJson(),
                "version" to repository.currentVersion())
        )
    }

    // ---------------------------------------------------------------- PUT

    private fun handlePut(session: IHTTPSession, path: String): Response {
        if (!path.startsWith("/api/upload/")) {
            return HttpUtil.error(Status.NOT_FOUND, "未找到接口: $path")
        }
        val uploadId = path.removePrefix("/api/upload/")
        val upload = uploads[uploadId]
            ?: return HttpUtil.error(Status.NOT_FOUND, "上传会话不存在或已结束")
        val requestedOffset = HttpUtil.queryParam(session, "offset")?.toLongOrNull() ?: upload.received
        if (requestedOffset != upload.received) {
            return HttpUtil.json(
                Status.CONFLICT,
                Json.obj(
                    "ok" to false,
                    "error" to "offset mismatch",
                    "offset" to upload.received,
                    "size" to upload.size
                )
            )
        }
        val contentLength = HttpUtil.contentLength(session)
            ?: return HttpUtil.error(Status.LENGTH_REQUIRED, "缺少 Content-Length")
        if (contentLength <= 0) return HttpUtil.error(Status.BAD_REQUEST, "Content-Length 无效")
        val remaining = upload.size - upload.received
        if (contentLength > remaining) {
            return HttpUtil.error(Status.PAYLOAD_TOO_LARGE, "写入长度超过文件剩余大小")
        }

        var written = 0L
        try {
            written = appendToFile(upload.file, session.inputStream, contentLength)
        } finally {
            if (written > 0) {
                upload.received += written
                repository.appendReceived(upload.itemId, written)
            }
        }

        val completed = upload.received >= upload.size
        if (completed) {
            uploads.remove(uploadId)
            repository.completeFile(upload.itemId)
        }
        return HttpUtil.ok(
            Json.obj(
                "ok" to true,
                "received" to upload.received,
                "size" to upload.size,
                "completed" to completed,
                "version" to repository.currentVersion()
            )
        )
    }

    // ---------------------------------------------------------------- DELETE

    private fun handleDelete(session: IHTTPSession, path: String): Response {
        return when {
            path.startsWith("/api/upload/") -> {
                val uploadId = path.removePrefix("/api/upload/")
                uploads.remove(uploadId)
                repository.failFile(uploadId)
                HttpUtil.ok(Json.obj("ok" to true, "version" to repository.currentVersion()))
            }

            path.startsWith("/api/file/") -> {
                // 只删除磁盘原文件，保留传输板记录（记录会标记为文件已删除）
                val itemId = path.removePrefix("/api/file/")
                uploads.remove(itemId)
                if (repository.deleteFileOnly(itemId)) {
                    HttpUtil.ok(Json.obj("ok" to true, "version" to repository.currentVersion()))
                } else {
                    HttpUtil.error(Status.NOT_FOUND, "条目不存在或不是文件")
                }
            }

            path.startsWith("/api/item/") -> {
                val itemId = path.removePrefix("/api/item/")
                uploads.remove(itemId)
                val deleted = repository.delete(itemId)
                if (deleted) {
                    HttpUtil.ok(Json.obj("ok" to true, "version" to repository.currentVersion()))
                } else {
                    HttpUtil.error(Status.NOT_FOUND, "条目不存在")
                }
            }

            else -> HttpUtil.error(Status.NOT_FOUND, "未找到接口: $path")
        }
    }

    // ---------------------------------------------------------------- 工具

    private sealed class BodyResult {
        data class Ok(val json: Map<String, Any?>) : BodyResult()
        data object Invalid : BodyResult()
        data object TooLarge : BodyResult()
    }

    private fun readJsonBody(session: IHTTPSession, maxBytes: Long): BodyResult {
        val declared = HttpUtil.contentLength(session)
        if (declared != null && declared > maxBytes) return BodyResult.TooLarge
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        val input = session.inputStream
        if (declared != null && declared > 0) {
            // 按 Content-Length 精确读取，不能读到 EOF：HTTP/1.1 keep-alive 下流不会结束。
            var remaining = declared
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read < 0) break
                bytes.write(buffer, 0, read)
                remaining -= read
            }
        } else {
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) return BodyResult.TooLarge
                bytes.write(buffer, 0, read)
            }
        }
        if (bytes.size().toLong() > maxBytes) return BodyResult.TooLarge
        val parsed = runCatching { Json.parse(bytes.toString("UTF-8")) }.getOrNull() as? Map<*, *>
            ?: return BodyResult.Invalid
        @Suppress("UNCHECKED_CAST")
        val map = parsed as Map<String, Any?>
        return BodyResult.Ok(map)
    }

    private fun appendToFile(file: File, input: InputStream, length: Long): Long {
        var remaining = length
        var written = 0L
        FileOutputStream(file, true).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read < 0) break
                out.write(buffer, 0, read)
                written += read
                remaining -= read
            }
            out.flush()
        }
        return written
    }

    private fun skipFully(input: InputStream, count: Long, block: (InputStream) -> Response): Response {
        var remaining = count
        try {
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                } else {
                    if (input.read() < 0) return HttpUtil.error(Status.INTERNAL_ERROR, "读取文件失败")
                    remaining -= 1
                }
            }
        } catch (e: IOException) {
            runCatching { input.close() }
            throw e
        }
        return block(input)
    }

    private fun contentDisposition(name: String, download: Boolean): String {
        val encoded = try {
            URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        } catch (_: Exception) {
            "download"
        }
        val type = if (download) "attachment" else "inline"
        return "$type; filename*=UTF-8''$encoded"
    }

    private fun normalizePath(uri: String?): String {
        var path = (uri ?: "/").substringBefore('?')
        if (path.isEmpty()) path = "/"
        if (path.length > 1 && path.endsWith('/')) path = path.dropLast(1)
        return path
    }
}
