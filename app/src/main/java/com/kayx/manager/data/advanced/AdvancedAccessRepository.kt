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

    suspend fun checkAccessStatus(): AccessStatus = withContext(Dispatchers.IO) {
        val hasRootSu = checkSuBinary()
        val rootGranted = if (hasRootSu) requestRootPermission() else false

        AccessStatus(
            rootAvailable = hasRootSu,
            rootGranted = rootGranted,
            shizukuAvailable = runCatching { ShizukuShell.state() }.getOrNull().let { it != null && it != ShizukuShell.State.NOT_INSTALLED && it != ShizukuShell.State.NOT_RUNNING && it != ShizukuShell.State.OUTDATED },
            shizukuGranted = ShizukuShell.isReady()
        )
    }

    private fun checkSuBinary(): Boolean {
        val paths = arrayOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sd/xbin/su")
        return paths.any { File(it).exists() }
    }

    private fun requestRootPermission(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c exit")
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
}
