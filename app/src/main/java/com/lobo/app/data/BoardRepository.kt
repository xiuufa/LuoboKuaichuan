package com.lobo.app.data

import com.lobo.app.core.Json
import com.lobo.app.util.LogUtils
import java.io.File
import java.util.UUID

/**
 * 共享传输板的唯一数据源（主机侧）。
 *
 * - 线程安全：所有读写都在 [lock] 内完成；
 * - 持久化：结构变化（增/删/完成/清空）写入 [storeFile]（JSON），接收进度不落盘；
 * - 文件：接收中的分片直接写入 [filesDir]，完成时改名为最终文件（[BoardItem.fileName]）；
 * - 容量：超过 [maxItems] 淘汰最旧条目并删除对应文件。
 *
 * 设计为纯 JVM 类（File 由外部注入），方便单元测试。
 */
class BoardRepository(
    val filesDir: File,
    private val storeFile: File?,
    private val maxItems: Int
) {
    private val lock = Any()
    private val items = LinkedHashMap<String, BoardItem>()
    private var version = 0L

    /**
     * 新条目就绪回调（文本发送完成 / 文件接收完成）。
     *
     * 只在「新内容进入传输板」时触发，用于后台通知；resend 复制条目不触发。
     * 回调在持锁线程上执行，实现方不要做耗时操作。
     */
    @Volatile
    var onItemReady: ((BoardItem) -> Unit)? = null

    init {
        filesDir.mkdirs()
        load()
    }

    // ==================== 读取 ====================

    /** 返回当前版本号：任何结构变化都会 +1，客户端可据此判断是否需要刷新。 */
    fun currentVersion(): Long = synchronized(lock) { version }

    /** 返回全部条目，最新的在前。 */
    fun listNewestFirst(): List<BoardItem> = synchronized(lock) { items.values.toList().asReversed() }

    fun size(): Int = synchronized(lock) { items.size }

    fun get(id: String): BoardItem? = synchronized(lock) { items[id] }

    /** 文件条目在磁盘上的位置；文本条目或不存在时返回 null。 */
    fun fileOf(item: BoardItem): File? {
        if (!item.isFile || item.fileName.isEmpty()) return null
        return File(filesDir, item.fileName)
    }

    fun fileOf(id: String): File? = get(id)?.let { fileOf(it) }

    // ==================== 文本 ====================

    fun addText(text: String, sender: String, senderId: String): BoardItem = synchronized(lock) {
        val now = System.currentTimeMillis()
        val item = BoardItem(
            id = newId(),
            kind = BoardItem.KIND_TEXT,
            name = text.take(80),
            text = text,
            size = text.toByteArray(Charsets.UTF_8).size.toLong(),
            mime = "text/plain; charset=utf-8",
            sender = sender,
            senderId = senderId,
            createdAt = now,
            state = BoardItem.STATE_READY
        )
        putAndTrim(item)
        persistLocked()
        onItemReady?.invoke(item)
        item
    }

    // ==================== 文件（分片上传） ====================

    /**
     * 开始接收一个文件：创建占位条目 + 空文件，返回条目与磁盘文件。
     * 之后每次 PUT 分片调用 [appendReceived]，完成后调用 [completeFile]。
     */
    fun beginFile(
        name: String,
        size: Long,
        mime: String,
        sender: String,
        senderId: String
    ): Pair<BoardItem, File> = synchronized(lock) {
        val id = newId()
        val safeName = sanitizeFileName(name)
        val diskName = "${id}_$safeName"
        val file = File(filesDir, diskName)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(0))
        val item = BoardItem(
            id = id,
            kind = BoardItem.KIND_FILE,
            name = if (safeName.isBlank()) "file" else safeName,
            size = size,
            mime = mime.ifBlank { "application/octet-stream" },
            sender = sender,
            senderId = senderId,
            createdAt = System.currentTimeMillis(),
            state = BoardItem.STATE_RECEIVING,
            received = 0L,
            fileName = diskName
        )
        putAndTrim(item)
        persistLocked()
        item to file
    }

    /** 记录已接收字节数（不触发持久化）。返回最新的已接收字节数。 */
    fun appendReceived(id: String, bytes: Long): Long = synchronized(lock) {
        val item = items[id] ?: return 0L
        val received = (item.received + bytes).coerceAtMost(item.size)
        items[id] = item.copy(received = received)
        received
    }

    /** 标记文件接收完成。 */
    fun completeFile(id: String): BoardItem? = synchronized(lock) {
        val item = items[id] ?: return null
        if (!item.isFile) return item
        if (item.state == BoardItem.STATE_READY) return item
        val received = fileOf(item)
        val finalName = if (received != null && received.isFile) {
            // 接收时磁盘名带 id 前缀（避免并发冲突），完成后改回用户可读的原始文件名。
            val targetName = uniqueFileName(item.name)
            val target = File(filesDir, targetName)
            if (received.renameTo(target)) targetName else received.name
        } else {
            item.fileName
        }
        val completed = item.copy(
            state = BoardItem.STATE_READY,
            received = if (received != null && received.isFile) received.length() else item.size,
            fileName = finalName
        )
        items[id] = completed
        version++
        persistLocked()
        onItemReady?.invoke(completed)
        completed
    }

    /** 生成不冲突的最终文件名（同目录下重名时追加 (1)、(2)…）。 */
    private fun uniqueFileName(rawName: String): String {
        val base = sanitizeFileName(rawName)
        if (!File(filesDir, base).exists()) return base
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0 && base.length - dot <= 12) base.substring(0, dot) else base
        val ext = if (dot > 0 && base.length - dot <= 12) base.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$stem ($index)$ext"
            if (!File(filesDir, candidate).exists()) return candidate
            index++
        }
    }

    /** 接收失败/主动取消：删除占位条目与半成品文件。 */
    fun failFile(id: String): BoardItem? = synchronized(lock) {
        val item = items.remove(id) ?: return null
        fileOf(item)?.delete()
        version++
        persistLocked()
        item
    }

    // ==================== 再次发送 / 删除原文件 ====================

    /**
     * 再次发送一条已有条目：文本复制出一条新文本，文件复制出一份新文件条目。
     * 不触发 [onItemReady]（属于本地重复发送，不需要「收到通知」）。
     */
    fun resend(id: String, sender: String, senderId: String): BoardItem? = synchronized(lock) {
        val item = items[id] ?: return null
        val now = System.currentTimeMillis()
        if (item.isText) {
            val copy = BoardItem(
                id = newId(),
                kind = BoardItem.KIND_TEXT,
                name = item.name,
                text = item.text,
                size = item.size,
                mime = item.mime,
                sender = sender,
                senderId = senderId,
                createdAt = now,
                state = BoardItem.STATE_READY
            )
            putAndTrim(copy)
            persistLocked()
            return copy
        }
        val source = fileOf(item)
        if (source == null || !source.isFile) return null
        val copyName = uniqueFileName(item.name)
        val target = File(filesDir, copyName)
        try {
            source.copyTo(target, overwrite = false)
        } catch (t: Throwable) {
            LogUtils.w("BoardRepository", "再次发送拷贝文件失败: ${t.message}")
            return null
        }
        val copy = item.copy(
            id = newId(),
            createdAt = now,
            state = BoardItem.STATE_READY,
            received = target.length(),
            fileName = copyName,
            sender = sender,
            senderId = senderId
        )
        putAndTrim(copy)
        persistLocked()
        copy
    }

    /** 删除条目对应的原文件，但保留记录（记录标记为失败/文件已删除）。 */
    fun deleteFileOnly(id: String): Boolean = synchronized(lock) {
        val item = items[id] ?: return false
        if (!item.isFile) return false
        fileOf(item)?.delete()
        items[id] = item.copy(state = BoardItem.STATE_FAILED)
        version++
        persistLocked()
        true
    }

    // ==================== 删除 / 清空 ====================

    fun delete(id: String): Boolean = synchronized(lock) {
        val item = items.remove(id) ?: return false
        fileOf(item)?.delete()
        version++
        persistLocked()
        true
    }

    fun clear(): List<BoardItem> = synchronized(lock) {
        val removed = items.values.toList()
        items.clear()
        removed.forEach { fileOf(it)?.delete() }
        version++
        persistLocked()
        removed
    }

    // ==================== 内部 ====================

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    private fun putAndTrim(item: BoardItem) {
        items[item.id] = item
        version++
        while (items.size > maxItems) {
            val oldestKey = items.keys.firstOrNull() ?: break
            val oldest = items.remove(oldestKey) ?: break
            fileOf(oldest)?.delete()
        }
    }

    private fun sanitizeFileName(raw: String): String {
        var name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        name = name.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
        if (name.length > 120) {
            val dot = name.lastIndexOf('.')
            name = if (dot > 0 && name.length - dot <= 12) {
                name.substring(0, 100) + name.substring(dot)
            } else {
                name.take(120)
            }
        }
        return name.ifBlank { "file" }
    }

    private fun persistLocked() {
        val file = storeFile ?: return
        try {
            val payload = Json.stringify(
                Json.obj(
                    "version" to version,
                    "items" to items.values.map { it.toJson().toMutableMap().apply { put("fileName", it.fileName) } }
                )
            )
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(payload)
            if (!tmp.renameTo(file)) {
                file.writeText(payload)
                tmp.delete()
            }
        } catch (t: Throwable) {
            LogUtils.w("BoardRepository", "持久化传输板失败: ${t.message}")
        }
    }

    private fun load() {
        val file = storeFile ?: return
        if (!file.isFile) return
        try {
            val parsed = Json.parse(file.readText()) as? Map<*, *> ?: return
            @Suppress("UNCHECKED_CAST")
            val map = parsed as Map<String, Any?>
            version = (map["version"] as? Long) ?: 0L
            val rawItems = map["items"] as? List<*> ?: return
            for (raw in rawItems) {
                @Suppress("UNCHECKED_CAST")
                val json = raw as? Map<String, Any?> ?: continue
                val item = BoardItem.fromJson(json).copy(fileName = json["fileName"] as? String ?: "")
                if (item.id.isEmpty()) continue
                if (item.isFile && item.fileName.isNotEmpty() && !File(filesDir, item.fileName).isFile) {
                    // 文件已丢失：接收中的条目直接丢弃，已完成的保留记录但状态改为失败
                    if (item.isReceiving) continue
                    items[item.id] = item.copy(state = BoardItem.STATE_FAILED)
                } else {
                    items[item.id] = item
                }
            }
        } catch (t: Throwable) {
            LogUtils.w("BoardRepository", "读取传输板失败，忽略旧数据: ${t.message}")
        }
    }
}
