package com.lobo.app.http

import com.lobo.app.core.Json
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status

/** 统一的 HTTP 响应工具：JSON / 纯文本 / CORS。 */
object HttpUtil {

    const val JSON_MIME = "application/json; charset=utf-8"
    const val TEXT_MIME = "text/plain; charset=utf-8"

    fun ok(body: Map<String, Any?>): Response = json(Status.OK, body)

    fun json(status: Status, body: Any?): Response =
        withCors(NanoHTTPD.newFixedLengthResponse(status, JSON_MIME, Json.stringify(body)))

    fun error(status: Status, message: String): Response =
        json(status, Json.obj("ok" to false, "error" to message))

    fun text(status: Status, body: String, mime: String = TEXT_MIME): Response =
        withCors(NanoHTTPD.newFixedLengthResponse(status, mime, body))

    fun withCors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, HEAD, OPTIONS")
        response.addHeader(
            "Access-Control-Allow-Headers",
            "Range, Content-Type, Content-Length, Accept, X-Requested-With"
        )
        response.addHeader(
            "Access-Control-Expose-Headers",
            "Content-Range, Content-Length, Accept-Ranges, Content-Disposition"
        )
        response.addHeader("Access-Control-Max-Age", "86400")
        return response
    }

    fun contentLength(session: NanoHTTPD.IHTTPSession): Long? =
        session.headers["content-length"]?.trim()?.toLongOrNull()

    fun queryParam(session: NanoHTTPD.IHTTPSession, name: String): String? =
        session.parameters[name]?.firstOrNull()

    /**
     * 把请求体读掉并丢弃。
     *
     * NanoHTTPD 不会自动消费请求体；若 POST 的 body 没被读掉，同一个 keep-alive 连接上的
     * 下一个请求会被污染（下一行变成 `{}GET /api/list ...`），服务器回 400。因此不需要
     * body 的 POST 处理完必须调用本方法。
     */
    fun drainBody(session: NanoHTTPD.IHTTPSession) {
        val length = contentLength(session) ?: return
        if (length <= 0L) return
        val input = session.inputStream ?: return
        val buffer = ByteArray(8192)
        var remaining = length
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) break
            remaining -= read
        }
    }

    /** 读取最多 [maxBytes] 字节的 UTF-8 文本；超限返回 null。 */
    fun readText(input: java.io.InputStream, maxBytes: Long): String? {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toString("UTF-8")
    }
}
