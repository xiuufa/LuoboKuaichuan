package com.lobo.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** HTTP 共享服务的运行状态。 */
data class ServerStatus(
    val running: Boolean = false,
    val port: Int = AppConfig.HTTP_PORT,
    val url: String? = null,
    val error: String? = null
)

/** 服务状态总线：Service 写入，界面/其它模块订阅。 */
object ServerBus {

    private val _status = MutableStateFlow(ServerStatus())
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    fun post(status: ServerStatus) {
        _status.value = status
    }

    fun postRunning(port: Int, url: String?) {
        _status.value = ServerStatus(running = true, port = port, url = url)
    }

    fun postStopped() {
        _status.value = ServerStatus(running = false)
    }

    fun postError(message: String) {
        _status.value = ServerStatus(running = false, error = message)
    }
}
