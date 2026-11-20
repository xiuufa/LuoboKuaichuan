package com.lobo.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.lobo.app.core.AppConfig
import com.lobo.app.data.BoardRepository
import com.lobo.app.data.SettingsStore
import com.lobo.app.util.LogUtils
import com.lobo.app.util.NetworkUtils
import com.lobo.app.util.StorageLocator
import java.io.File

class LuoboApp : Application(), Application.ActivityLifecycleCallbacks {

    lateinit var repository: BoardRepository
        private set

    /** 前台 Activity 计数，>0 表示 App 在前台（用于决定是否发「收到内容」通知）。 */
    @Volatile
    private var startedActivities = 0

    val isForeground: Boolean get() = startedActivities > 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        LogUtils.init(this)
        SettingsStore.init(this)
        NetworkUtils.refresh()
        repository = createRepository()
        registerActivityLifecycleCallbacks(this)
        LogUtils.i("LuoboApp", "萝卜快传启动，存储目录=${repository.filesDir.absolutePath}")
    }

    /** 权限变化后存储目录可能改变，需要时重建传输板。 */
    fun repositoryFor(storageDir: File): BoardRepository {
        if (!this::repository.isInitialized || repository.filesDir != storageDir) {
            repository = BoardRepository(
                storageDir,
                File(filesDir, AppConfig.BOARD_FILE_NAME),
                AppConfig.MAX_ITEMS
            )
        }
        return repository
    }

    private fun createRepository(): BoardRepository = BoardRepository(
        StorageLocator.mediaDir(this),
        File(filesDir, AppConfig.BOARD_FILE_NAME),
        AppConfig.MAX_ITEMS
    )

    // ---------------------------------------------------------------- 前后台

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        lateinit var instance: LuoboApp
            private set
    }
}
