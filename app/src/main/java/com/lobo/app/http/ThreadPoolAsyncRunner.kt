package com.lobo.app.http

import fi.iki.elonen.NanoHTTPD
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** 固定大小线程池执行器，避免 NanoHTTPD 默认线程模型在慢速上传时相互阻塞。 */
class ThreadPoolAsyncRunner(threads: Int = 8) : NanoHTTPD.AsyncRunner {

    private val counter = AtomicInteger(0)
    private val executor: ExecutorService = Executors.newFixedThreadPool(threads) { runnable ->
        Thread(runnable, "luobo-http-${counter.incrementAndGet()}").apply { isDaemon = true }
    }
    private val clients: MutableSet<NanoHTTPD.ClientHandler> =
        Collections.synchronizedSet(mutableSetOf())

    override fun closed(client: NanoHTTPD.ClientHandler) {
        clients.remove(client)
    }

    override fun exec(client: NanoHTTPD.ClientHandler) {
        clients.add(client)
        executor.submit { runCatching { client.run() } }
    }

    override fun closeAll() {
        val snapshot = synchronized(clients) { clients.toList() }
        snapshot.forEach { client -> runCatching { client.close() } }
        clients.clear()
        executor.shutdownNow()
    }
}
