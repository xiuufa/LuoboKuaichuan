package com.lobo.app.data

import com.lobo.app.core.AppConfig
import com.lobo.app.core.Json
import com.lobo.app.core.arr
import com.lobo.app.core.bool
import com.lobo.app.core.int
import com.lobo.app.core.long
import com.lobo.app.core.obj
import com.lobo.app.core.str
import com.lobo.app.util.LogUtils
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 服务端返回的业务错误。 */
class BoardException(message: String, val statusCode: Int = 0) : IOException(message)

/** 分片上传时服务端记录的偏移与客户端不一致（服务端会带回真实 offset）。 */
class OffsetMismatchException(val offset: Long, val size: Long) : IOException("上传偏移不一致")

data class ServerInfo(
    val appName: String,
    val versionName: String,
    val deviceName: String,
    val deviceId: String,
    val port: Int,
    val url: String?,
    val urls: List<String>,
    val itemCount: Int,
    val maxUploadBytes: Long,
    val chunkSize: Int,
    val storagePath: String
) {
    companion object {
        fun fromJson(json: Map<String, Any?>): ServerInfo = ServerInfo(
            appName = json.str("appName") ?: AppConfig.APP_NAME,
            versionName = json.str("versionName") ?: "",
            deviceName = json.str("deviceName") ?: "",
            deviceId = json.str("deviceId") ?: "",
            port = json.int("port") ?: AppConfig.HTTP_PORT,
            url = json.str("url"),
            urls = json.arr("urls")?.mapNotNull { it as? String } ?: emptyList(),
            itemCount = json.int("itemCount") ?: 0,
            maxUploadBytes = json.long("maxUploadBytes") ?: AppConfig.MAX_FILE_BYTES,
            chunkSize = json.int("chunkSize") ?: AppConfig.CHUNK_SIZE,
            storagePath = json.str("storagePath") ?: ""
        )
    }
}

data class BoardListResult(
    val version: Long,
    val items: List<BoardItem>,
    val unchanged: Boolean
)

data class UploadTicket(
    val uploadId: String,
    val itemId: String,
    val offset: Long,
    val chunkSize: Int
)

data class ChunkResult(
    val received: Long,
    val size: Long,
    val completed: Boolean
)

/**
 * 传输板 HTTP 客户端：APP 无论作为主机（指向 127.0.0.1）还是客户端（指向扫码得到的地址），
 * 都通过它访问同一套接口。
 */
class BoardClient(baseUrl: String) {

    @Volatile
    var baseUrl: String = normalize(baseUrl)

    fun info(): ServerInfo = ServerInfo.fromJson(request("GET", "/api/info"))

    fun list(since: Long? = null): BoardListResult {
        val path = if (since == null) "/api/list" else "/api/list?since=$since"
        val json = request("GET", path)
        val items = json.arr("items")?.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            BoardItem.fromJson(map as Map<String, Any?>)
        } ?: emptyList()
        return BoardListResult(
            version = json.long("version") ?: 0L,
            items = items,
            unchanged = json.bool("unchanged") == true
        )
    }

    fun sendText(text: String, sender: String, senderId: String): BoardItem {
        val body = Json.stringify(
            Json.obj("text" to text, "sender" to sender, "senderId" to senderId)
        ).toByteArray(Charsets.UTF_8)
        val json = request("POST", "/api/text", body)
        val item = json.obj("item") ?: throw BoardException("服务端未返回条目")
        return BoardItem.fromJson(item)
    }

    fun initUpload(name: String, size: Long, mime: String, sender: String, senderId: String): UploadTicket {
        val body = Json.stringify(
            Json.obj(
                "name" to name,
                "size" to size,
                "mime" to mime,
                "sender" to sender,
                "senderId" to senderId
            )
        ).toByteArray(Charsets.UTF_8)
        val json = request("POST", "/api/upload/init", body)
        return UploadTicket(
            uploadId = json.str("uploadId") ?: throw BoardException("服务端未返回 uploadId"),
            itemId = json.str("itemId") ?: "",
            offset = json.long("offset") ?: 0L,
            chunkSize = json.int("chunkSize") ?: AppConfig.CHUNK_SIZE
        )
    }

    fun putChunk(uploadId: String, offset: Long, data: ByteArray): ChunkResult {
        val json = request(
            "PUT",
            "/api/upload/$uploadId?offset=$offset",
            data,
            contentType = "application/octet-stream"
        )
        return ChunkResult(
            received = json.long("received") ?: 0L,
            size = json.long("size") ?: 0L,
            completed = json.bool("completed") == true
        )
    }

    fun completeUpload(uploadId: String): BoardItem {
        val json = request("POST", "/api/upload/$uploadId/complete")
        val item = json.obj("item") ?: throw BoardException("服务端未返回条目")
        return BoardItem.fromJson(item)
    }

    fun cancelUpload(uploadId: String) {
        request("DELETE", "/api/upload/$uploadId")
    }

    fun deleteItem(itemId: String) {
        request("DELETE", "/api/item/$itemId")
    }

    /** 再次发送：服务端复制出一条新条目。 */
    fun resendItem(itemId: String): BoardItem {
        val json = request("POST", "/api/item/$itemId/resend")
        val item = json.obj("item") ?: throw BoardException("服务端未返回条目")
        return BoardItem.fromJson(item)
    }

    /** 只删除磁盘上的原文件，保留传输板记录。 */
    fun deleteItemFile(itemId: String) {
        request("DELETE", "/api/file/$itemId")
    }

    fun clear() {
        request("POST", "/api/clear")
    }

    fun fileUrl(itemId: String): String = "$baseUrl/api/file/$itemId"

    fun qrUrl(data: String): String =
        "$baseUrl/api/qr.svg?data=${URLEncoder.encode(data, "UTF-8")}"

    // ---------------------------------------------------------------- 内部

    private fun request(
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String = "application/json; charset=utf-8"
    ): Map<String, Any?> {
        val url = URL(baseUrl + path)
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = 60_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = readAll(stream)
            val parsed = runCatching { Json.parse(text) }.getOrNull() as? Map<*, *>
            @Suppress("UNCHECKED_CAST")
            val json: Map<String, Any?> = (parsed as? Map<String, Any?>) ?: emptyMap()

            if (code == HttpURLConnection.HTTP_CONFLICT && method == "PUT") {
                val offset = json.long("offset")
                if (offset != null) {
                    throw OffsetMismatchException(offset, json.long("size") ?: 0L)
                }
            }
            if (code !in 200..299) {
                throw BoardException(json.str("error") ?: "HTTP $code", code)
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun readAll(input: InputStream?): String {
        if (input == null) return ""
        return input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            output.toString("UTF-8")
        }
    }

    companion object {
        fun normalize(raw: String): String {
            var value = raw.trim()
            if (value.isEmpty()) return ""
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
                value = "http://$value"
            }
            while (value.endsWith("/")) value = value.dropLast(1)
            return value
        }

        /** 探测地址是否为萝卜快传主机。 */
        fun probe(baseUrl: String): ServerInfo? {
            return try {
                BoardClient(baseUrl).info()
            } catch (t: Throwable) {
                LogUtils.d("BoardClient", "探测 $baseUrl 失败: ${t.message}")
                null
            }
        }
    }
}
