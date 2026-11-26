package com.kayx.manager

import android.app.Application
import com.kayx.manager.data.settings.SettingsStore
import com.kayx.manager.ui.Thumbs

class KayXApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        com.kayx.manager.data.advanced.ShizukuShell.init(this)
        com.kayx.manager.data.storage.TagStore.init(this)
        com.kayx.manager.data.storage.StorageScanner.init(this)
        com.kayx.manager.data.filesystem.RecycleBinPurgeWorker.schedule(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Thumbs.trimMemory(level)
    }
}
