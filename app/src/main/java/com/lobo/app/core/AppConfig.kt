package com.lobo.app.core

/** 全局常量：端口、分片大小、容量限制、存储目录名等。 */
object AppConfig {

    /** 局域网 HTTP 服务端口（网页 / APP / PC / Linux / Python 共用） */
    const val HTTP_PORT = 8080

    /** 分片上传块大小：1MB。断点续传、暂停/继续都以块为单位。 */
    const val CHUNK_SIZE = 1024 * 1024

    /** 传输板最多保留多少条记录，超出后淘汰最旧的（同时删除文件） */
    const val MAX_ITEMS = 1000

    /** 单条文本最大字节数 */
    const val MAX_TEXT_BYTES = 1024 * 1024

    /** 单文件最大字节数（16GB） */
    const val MAX_FILE_BYTES = 16L * 1024 * 1024 * 1024

    /** 超过该大小视为「大文件」：左滑删除需要二次确认 */
    const val BIG_FILE_THRESHOLD = 20L * 1024 * 1024

    /** 传输板持久化文件名（位于应用私有目录） */
    const val BOARD_FILE_NAME = "board.json"

    /** 共享目录名：优先 /storage/emulated/0/Luobo/ */
    const val STORAGE_DIR_NAME = "Luobo"

    /** 前台服务通知 */
    const val NOTIFICATION_CHANNEL_ID = "luobo_share"
    const val NOTIFICATION_ID = 1001

    /** 客户端轮询传输板的间隔（毫秒） */
    const val BOARD_POLL_INTERVAL_MS = 1500L

    /** APP 名称 / API 标识 */
    const val APP_NAME = "萝卜快传"
    const val API_NAME = "LuoboKuaichuan"
    const val API_VERSION = 1
    const val VERSION_NAME = "1.0.0"
}
