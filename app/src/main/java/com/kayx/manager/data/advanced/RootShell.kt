package com.kayx.manager.data.advanced

import com.kayx.manager.data.settings.PrivilegedBackend
import com.kayx.manager.data.settings.SettingsStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** User-invoked commands through su or the explicitly granted Shizuku backend. */
object RootShell {
    data class Res(val code: Int, val out: String, val err: String)
    data class Node(
        val name: String, val dir: Boolean, val link: Boolean, val perms: String,
        val owner: String, val size: Long, val date: String, val target: String?
    )

    private const val OUTPUT_LIMIT = 512 * 1024

    private fun drain(stream: InputStream, target: ByteArrayOutputStream) {
        val buffer = ByteArray(8192)
        stream.use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                synchronized(target) {
                    if (target.size() < OUTPUT_LIMIT) target.write(buffer, 0, minOf(n, OUTPUT_LIMIT - target.size()))
                }
            }
        }
    }

    fun run(cmd: String, timeoutMs: Long = 20_000): Res =
        if (SettingsStore.backend == PrivilegedBackend.SHIZUKU) ShizukuShell.run(cmd, timeoutMs)
        else runRoot(cmd, timeoutMs)

    /** Execute through su regardless of the currently selected privileged backend. */
    fun runRoot(cmd: String, timeoutMs: Long = 20_000): Res {
        require(cmd.isNotBlank()) { "Command is empty" }
        val timeout = timeoutMs.coerceIn(100L, 120_000L)
        val process = try { ProcessBuilder("su", "-c", cmd).start() }
        catch (e: Exception) { return Res(-1, "", e.message ?: "su is not available") }
        runCatching { process.outputStream.close() }
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val outThread = Thread({ runCatching { drain(process.inputStream, stdout) } }, "kayx-root-stdout").apply { isDaemon = true }
        val errThread = Thread({ runCatching { drain(process.errorStream, stderr) } }, "kayx-root-stderr").apply { isDaemon = true }
        outThread.start()
        errThread.start()
        return try {
            if (!process.waitFor(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                outThread.join(500)
                errThread.join(500)
                Res(-2, stdout.toString(Charsets.UTF_8.name()), "Timed out after ${timeout}ms")
            } else {
                outThread.join(1_000)
                errThread.join(1_000)
                Res(process.exitValue(), stdout.toString(Charsets.UTF_8.name()), stderr.toString(Charsets.UTF_8.name()))
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            Res(-2, stdout.toString(Charsets.UTF_8.name()), "Interrupted")
        } finally {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }
    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** True when the selected backend responds; Root mode additionally requires uid 0. */
    fun isRoot(): Boolean = runRoot("id", 5_000).let { it.code == 0 && isRootIdentity(it.out) }

    fun isBackendReady(): Boolean = if (SettingsStore.backend == PrivilegedBackend.SHIZUKU) ShizukuShell.isReady() else isRoot()

    private val lsLine = Regex(
        "^([a-z\\-][rwxsStTl\\-]{9})\\s+(?:\\d+\\s+)?(\\S+)\\s+(\\S+)\\s+(?:(\\d+)(?:,\\s*\\d+)?\\s+)?(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2})(?::\\d{2})?\\s+(.+)$"
    )

    fun list(path: String): Result<List<Node>> = runCatching {
        val r = run("ls -lA -- ${q(path)}")
        if (r.code != 0 && r.out.isBlank()) error(r.err.trim().ifBlank { "Cannot list $path (exit ${r.code})" })
        r.out.lineSequence().mapNotNull { line ->
            val m = lsLine.find(line.trimEnd()) ?: return@mapNotNull null
            val perms = m.groupValues[1]
            val raw = m.groupValues[7]
            val link = perms[0] == 'l'
            Node(
                name = if (link) raw.substringBefore(" -> ") else raw,
                dir = perms[0] == 'd', link = link, perms = perms,
                owner = m.groupValues[2] + ":" + m.groupValues[3],
                size = m.groupValues[4].toLongOrNull() ?: 0L,
                date = m.groupValues[5] + " " + m.groupValues[6],
                target = if (link) raw.substringAfter(" -> ", "").ifEmpty { null } else null
            )
        }.toList().sortedWith(compareBy({ !it.dir }, { it.name.lowercase() }))
    }

    /** Binary-safe bounded preview: encode bytes before the shell/Binder text transport. */
    fun head(path: String, bytes: Int = 65_536): Res {
        require(bytes in 1..128 * 1024) { "Read size must be between 1 byte and 128 KiB" }
        val result = run("head -c $bytes -- ${q(path)} | base64", 10_000)
        if (result.code != 0 || result.err.isNotBlank()) return result.copy(code = if (result.code == 0) -1 else result.code)
        return runCatching {
            val decoded = android.util.Base64.decode(result.out, android.util.Base64.DEFAULT)
            val raw = decoded.copyOf(minOf(bytes, decoded.size))
            result.copy(out = String(raw, Charsets.ISO_8859_1))
        }.getOrElse { Res(-1, "", "Could not decode binary preview: ${it.message}") }
    }

    /** Refuse catastrophic roots and critical mount roots; paths are still quoted and passed after --. */
    fun delete(path: String): Res {
        // Resolve symlinks before applying the deletion guard.
        val target = runCatching { File(path).canonicalFile }.getOrElse { return Res(-1, "", "Invalid path") }
        if (!isSafeRecursiveDeleteTarget(target.path)) return Res(-1, "", "Refusing to recursively delete a protected system root")
        return run("rm -rf -- ${q(target.path)}")
    }

    fun chmod(mode: String, path: String): Res {
        require(Regex("^[0-7]{3,4}$").matches(mode)) { "Mode must be 3-4 octal digits, e.g. 644" }
        return run("chmod $mode -- ${q(path)}")
    }

    fun copyOut(path: String, destDir: String): Res =
        run("mkdir -p -- ${q(destDir)} && cp -r -- ${q(path)} ${q(destDir)}/")
}


/** Parse the conventional id output from both toybox and toolbox implementations. */
internal fun isRootIdentity(output: String): Boolean = Regex("uid=0(?:\\(|,)").containsMatchIn(output)
/** Pure path guard shared with JVM tests. */
internal fun isSafeRecursiveDeleteTarget(path: String): Boolean {
    if (path.isBlank()) return false
    val target = runCatching { File(path).canonicalFile.toPath().toAbsolutePath().normalize().toString() }.getOrNull() ?: return false
    val protectedTrees = setOf("/system", "/vendor", "/product", "/data", "/proc", "/sys", "/dev", "/apex")
    val isDataTemp = target.startsWith("/data/local/tmp/")
    if (target == "/" || target == "/storage" || target == "/sdcard" || target == "/mnt/media_rw") return false
    if (!isDataTemp && protectedTrees.any { target == it || target.startsWith(it + "/") }) return false
    // Never allow a recursive delete of an entire mounted user volume.
    val parts = target.trim('/').split('/').filter { it.isNotEmpty() }
    if (parts.firstOrNull() == "storage" && parts.size <= 3) return false
    if (parts.take(2) == listOf("mnt", "media_rw") && parts.size <= 3) return false
    if (parts.take(3) == listOf("mnt", "user", "0") && parts.size <= 4) return false
    return target.isNotBlank()
}
