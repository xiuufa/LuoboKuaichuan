package com.lobo.app.notify

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.lobo.app.R
import com.lobo.app.util.LogUtils

/**
 * 通知点击「复制」的落地端：静态广播接收器，直接写系统剪贴板。
 * 用 PendingIntent.getBroadcast 触发，即使 App 进程已被杀也能响应。
 */
class ClipboardReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TransferNotifier.ACTION_COPY_TEXT) return
        val text = intent.getStringExtra(TransferNotifier.EXTRA_TEXT) ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        LogUtils.d("ClipboardReceiver", "写入剪贴板 ${text.length} 字")
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
        runCatching {
            Toast.makeText(context, R.string.notification_text_copied, Toast.LENGTH_SHORT).show()
        }
    }
}
