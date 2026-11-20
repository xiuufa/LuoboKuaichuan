package com.lobo.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.lobo.app.LuoboApp
import com.lobo.app.MainActivity
import com.lobo.app.R
import com.lobo.app.core.AppConfig
import com.lobo.app.core.ServerBus
import com.lobo.app.data.SettingsStore
import com.lobo.app.notify.TransferNotifier
import com.lobo.app.http.LuoboHttpServer
import com.lobo.app.notify.NotificationIcons
import com.lobo.app.util.LogUtils
import com.lobo.app.util.NetworkUtils
import com.lobo.app.util.StorageLocator

/**
 * 主机模式的前台服务：常驻通知 + CPU/WiFi 锁 + 局域网 HTTP 服务。
 * 网页端、APP 内其它页面、PC/Linux/py 脚本都通过它提供的 HTTP 接口访问传输板。
 */
class HttpServerService : Service() {

    private var server: LuoboHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var shutdownReceiver: BroadcastReceiver? = null

    /** 关闭流程只执行一次：多个关闭信号（停止按钮 / 系统关机 / onDestroy）重复到达时直接跳过。 */
    private val stopLock = Any()
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerShutdownReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSharing()
            return START_NOT_STICKY
        }
        startSharing()
        return START_STICKY
    }

    private fun startSharing() {
        synchronized(stopLock) { stopped = false }
        if (server?.isRunning() == true) {
            updateNotification()
            return
        }
        pushForeground(getString(R.string.notification_starting))
        acquireLocks()

        val app = application as LuoboApp
        val repository = app.repositoryFor(StorageLocator.mediaDir(this))
        repository.onItemReady = { item -> maybeNotifyReceived(app, item) }
        val httpServer = LuoboHttpServer(
            context = this,
            repository = repository,
            deviceId = SettingsStore.deviceId,
            deviceName = { SettingsStore.deviceName },
            storagePath = { StorageLocator.displayPath(this) }
        )
        when (val result = httpServer.startServer()) {
            is LuoboHttpServer.StartResult.Started -> {
                server = httpServer
                NetworkUtils.refresh()
                ServerBus.postRunning(AppConfig.HTTP_PORT, NetworkUtils.urlFor(AppConfig.HTTP_PORT))
                registerNetworkCallback()
                updateNotification()
                LogUtils.i("HttpServerService", "HTTP 服务已启动: ${NetworkUtils.urlFor(AppConfig.HTTP_PORT)}")
            }

            is LuoboHttpServer.StartResult.Failed -> {
                LogUtils.e("HttpServerService", "HTTP 服务启动失败: ${result.message}")
                ServerBus.postError(result.message)
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** 后台收到别人发来的内容时发通知（自己发的不通知、App 前台且亮屏也不通知）。 */
    private fun maybeNotifyReceived(app: LuoboApp, item: com.lobo.app.data.BoardItem) {
        if (item.senderId == SettingsStore.deviceId) return
        if (app.isForeground) {
            val power = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (power.isInteractive) return
        }
        if (item.isText) {
            TransferNotifier.notifyText(this, item)
        } else if (item.isReady) {
            TransferNotifier.notifyFile(this, item)
        }
    }

    private fun stopSharing() {
        synchronized(stopLock) {
            if (stopped) return
            stopped = true
        }
        unregisterNetworkCallback()
        server?.stopServer()
        server = null
        releaseLocks()
        ServerBus.postStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        unregisterShutdownReceiver()
        stopSharing()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 关机 / 重启等关闭信号

    private fun registerShutdownReceiver() {
        if (shutdownReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                LogUtils.i("HttpServerService", "收到系统关闭信号 ${intent?.action}，优雅停止共享服务")
                stopSharing()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SHUTDOWN)
            addAction(Intent.ACTION_REBOOT)
            addAction(ACTION_QUICKBOOT_POWEROFF)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            shutdownReceiver = receiver
        }.onFailure {
            LogUtils.w("HttpServerService", "注册关机广播失败: ${it.message}")
        }
    }

    private fun unregisterShutdownReceiver() {
        val receiver = shutdownReceiver ?: return
        shutdownReceiver = null
        runCatching { unregisterReceiver(receiver) }
    }

    // ---------------------------------------------------------------- 通知

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(AppConfig.NOTIFICATION_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                AppConfig.NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = getString(R.string.notification_channel_desc)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, HttpServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, AppConfig.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_luobo)
            .setColor(NotificationIcons.color(this))
            .setLargeIcon(NotificationIcons.largeIcon(this))
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun pushForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                AppConfig.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(AppConfig.NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val url = NetworkUtils.urlFor(AppConfig.HTTP_PORT)
        val text = if (url != null) {
            getString(R.string.notification_running, url)
        } else {
            getString(R.string.notification_running_no_wifi)
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(AppConfig.NOTIFICATION_ID, buildNotification(text))
    }

    // ---------------------------------------------------------------- 锁与网络

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(PowerManager::class.java)
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Luobo:HttpServer"
            )?.apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }
        } catch (t: Throwable) {
            LogUtils.w("HttpServerService", "获取 CPU 锁失败: ${t.message}")
        }
        try {
            val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wifiManager?.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "Luobo:WifiLock"
            )?.apply { setReferenceCounted(false); acquire() }
        } catch (t: Throwable) {
            LogUtils.w("HttpServerService", "获取 WiFi 锁失败: ${t.message}")
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                NetworkUtils.refresh()
                ServerBus.postRunning(AppConfig.HTTP_PORT, NetworkUtils.urlFor(AppConfig.HTTP_PORT))
                updateNotification()
            }

            override fun onLost(network: Network) {
                NetworkUtils.refresh()
                ServerBus.postRunning(AppConfig.HTTP_PORT, NetworkUtils.urlFor(AppConfig.HTTP_PORT))
                updateNotification()
            }
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (t: Throwable) {
            LogUtils.w("HttpServerService", "注册网络回调失败: ${t.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        }
    }

    companion object {
        const val ACTION_START = "com.lobo.app.action.START"
        const val ACTION_STOP = "com.lobo.app.action.STOP"

        private const val ACTION_QUICKBOOT_POWEROFF = "android.intent.action.QUICKBOOT_POWEROFF"

        fun start(context: Context) {
            val intent = Intent(context, HttpServerService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HttpServerService::class.java))
        }
    }
}
