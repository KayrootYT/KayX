package com.kayx.manager.data.advanced

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AccessMode { NORMAL, ROOT, SHIZUKU }

data class AccessStatus(
    val rootAvailable: Boolean = false,
    val rootGranted: Boolean = false,
    val shizukuAvailable: Boolean = false,
    val shizukuGranted: Boolean = false
)

class AdvancedAccessRepository {
    /**
     * Status checks must be passive. Merely opening Settings must never launch a su prompt.
     * Root authorization is requested by the actual user-initiated command through RootShell.
     */
    suspend fun checkAccessStatus(): AccessStatus = withContext(Dispatchers.IO) {
        val hasRootSu = checkSuBinary()
        val shizukuState = runCatching { ShizukuShell.state() }.getOrNull()
        AccessStatus(
            rootAvailable = hasRootSu,
            rootGranted = false,
            shizukuAvailable = shizukuState != null &&
                shizukuState != ShizukuShell.State.NOT_INSTALLED &&
                shizukuState != ShizukuShell.State.NOT_RUNNING &&
                shizukuState != ShizukuShell.State.OUTDATED,
            shizukuGranted = ShizukuShell.isReady()
        )
    }

    private fun checkSuBinary(): Boolean {
        val paths = arrayOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sd/xbin/su")
        return paths.any { File(it).let { f -> f.isFile && f.canExecute() } }
    }
}
