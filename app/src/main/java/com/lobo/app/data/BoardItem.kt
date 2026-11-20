package com.lobo.app.data

/**
 * 传输板条目：一条文本，或一个文件（接收中 / 已就绪）。
 *
 * 传输板是「主机维护、所有客户端共享」的同一份列表：
 * 任何设备 POST /api/text 或分片上传文件后，其它设备都会看到同一条记录。
 */
data class BoardItem(
    val id: String,
    /** [KIND_TEXT] 或 [KIND_FILE] */
    val kind: String,
    /** 展示名：文本条目为文本预览，文件条目为文件名 */
    val name: String,
    val text: String = "",
    val size: Long = 0L,
    val mime: String = "text/plain",
    val sender: String = "",
    val senderId: String = "",
    val createdAt: Long = 0L,
    /** [STATE_RECEIVING] / [STATE_READY] / [STATE_FAILED] */
    val state: String = STATE_READY,
    /** 已接收字节数（接收中的文件用于展示进度） */
    val received: Long = 0L,
    /** 文件在主机磁盘上的实际文件名（API 不对外暴露） */
    val fileName: String = ""
) {
    val isText: Boolean get() = kind == KIND_TEXT
    val isFile: Boolean get() = kind == KIND_FILE
    val isReady: Boolean get() = state == STATE_READY
    val isReceiving: Boolean get() = state == STATE_RECEIVING

    fun toJson(): Map<String, Any?> {
        // 注意：不能用 LinkedHashMap().apply { ... } 直接写 size ——
        // apply 接收者是 Map，`size` 会被解析成 Map.size（当前条目数）而不是本对象的 size。
        val map = LinkedHashMap<String, Any?>()
        map["id"] = id
        map["kind"] = kind
        map["name"] = name
        map["text"] = text
        map["size"] = this.size
        map["mime"] = mime
        map["sender"] = sender
        map["senderId"] = senderId
        map["createdAt"] = createdAt
        map["state"] = state
        map["received"] = received
        map["fileName"] = fileName
        return map
    }

    companion object {
        const val KIND_TEXT = "text"
        const val KIND_FILE = "file"

        const val STATE_RECEIVING = "receiving"
        const val STATE_READY = "ready"
        const val STATE_FAILED = "failed"

        fun fromJson(json: Map<String, Any?>): BoardItem = BoardItem(
            id = json.str("id") ?: "",
            kind = json.str("kind") ?: KIND_TEXT,
            name = json.str("name") ?: "",
            text = json.str("text") ?: "",
            size = json.long("size") ?: 0L,
            mime = json.str("mime") ?: "application/octet-stream",
            sender = json.str("sender") ?: "",
            senderId = json.str("senderId") ?: "",
            createdAt = json.long("createdAt") ?: 0L,
            state = json.str("state") ?: STATE_READY,
            received = json.long("received") ?: 0L,
            fileName = json.str("fileName") ?: ""
        )
    }
}

private fun Map<String, Any?>.str(key: String): String? = this[key] as? String
private fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) {
    is Long -> v
    is Int -> v.toLong()
    is Double -> v.toLong()
    else -> null
}
