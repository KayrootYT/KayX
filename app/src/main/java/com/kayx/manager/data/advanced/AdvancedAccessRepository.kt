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
        // This check is called only after the user taps "Check root" in Settings, so it is
        // safe to let the configured root manager display its authorization prompt here.
        val rootResult = if (hasRootSu) RootShell.runRoot("id", 5_000) else RootShell.Res(-1, "", "su not found")
        val shizukuState = runCatching { ShizukuShell.state() }.getOrNull()
        AccessStatus(
            rootAvailable = hasRootSu,
            rootGranted = rootResult.code == 0 && isRootIdentity(rootResult.out),
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
