package com.lobo.app.util

import android.content.Context
import android.os.Build
import android.os.Environment
import com.lobo.app.core.AppConfig
import java.io.File

/**
 * 文件保存位置：
 * - 已授予「所有文件访问权限」：/storage/emulated/0/Luobo/
 * - 否则降级到应用私有目录 filesDir/luobo_files（其它应用不易访问）
 *
 * 该目录同时是主机接收文件的落盘位置，也是 APP 内「打开/保存」的目标目录。
 */
object StorageLocator {

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    fun mediaDir(context: Context): File {
        val target = if (hasAllFilesAccess()) {
            File(Environment.getExternalStorageDirectory(), AppConfig.STORAGE_DIR_NAME)
        } else {
            File(context.filesDir, "luobo_files")
        }
        if (!target.isDirectory) target.mkdirs()
        return target
    }

    fun displayPath(context: Context): String = mediaDir(context).absolutePath
}
