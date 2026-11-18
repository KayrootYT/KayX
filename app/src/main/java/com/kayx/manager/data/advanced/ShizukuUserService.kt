package com.kayx.manager.data.advanced

import android.content.Context
import com.kayx.manager.IUserService
import kotlin.system.exitProcess

/**
 * Instantiated by Shizuku in its own process (uid 2000 "shell" when started via ADB/wireless debugging, uid 0 when started
 * with root). Everything the Root explorer needs is a plain shell command, so one `exec` entry point is enough.
 */
class ShizukuUserService() : IUserService.Stub() {
    @Suppress("unused") constructor(context: Context) : this()

    override fun destroy() { exitProcess(0) }

    override fun exec(cmd: String?): String {
        if (cmd.isNullOrBlank()) return "-1\u0000\u0000empty command"
        val p = try { ProcessBuilder("sh", "-c", cmd).start() } catch (e: Exception) { return "-1\u0000\u0000" + (e.message ?: "cannot start sh") }
        var out = ""
        var err = ""
        val t1 = Thread { out = runCatching { p.inputStream.use { readCapped(it) } }.getOrDefault("") }
        val t2 = Thread { err = runCatching { p.errorStream.use { readCapped(it) } }.getOrDefault("") }
        t1.start(); t2.start()
        t1.join(TIMEOUT_MS); t2.join(1_000)
        if (t1.isAlive) { p.destroy(); return "-2\u0000$out\u0000Timed out" }
        val code = runCatching { p.waitFor() }.getOrDefault(-1)
        return "$code\u0000$out\u0000$err"
    }

    private fun readCapped(ins: java.io.InputStream): String {
        val bos = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            if (bos.size() < MAX_BYTES) bos.write(buf, 0, minOf(n, MAX_BYTES - bos.size())) // keep draining so the child never blocks
        }
        return bos.toString("UTF-8")
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val MAX_BYTES = 400_000 // Binder transactions are limited to ~1 MB total
    }
}
