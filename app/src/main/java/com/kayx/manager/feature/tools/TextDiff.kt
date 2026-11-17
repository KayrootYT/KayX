package com.kayx.manager.feature.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kayx.manager.ui.theme.Fg
import java.io.File
import java.io.FileInputStream

/** kind: ' ' unchanged, '-' only in file 1, '+' only in file 2, '~' folded run of unchanged lines. */
data class DiffRow(val kind: Char, val text: String, val aNo: Int, val bNo: Int)

sealed interface DiffOutcome {
    data class Lines(val rows: List<DiffRow>, val changes: Int) : DiffOutcome
    data class Unavailable(val reason: String) : DiffOutcome
}

private const val MAX_BYTES = 2L * 1024 * 1024
private const val CONTEXT = 3

internal fun diffLines(a: List<String>, b: List<String>): List<DiffRow>? {
    var pre = 0
    while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
    var suf = 0
    while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
    val n = a.size - pre - suf
    val m = b.size - pre - suf
    if ((n + 1).toLong() * (m + 1) > 4_000_000L) return null
    val w = m + 1
    val dp = IntArray((n + 1) * w)
    for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
        dp[i * w + j] = if (a[pre + i] == b[pre + j]) dp[(i + 1) * w + j + 1] + 1
        else maxOf(dp[(i + 1) * w + j], dp[i * w + j + 1])
    }
    val out = ArrayList<DiffRow>(a.size + b.size)
    for (k in 0 until pre) out.add(DiffRow(' ', a[k], k + 1, k + 1))
    var i = 0
    var j = 0
    while (i < n || j < m) {
        when {
            i < n && j < m && a[pre + i] == b[pre + j] -> { out.add(DiffRow(' ', a[pre + i], pre + i + 1, pre + j + 1)); i++; j++ }
            j >= m || (i < n && dp[(i + 1) * w + j] >= dp[i * w + j + 1]) -> { out.add(DiffRow('-', a[pre + i], pre + i + 1, 0)); i++ }
            else -> { out.add(DiffRow('+', b[pre + j], 0, pre + j + 1)); j++ }
        }
    }
    for (k in 0 until suf) out.add(DiffRow(' ', a[a.size - suf + k], a.size - suf + k + 1, b.size - suf + k + 1))
    return out
}

/** Keeps [ctx] unchanged lines around every change and folds the rest into '~' rows. */
internal fun foldContext(rows: List<DiffRow>, ctx: Int = CONTEXT): List<DiffRow> {
    val keep = BooleanArray(rows.size)
    rows.forEachIndexed { idx, r ->
        if (r.kind != ' ') for (k in (idx - ctx).coerceAtLeast(0)..(idx + ctx).coerceAtMost(rows.size - 1)) keep[k] = true
    }
    val out = ArrayList<DiffRow>()
    var skipped = 0
    rows.forEachIndexed { idx, r ->
        if (keep[idx]) {
            if (skipped > 0) { out.add(DiffRow('~', "$skipped unchanged lines", 0, 0)); skipped = 0 }
            out.add(r)
        } else skipped++
    }
    if (skipped > 0) out.add(DiffRow('~', "$skipped unchanged lines", 0, 0))
    return out
}

private fun looksBinary(f: File): Boolean = runCatching {
    FileInputStream(f).use { ins ->
        val buf = ByteArray(8192)
        val n = ins.read(buf)
        (0 until maxOf(n, 0)).any { buf[it].toInt() == 0 }
    }
}.getOrDefault(true)

fun buildTextDiff(a: File, b: File): DiffOutcome {
    if (!a.isFile || !b.isFile) return DiffOutcome.Unavailable("Select two files to see a text diff.")
    if (a.length() > MAX_BYTES || b.length() > MAX_BYTES) return DiffOutcome.Unavailable("Text diff is limited to files up to 2 MB.")
    if (looksBinary(a) || looksBinary(b)) return DiffOutcome.Unavailable("Binary files: use the Hex editor to inspect them.")
    return runCatching {
        val rows = diffLines(a.readLines(Charsets.UTF_8), b.readLines(Charsets.UTF_8))
            ?: return DiffOutcome.Unavailable("Too many differences for an inline view.")
        val changes = rows.count { it.kind == '+' || it.kind == '-' }
        DiffOutcome.Lines(foldContext(rows), changes)
    }.getOrElse { DiffOutcome.Unavailable(it.message ?: "Could not read the files.") }
}

@Composable
fun DiffLineRow(r: DiffRow) {
    val mono = FontFamily.Monospace
    if (r.kind == '~') {
        Text("⋯ ${r.text} ⋯", Modifier.fillMaxWidth().padding(vertical = 4.dp), fontFamily = mono, fontSize = 11.sp, color = Fg.copy(.45f))
        return
    }
    val bg = when (r.kind) { '+' -> Color(0x226BFFA0); '-' -> Color(0x22FF6B6B); else -> Color.Transparent }
    Row(Modifier.fillMaxWidth().background(bg), verticalAlignment = Alignment.Top) {
        Text(if (r.aNo > 0) r.aNo.toString() else "", Modifier.width(34.dp), fontFamily = mono, fontSize = 10.sp, color = Fg.copy(.4f))
        Text(if (r.bNo > 0) r.bNo.toString() else "", Modifier.width(34.dp), fontFamily = mono, fontSize = 10.sp, color = Fg.copy(.4f))
        Text(r.kind.toString() + " ", fontFamily = mono, fontSize = 11.sp, color = Fg.copy(.8f))
        Text(r.text, Modifier.weight(1f), fontFamily = mono, fontSize = 11.sp, style = MaterialTheme.typography.bodySmall, color = Fg.copy(.92f))
    }
}
