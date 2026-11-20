package com.lobo.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lobo.app.MainActivity
import com.lobo.app.R
import com.lobo.app.data.BoardItem
import com.lobo.app.util.FormatUtils

/**
 * 后台收到内容时发通知：
 * - 文件：「收到文件，点击查看」→ 打开 App；
 * - 文字：「收到文字，点击复制」→ 通过静态广播把文字写进剪贴板（App 被杀也能响应）。
 */
object TransferNotifier {

    const val CHANNEL_ID = "luobo_transfer"
    const val ACTION_COPY_TEXT = "com.lobo.app.action.COPY_TEXT"
    const val EXTRA_TEXT = "com.lobo.app.extra.COPY_TEXT"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.app_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    fun notifyFile(context: Context, item: BoardItem) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_ITEM_ID, item.id)
        }
        val pending = PendingIntent.getActivity(
            context,
            idFor(item.id),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_luobo)
            .setColor(NotificationIcons.color(context))
            .setLargeIcon(NotificationIcons.largeIcon(context))
            .setContentTitle(context.getString(R.string.notification_file_title))
            .setContentText(context.getString(R.string.notification_file_text, item.name))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        context.getString(R.string.notification_file_text, item.name) +
                            "\n" + FormatUtils.formatSize(item.size)
                    )
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(idFor(item.id), notification)
    }

    fun notifyText(context: Context, item: BoardItem) {
        ensureChannel(context)
        val intent = Intent(context, ClipboardReceiver::class.java).apply {
            action = ACTION_COPY_TEXT
            putExtra(EXTRA_TEXT, item.text)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            idFor(item.id),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val preview = if (item.text.length > 60) item.text.take(60) + "…" else item.text
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_luobo)
            .setColor(NotificationIcons.color(context))
            .setLargeIcon(NotificationIcons.largeIcon(context))
            .setContentTitle(context.getString(R.string.notification_text_title))
            .setContentText(context.getString(R.string.notification_text_body))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(preview + "\n" + context.getString(R.string.notification_text_body))
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(idFor(item.id), notification)
    }

    /** 客户端模式自动保存完成后更新通知（复用同一条通知 id）。 */
    fun notifySaved(context: Context, item: BoardItem) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_ITEM_ID, item.id)
        }
        val pending = PendingIntent.getActivity(
            context,
            idFor(item.id),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_luobo)
            .setColor(NotificationIcons.color(context))
            .setLargeIcon(NotificationIcons.largeIcon(context))
            .setContentTitle(context.getString(R.string.notification_saved_title))
            .setContentText(context.getString(R.string.notification_saved_text, item.name))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.notification_saved_text, item.name) +
                        "\n" + FormatUtils.formatSize(item.size))
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(idFor(item.id), notification)
    }

    private fun idFor(itemId: String): Int = itemId.hashCode() and 0x7fffffff

    const val EXTRA_ITEM_ID = "com.lobo.app.extra.ITEM_ID"
}
