package com.kayx.manager.data.advanced

import com.kayx.manager.data.settings.PrivilegedBackend
import com.kayx.manager.data.settings.SettingsStore

/** Runs commands through `su -c` (Root backend) or a Shizuku user service (Shizuku backend). Every path is single-quoted. */
object RootShell {
    data class Res(val code: Int, val out: String, val err: String)
    data class Node(
        val name: String, val dir: Boolean, val link: Boolean, val perms: String,
        val owner: String, val size: Long, val date: String, val target: String?
    )

    fun run(cmd: String, timeoutMs: Long = 20_000): Res {
        if (SettingsStore.backend == PrivilegedBackend.SHIZUKU) return ShizukuShell.run(cmd, timeoutMs)
        val p = try { ProcessBuilder("su", "-c", cmd).start() } catch (e: Exception) { return Res(-1, "", e.message ?: "su is not available") }
        var out = ""
        var err = ""
        val t1 = Thread { out = runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("") }
        val t2 = Thread { err = runCatching { p.errorStream.bufferedReader().readText() }.getOrDefault("") }
        t1.start(); t2.start()
        t1.join(timeoutMs); t2.join(1_000)
        if (t1.isAlive) { p.destroy(); return Res(-2, out, "Timed out") }
        val code = runCatching { p.waitFor() }.getOrDefault(-1)
        return Res(code, out, err)
    }

    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** True when the selected backend works: uid 0 for Root, or any working shell (uid 2000 or 0) for Shizuku. */
    fun isRoot(): Boolean = run("id").let {
        it.code == 0 && (SettingsStore.backend == PrivilegedBackend.SHIZUKU || it.out.contains("uid=0"))
    }

    private val lsLine = Regex(
        "^([a-z\\-][rwxsStTl\\-]{9})\\s+(?:\\d+\\s+)?(\\S+)\\s+(\\S+)\\s+(?:(\\d+)(?:,\\s*\\d+)?\\s+)?(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2})(?::\\d{2})?\\s+(.+)$"
    )

    fun list(path: String): Result<List<Node>> = runCatching {
        val r = run("ls -lA ${q(path)}")
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

    fun head(path: String, bytes: Int = 65_536): Res = run("head -c $bytes ${q(path)}")
    fun delete(path: String): Res = run("rm -rf ${q(path)}")
    fun chmod(mode: String, path: String): Res {
        require(Regex("^[0-7]{3,4}$").matches(mode)) { "Mode must be 3-4 octal digits, e.g. 644" }
        return run("chmod $mode ${q(path)}")
    }
    fun copyOut(path: String, destDir: String): Res = run("mkdir -p ${q(destDir)} && cp -r ${q(path)} ${q(destDir)}/")
}
