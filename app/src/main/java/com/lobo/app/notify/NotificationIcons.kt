package com.lobo.app.notify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import com.lobo.app.R

/** 通知里统一使用的应用图标与主题色（与桌面图标/品牌绿保持一致）。 */
object NotificationIcons {

    /** 把 mipmap 自适应图标绘制成 Bitmap，用作通知 largeIcon。 */
    fun largeIcon(context: Context): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher) ?: return null
        val size = (96 * context.resources.displayMetrics.density).toInt().coerceAtLeast(96)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
        }
    }

    fun color(context: Context): Int = ContextCompat.getColor(context, R.color.luobo_primary)
}
