package com.kayx.manager.feature.apps

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.kayx.manager.data.filesystem.ExternalOps
import com.kayx.manager.feature.tools.ToolScaffold
import com.kayx.manager.ui.glass.GlassCard
import com.kayx.manager.ui.theme.Accent
import com.kayx.manager.ui.theme.Fg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AppRow(
    val label: String, val pkg: String, val version: String, val apk: String, val splits: List<String>,
    val system: Boolean, val size: Long, val updated: Long
)

private fun loadApps(pm: PackageManager): List<AppRow> = pm.getInstalledApplications(0).map { ai ->
    val pi = runCatching { pm.getPackageInfo(ai.packageName, 0) }.getOrNull()
    AppRow(pm.getApplicationLabel(ai).toString(), ai.packageName, pi?.versionName ?: "?", ai.sourceDir,
        ai.splitSourceDirs?.toList().orEmpty(), (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
        File(ai.sourceDir).length(), pi?.lastUpdateTime ?: 0L)
}

private fun fmt(b: Long): String {
    if (b < 1024) return "$b B"
    val u = arrayOf("KB", "MB", "GB"); var v = b / 1024.0; var i = 0
    while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, u[i])
}

@Composable
private fun AppIcon(pm: PackageManager, pkg: String) {
    val bmp by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) { runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
    }
    val b = bmp
    if (b != null) Image(b, null, Modifier.size(40.dp)) else Icon(Icons.Default.Android, null, Modifier.size(40.dp), tint = Accent)
}

/** Installed apps: launch, app info, uninstall, extract/backup APK (incl. splits), open in APK inspector. */
@Composable
fun AppManagerScreen(onBack: () -> Unit, onOpenApk: (String) -> Unit) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var apps by remember { mutableStateOf<List<AppRow>?>(null) }\n    var reloadKey by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(0) }
    var sortBy by remember { mutableStateOf(0) }
    var sel by remember { mutableStateOf<AppRow?>(null) }
    LaunchedEffect(reloadKey) { apps = null; apps = withContext(Dispatchers.IO) { loadApps(pm) } }

    val shown = remember(apps, q, filter, sortBy) {
        apps.orEmpty().filter {
            (filter == 2 || (filter == 1) == it.system) &&
                (q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true))
        }.let { l ->
            when (sortBy) { 1 -> l.sortedByDescending { it.size }; 2 -> l.sortedByDescending { it.updated }; else -> l.sortedBy { it.label.lowercase() } }
        }
    }

    fun extract(a: AppRow) = scope.launch {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val safe = (a.label + "_" + a.version).replace(Regex("[^\\w.\\-]"), "_")
                val dir = File(Environment.getExternalStorageDirectory(), "KayX/apk").apply { mkdirs() }
                if (a.splits.isEmpty()) File(a.apk).copyTo(File(dir, "$safe.apk"), overwrite = true).name
                else {
                    val d = File(dir, safe).apply { mkdirs() }
                    (listOf(a.apk) + a.splits).forEach { File(it).copyTo(File(d, File(it).name), overwrite = true) }
                    "$safe/ (${a.splits.size + 1} files)"
                }
            }
        }
        snack.showSnackbar(r.fold({ "Saved to /KayX/apk/$it" }, { it.message ?: "Extract failed" }))
    }

    ToolScaffold("Apps (${shown.size})", onBack, snack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true, placeholder = { Text("Search name or package") }, trailingIcon = { IconButton(onClick = { reloadKey++ }) { Icon(Icons.Default.Refresh, "Refresh apps") } })
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("User", "System", "All").forEachIndexed { i, l -> FilterChip(filter == i, { filter = i }, { Text(l, maxLines = 1, softWrap = false) }) }
                Spacer(Modifier.width(10.dp))
                listOf("A–Z", "Size", "Date").forEachIndexed { i, l -> FilterChip(sortBy == i, { sortBy = i }, { Text(l, maxLines = 1, softWrap = false) }) }
            }
            if (apps == null) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Accent) }
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.pkg }) { a ->
                    GlassCard(Modifier.fillMaxWidth(), onClick = { sel = a }) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(pm, a.pkg)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(a.pkg, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = Fg.copy(.55f))
                                Text("v${a.version} · ${fmt(a.size)}" + if (a.splits.isNotEmpty()) " · split" else "", style = MaterialTheme.typography.labelSmall, color = Fg.copy(.55f))
                            }
                        }
                    }
                }
            }
        }
    }

    sel?.let { a ->
        val acts = listOf<Pair<String, () -> Unit>>(
            "Launch" to { pm.getLaunchIntentForPackage(a.pkg)?.let { ctx.startActivity(it) } ?: Toast.makeText(ctx, "No launcher activity", Toast.LENGTH_SHORT).show() },
            "App info (system)" to { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))) },
            "Inspect APK" to { onOpenApk(a.apk) },
            "Extract / backup APK" to { extract(a) },
            "Copy package name" to { ExternalOps.copyText(ctx, a.pkg) },
            "Uninstall" to { ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}"))) }
        )
        AlertDialog({ sel = null }, title = { Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                acts.forEach { (l, r) -> TextButton({ sel = null; runCatching { r() } }, Modifier.fillMaxWidth()) { Text(l) } }
            } },
            confirmButton = {}, dismissButton = { TextButton({ sel = null }) { Text("Close") } })
    }
}
