package com.kayx.manager.data.advanced

import android.content.Context
import android.util.Base64
import com.kayx.manager.IUserService
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Shizuku-hosted shell. The user must explicitly grant KayX Shizuku permission first. */
class ShizukuUserService() : IUserService.Stub() {
    @Suppress("unused") constructor(context: Context) : this()

    override fun destroy() { exitProcess(0) }

    override fun exec(cmd: String?, timeoutMs: Long): String {
        if (cmd.isNullOrBlank()) return response(-1, ByteArray(0), "empty command".toByteArray())
        val timeout = timeoutMs.coerceIn(100L, 120_000L)
        val process = try { ProcessBuilder("sh", "-c", cmd).start() }
        catch (e: Exception) { return response(-1, ByteArray(0), (e.message ?: "cannot start sh").toByteArray()) }
        runCatching { process.outputStream.close() }
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val outThread = Thread({ runCatching { drain(process.inputStream, stdout) } }, "kayx-shizuku-out").apply { isDaemon = true }
        val errThread = Thread({ runCatching { drain(process.errorStream, stderr) } }, "kayx-shizuku-err").apply { isDaemon = true }
        outThread.start()
        errThread.start()
        return try {
            if (!process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                outThread.join(500)
                errThread.join(500)
                response(-2, stdout.toByteArray(), "Timed out after ${timeout}ms".toByteArray())
            } else {
                outThread.join(1_000)
                errThread.join(1_000)
                response(process.exitValue(), stdout.toByteArray(), stderr.toByteArray())
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            response(-2, stdout.toByteArray(), "Interrupted".toByteArray())
        } finally {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }

    private fun drain(input: InputStream, output: ByteArrayOutputStream) {
        val buffer = ByteArray(8192)
        input.use {
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                synchronized(output) {
                    if (output.size() < MAX_STREAM_BYTES) output.write(buffer, 0, minOf(n, MAX_STREAM_BYTES - output.size()))
                }
            }
        }
    }

    private fun response(code: Int, out: ByteArray, err: ByteArray): String =
        code.toString() + "\n" +
            Base64.encodeToString(out, Base64.NO_WRAP) + "\n" +
            Base64.encodeToString(err, Base64.NO_WRAP)

    private companion object {
        // 192 KiB raw per stream => about 512 KiB total Base64 payload, below Binder's transaction cap.
        const val MAX_STREAM_BYTES = 192 * 1024
    }
}