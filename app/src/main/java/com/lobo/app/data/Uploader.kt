package com.lobo.app.data

import com.lobo.app.core.AppConfig
import com.lobo.app.util.LogUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * 发送队列：分片上传（默认 1MB/块），支持暂停 / 继续 / 取消 / 重试 / 拖动调整优先级。
 *
 * 上传走传输板 HTTP 接口，因此主机模式与客户端模式代码完全一致。
 */
class Uploader(
    private val scope: CoroutineScope,
    private val clientProvider: () -> BoardClient?,
    private val senderProvider: () -> Pair<String, String>,
    private val onBoardChanged: () -> Unit
) {

    enum class State { QUEUED, UPLOADING, PAUSED, FAILED, DONE, CANCELLED }

    /** 上传数据源：文件、content Uri 等。 */
    abstract class Source {
        abstract val name: String
        abstract val size: Long
        abstract val mime: String
        abstract fun open(): InputStream
    }

    data class Task(
        val id: String,
        val name: String,
        val size: Long,
        val mime: String,
        val source: Source,
        val state: State = State.QUEUED,
        val sent: Long = 0L,
        val uploadId: String? = null,
        val itemId: String? = null,
        val error: String? = null,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        val progress: Int
            get() = if (size <= 0L) 0 else ((sent * 1000L) / size).toInt().coerceIn(0, 1000)
    }

    private val list = mutableListOf<Task>()
    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) { workerLoop() }
    }

    // ---------------------------------------------------------------- 外部操作

    fun enqueue(source: Source): Task {
        val task = Task(
            id = UUID.randomUUID().toString().replace("-", "").take(12),
            name = source.name,
            size = source.size.coerceAtLeast(0L),
            mime = source.mime,
            source = source
        )
        synchronized(list) { list.add(task) }
        emit(force = true)
        wake.trySend(Unit)
        return task
    }

    fun enqueueAll(sources: List<Source>): List<Task> = sources.map { enqueue(it) }

    fun pause(taskId: String) {
        update(taskId) {
            if (it.state == State.UPLOADING || it.state == State.QUEUED) {
                it.copy(state = State.PAUSED)
            } else {
                it
            }
        }
    }

    fun resume(taskId: String) {
        update(taskId) {
            if (it.state == State.PAUSED || it.state == State.FAILED) {
                it.copy(state = State.QUEUED, error = null)
            } else {
                it
            }
        }
        wake.trySend(Unit)
    }

    fun retry(taskId: String) = resume(taskId)

    /** 取消并删除服务端对应的未完成条目。 */
    fun cancel(taskId: String) {
        val task = current(taskId) ?: return
        update(taskId) { it.copy(state = State.CANCELLED) }
        val uploadId = task.uploadId
        val client = clientProvider()
        if (uploadId != null && client != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { client.cancelUpload(uploadId) }
                onBoardChanged()
            }
        }
        remove(taskId)
    }

    /** 从队列移除（失败条目「删除」用）。 */
    fun remove(taskId: String) {
        synchronized(list) { list.removeAll { it.id == taskId } }
        emit(force = true)
    }

    fun clearFinished() {
        synchronized(list) { list.removeAll { it.state == State.DONE || it.state == State.CANCELLED } }
        emit(force = true)
    }

    fun pendingCount(): Int = synchronized(list) {
        list.count { it.state == State.QUEUED || it.state == State.UPLOADING || it.state == State.PAUSED }
    }

    // ---------------------------------------------------------------- 工作线程

    private suspend fun workerLoop() {
        while (true) {
            val nextId = synchronized(list) { list.firstOrNull { it.state == State.QUEUED }?.id }
            if (nextId == null) {
                wake.receive()
                continue
            }
            runTask(nextId)
        }
    }

    private suspend fun runTask(taskId: String) {
        val task = current(taskId) ?: return
        if (task.state != State.QUEUED) return
        val client = clientProvider()
        if (client == null) {
            update(taskId) { it.copy(state = State.FAILED, error = "未连接主机") }
            return
        }
        update(taskId) { it.copy(state = State.UPLOADING, error = null) }
        var stream: InputStream? = null
        try {
            var uploadId = task.uploadId
            var sent = task.sent
            if (uploadId == null || sent == 0L) {
                val (sender, senderId) = senderProvider()
                val ticket = client.initUpload(task.name, task.size, task.mime, sender, senderId)
                uploadId = ticket.uploadId
                sent = ticket.offset
                update(taskId) {
                    it.copy(uploadId = ticket.uploadId, itemId = ticket.itemId, sent = sent)
                }
                onBoardChanged()
            }

            if (task.size > 0L) {
                stream = task.source.open()
                skipFully(stream, sent)
                val buffer = ByteArray(AppConfig.CHUNK_SIZE)
                while (sent < task.size) {
                    val state = currentState(taskId)
                    if (state == State.PAUSED || state == State.CANCELLED) return
                    val toRead = minOf(buffer.size.toLong(), task.size - sent).toInt()
                    val input = stream ?: throw IOException("上传流已关闭")
                    val read = readFully(input, buffer, toRead)
                    if (read <= 0) throw IOException("读取源文件失败")
                    val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                    val result = try {
                        client.putChunk(uploadId, sent, chunk)
                    } catch (e: OffsetMismatchException) {
                        sent = e.offset
                        runCatching { stream?.close() }
                        stream = task.source.open()
                        skipFully(stream, sent)
                        continue
                    }
                    sent = result.received
                    update(taskId) { it.copy(sent = sent) }
                    if (result.completed) break
                }
                val state = currentState(taskId)
                if (state == State.PAUSED || state == State.CANCELLED) return
            }

            val item = client.completeUpload(uploadId)
            update(taskId) { it.copy(state = State.DONE, sent = it.size, itemId = item.id) }
            onBoardChanged()
            synchronized(list) { list.removeAll { it.id == taskId } }
            emit(force = true)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            LogUtils.w("Uploader", "上传失败 ${task.name}: ${t.message}", t)
            if (t is BoardException && t.statusCode == 404 && task.uploadId != null) {
                update(taskId) {
                    it.copy(uploadId = null, itemId = null, sent = 0, state = State.QUEUED)
                }
            } else {
                update(taskId) { it.copy(state = State.FAILED, error = t.message ?: "上传失败") }
            }
        } finally {
            runCatching { stream?.close() }
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun current(taskId: String): Task? = synchronized(list) { list.firstOrNull { it.id == taskId } }

    private fun currentState(taskId: String): State? = synchronized(list) {
        list.firstOrNull { it.id == taskId }?.state
    }

    private fun update(taskId: String, transform: (Task) -> Task) {
        var stateChanged = false
        synchronized(list) {
            val index = list.indexOfFirst { it.id == taskId }
            if (index < 0) return
            val old = list[index]
            val updated = transform(old)
            stateChanged = old.state != updated.state
            list[index] = updated
        }
        emit(force = stateChanged)
    }

    /** 进度更新节流：最多约 12 次/秒，状态变化时立即刷新。 */
    private var lastEmitAt = 0L

    private fun emit(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastEmitAt < 80L) return
        lastEmitAt = now
        _tasks.value = synchronized(list) { list.toList() }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (input.read() < 0) {
                throw IOException("源文件比预期短")
            } else {
                remaining -= 1
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Int {
        var total = 0
        while (total < length) {
            val read = input.read(buffer, total, length - total)
            if (read < 0) break
            total += read
        }
        return total
    }
}
