package com.lobo.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.util.UUID

/** 本地设置：设备名、设备 ID、上次连接的主机地址。 */
object SettingsStore {

    private const val PREFS_NAME = "luobo_settings"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_LAST_REMOTE = "last_remote_url"
    private const val KEY_QR_EXPANDED = "qr_expanded"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() } ?: defaultDeviceName()
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value.trim()).apply()

    val deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val created = UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.edit().putString(KEY_DEVICE_ID, created).apply()
            return created
        }

    var lastRemoteUrl: String?
        get() = prefs.getString(KEY_LAST_REMOTE, null)
        set(value) = prefs.edit().putString(KEY_LAST_REMOTE, value).apply()

    /** 本机共享卡片里的二维码面板是否展开。 */
    var qrExpanded: Boolean
        get() = prefs.getBoolean(KEY_QR_EXPANDED, true)
        set(value) = prefs.edit().putBoolean(KEY_QR_EXPANDED, value).apply()

    private fun defaultDeviceName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return model.ifBlank { "我的手机" }
    }
}
