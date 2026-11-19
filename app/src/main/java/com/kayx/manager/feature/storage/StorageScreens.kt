package com.kayx.manager.feature.storage
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Label

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.layout.*
import java.util.TimeZone
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kayx.manager.data.filesystem.ExternalOps
import com.kayx.manager.data.filesystem.RecycleBin
import com.kayx.manager.data.settings.SettingsStore
import com.kayx.manager.data.storage.*
import com.kayx.manager.domain.model.Tool
import com.kayx.manager.feature.tools.ToolScaffold
import com.kayx.manager.ui.glass.GlassCard
import com.kayx.manager.ui.theme.Accent
import com.kayx.manager.ui.theme.Fg
import com.kayx.manager.ui.theme.TextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private val DANGER = Color(0xFFFF6B6B)

// ───────────────────────── Category / tag / cleanup file lists ─────────────────────────

private fun iconFor(c: Category?) = when (c) {
    Category.IMAGES -> Icons.Default.Image
    Category.VIDEOS -> Icons.Default.Movie
    Category.AUDIO -> Icons.Default.MusicNote
    Category.DOCUMENTS -> Icons.Default.Description
    Category.APKS -> Icons.Default.Android
    Category.ARCHIVES -> Icons.Default.Archive
    null -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private val textExts = setOf("txt", "md", "json", "xml", "html", "htm", "csv", "log", "js", "css", "java", "kt", "sh", "yml", "yaml", "ini", "conf", "properties")

/** Opens [f] in the matching built-in tool, or hands it to another app. */
private fun openScanFile(ctx: Context, f: ScanFile, onOpenTool: (String, Tool) -> Unit) {
    val ext = f.name.substringAfterLast('.', "").lowercase()
    when {
        f.category == Category.IMAGES && ext != "heif" -> onOpenTool(f.path, Tool.IMAGE)
        f.category == Category.VIDEOS -> onOpenTool(f.path, Tool.VIDEO)
        f.category == Category.AUDIO -> onOpenTool(f.path, Tool.AUDIO)
        f.category == Category.APKS && ext == "apk" -> onOpenTool(f.path, Tool.APK)
        f.category == Category.ARCHIVES && ext in setOf("zip", "jar") -> onOpenTool(f.path, Tool.ZIP)
        ext in textExts -> onOpenTool(f.path, Tool.TEXT)
        else -> ExternalOps.openWith(ctx, File(f.path))
    }
}

/**
 * Generic file list used by categories, tags, large files, duplicates and old videos.
 * [permanent] skips the recycle bin so deleting really frees space (cleanup lists).
 */
@Composable
fun FileListScreen(
    title: String, onBack: () -> Unit, onOpenTool: (String, Tool) -> Unit,
    permanent: Boolean = false, tagContext: String? = null, grid: Boolean = false, load: () -> List<ScanFile>
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var tick by remember { mutableStateOf(0) }
    var sortBy by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var tagDialog by remember { mutableStateOf(false) }
    val loaded by produceState<List<ScanFile>?>(null, tick) { value = withContext(Dispatchers.IO) { runCatching(load).getOrDefault(emptyList()) } }
    val list = remember(loaded, sortBy) {
        loaded?.let { l -> when (sortBy) { 1 -> l.sortedByDescending { it.size }; 2 -> l.sortedBy { it.name.lowercase() }; else -> l.sortedByDescending { it.modified } } }
    }
    val total = list?.sumOf { it.size } ?: 0L
    var scanned by remember { mutableStateOf(0) }
    LaunchedEffect(loaded) { while (loaded == null) { scanned = StorageScanner.progress.get(); kotlinx.coroutines.delay(300) } }
    val onTap: (ScanFile) -> Unit = { f -> val on = f.path in selected; if (selected.isNotEmpty()) selected = if (on) selected - f.path else selected + f.path else openScanFile(ctx, f, onOpenTool) }
    val onHold: (ScanFile) -> Unit = { f -> selected = if (f.path in selected) selected - f.path else selected + f.path }

    ToolScaffold(if (selected.isEmpty()) title else "${selected.size} selected", { if (selected.isNotEmpty()) { selected = emptySet() } else { onBack() } }, snack,
        actions = {
            if (selected.isNotEmpty()) IconButton({ selected = list.orEmpty().map { it.path }.toSet() }) { Icon(Icons.Default.SelectAll, "Select all") }
        },
        bottomBar = {
            if (selected.isNotEmpty()) GlassCard(Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton({ ExternalOps.share(ctx, selected.map(::File)) }) { Icon(Icons.Default.Share, "Share") }
                    IconButton({ tagDialog = true }) { Icon(Icons.AutoMirrored.Filled.Label, "Add tag") }
                    if (tagContext != null) IconButton({
                        selected.forEach { TagStore.remove(tagContext, it) }; selected = emptySet(); tick++
                    }) { Icon(Icons.Default.LabelOff, "Remove from tag") }
                    IconButton({ confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete", tint = DANGER) }
                }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (list == null) "Scanning… $scanned files" else "${list.size} files · ${formatBytes(total)}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = TextMuted)
                listOf("Date", "Size", "Name").forEachIndexed { i, l -> FilterChip(sortBy == i, { sortBy = i }, { Text(l) }) }
            }
            if (list == null) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Accent) }
            else if (list.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Nothing here\n" + StorageScanner.status, color = Fg.copy(.5f)) }
            else if (grid) ThumbGrid(list, selected, onTap, onHold)
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 90.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(list, key = { it.path }) { f ->
                    val on = f.path in selected
                    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(11.dp), tint = if (on) Accent else Fg,
                        onClick = { if (selected.isNotEmpty()) selected = if (on) selected - f.path else selected + f.path else openScanFile(ctx, f, onOpenTool) },
                        onLongClick = { selected = if (on) selected - f.path else selected + f.path }) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (on) Icon(Icons.Default.CheckCircle, null, tint = Accent, modifier = Modifier.size(44.dp).padding(10.dp)) else com.kayx.manager.ui.FileThumb(f.path)
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(formatBytes(f.size) + "  ·  " + com.kayx.manager.ui.formatDateTime(f.modified),
                                    style = MaterialTheme.typography.labelSmall, color = Fg.copy(.53f), maxLines = 1)
                                Text(f.path.substringBeforeLast('/').removePrefix(StorageScanner.externalRoot().absolutePath), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall, color = Fg.copy(.38f))
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        val chosen = list.orEmpty().filter { it.path in selected }
        AlertDialog({ confirmDelete = false }, title = { Text("Delete ${chosen.size} file(s)?") },
            text = { Text(formatBytes(chosen.sumOf { it.size }) + if (permanent || !SettingsStore.useRecycleBin) " will be permanently deleted." else " will be moved to the recycle bin.") },
            confirmButton = { TextButton({
                confirmDelete = false
                scope.launch {
                    val n = withContext(Dispatchers.IO) {
                        if (permanent || !SettingsStore.useRecycleBin) StorageScanner.delete(ctx, chosen).first
                        else { RecycleBin.trash(chosen.map { File(it.path) }); StorageScanner.invalidate(); chosen.size }
                    }
                    selected = emptySet(); tick++
                    snack.showSnackbar("Removed $n file(s)")
                }
            }) { Text("Delete", color = DANGER) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
    }
    if (tagDialog) AddTagDialog(selected.toList(), { tagDialog = false }) { selected = emptySet(); tagDialog = false; tick++; scope.launch { snack.showSnackbar("Tag added") } }
}

/** Pick an existing tag or type a new one and attach it to [paths]. */
@Composable
fun AddTagDialog(paths: List<String>, onDismiss: () -> Unit, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismiss, title = { Text("Add tag to ${paths.size} item(s)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Tag name") })
                TagStore.tags.keys.toList().chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { t -> AssistChip({ name = t }, { Text(t, maxLines = 1) }) }
                    }
                }
            }
        },
        confirmButton = { TextButton({ if (name.isNotBlank()) { TagStore.add(name, paths); onDone() } }) { Text("Add") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun CategoryScreen(id: String, onBack: () -> Unit, onOpenTool: (String, Tool) -> Unit) {
    when (id) {
        "LARGE" -> FileListScreen("Large files (100 MB+)", onBack, onOpenTool, permanent = true) { StorageScanner.analyze().large }
        "DUPES" -> FileListScreen("Duplicate files", onBack, onOpenTool, permanent = true) { StorageScanner.analyze().duplicates }
        "OLDVIDEOS" -> FileListScreen("Old videos", onBack, onOpenTool, permanent = true) { StorageScanner.analyze().oldVideos }
        else -> {
            val cat = runCatching { Category.valueOf(id) }.getOrNull()
            FileListScreen(cat?.label ?: id, onBack, onOpenTool, grid = cat == Category.IMAGES || cat == Category.VIDEOS) { StorageScanner.scan().filter { it.category == cat } }
        }
    }
}

@Composable
fun TagsScreen(onBack: () -> Unit, onOpenTool: (String, Tool) -> Unit) {
    var open by remember { mutableStateOf<String?>(null) }
    val tag = open
    if (tag != null) {
        FileListScreen(tag, { open = null }, onOpenTool, tagContext = tag) { TagStore.files(tag) }
        return
    }
    var removeTag by remember { mutableStateOf<String?>(null) }
    ToolScaffold("Tags", onBack) { pad ->
        val tags = TagStore.tags
        if (tags.isEmpty()) Box(Modifier.fillMaxSize().padding(pad).padding(24.dp), Alignment.Center) {
            Text("No tags yet.\nSelect files in the browser, a category or a cleanup list and tap the label icon to tag them.", textAlign = TextAlign.Center, color = Fg.copy(.6f))
        } else LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tags.keys.toList(), key = { it }) { t ->
                GlassCard(Modifier.fillMaxWidth(), onClick = { open = t }, onLongClick = { removeTag = t }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.Label, null, tint = Accent)
                        Spacer(Modifier.width(12.dp))
                        Text(t, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Text("${tags[t].orEmpty().size}", color = TextMuted)
                    }
                }
            }
        }
    }
    removeTag?.let { t ->
        AlertDialog({ removeTag = null }, title = { Text("Delete tag \"$t\"?") }, text = { Text("Your files are not deleted, only the tag.") },
            confirmButton = { TextButton({ TagStore.deleteTag(t); removeTag = null }) { Text("Delete", color = DANGER) } },
            dismissButton = { TextButton({ removeTag = null }) { Text("Cancel") } })
    }
}

// ───────────────────────── Private space ─────────────────────────

/** PIN gate for the private folder. [onUnlocked] receives the folder to open in the file browser. */
@Composable
fun PrivateSpaceScreen(onBack: () -> Unit, onUnlocked: (String) -> Unit) {
    val ctx = LocalContext.current
    val hasPin = remember { PrivateSpace.hasPin(ctx) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ToolScaffold("Private space", onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Lock, null, tint = Accent, modifier = Modifier.size(48.dp))
            Text(if (hasPin) "Enter your PIN" else "Create a PIN for your private space", style = MaterialTheme.typography.titleMedium)
            if (!hasPin) Text("Files are encrypted at rest with AES-256-GCM. The vault key is protected by your PIN and Android Keystore. If you forget the PIN, reset the device, or uninstall KayX, the files may be unrecoverable.",
                color = TextMuted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8); error = null }, singleLine = true, label = { Text("PIN (4–8 digits)") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            if (!hasPin) OutlinedTextField(confirm, { confirm = it.filter(Char::isDigit).take(8); error = null }, singleLine = true, label = { Text("Confirm PIN") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            error?.let { Text(it, color = DANGER) }
            Button({
                when {
                    pin.length < 4 -> error = "Use at least 4 digits"
                    !hasPin && pin != confirm -> error = "PINs do not match"
                    else -> scope.launch {
                        busy = true
                        val accepted = withContext(Dispatchers.IO) {
                            if (hasPin) PrivateSpace.verify(ctx, pin)
                            else PrivateSpace.setPin(ctx, pin).isSuccess
                        }
                        busy = false
                        if (accepted) onUnlocked(PrivateSpace.dir(ctx).absolutePath) else error = if (hasPin) "Wrong PIN or encrypted vault could not be opened" else "Could not create encrypted private vault"
                    }
                }
            }, enabled = !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (hasPin) "Unlock" else "Create PIN")
            }
        }
    }
}

// ───────────────────────── Space cleanup ─────────────────────────

private data class UnusedApp(val label: String, val pkg: String, val size: Long)

private fun hasUsageAccess(ctx: Context): Boolean = runCatching {
    val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    mode == AppOpsManager.MODE_ALLOWED
}.getOrDefault(false)

/** User apps that were not opened in the last 30 days (needs Usage access). */
private fun unusedApps(ctx: Context): List<UnusedApp> = runCatching {
    val pm = ctx.packageManager
    val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val now = System.currentTimeMillis(); val cutoff = now - 30L * 24 * 3600 * 1000
    val last = HashMap<String, Long>()
    usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, cutoff, now).orEmpty().forEach { last[it.packageName] = maxOf(last[it.packageName] ?: 0L, it.lastTimeUsed) }
    pm.getInstalledApplications(0).filter { ai ->
        (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && ai.packageName != ctx.packageName &&
            pm.getLaunchIntentForPackage(ai.packageName) != null && (last[ai.packageName] ?: 0L) < cutoff &&
            (runCatching { pm.getPackageInfo(ai.packageName, 0).firstInstallTime }.getOrDefault(0L)) < cutoff
    }.map { UnusedApp(pm.getApplicationLabel(it).toString(), it.packageName, File(it.sourceDir).length()) }.sortedByDescending { it.size }
}.getOrDefault(emptyList())

@Composable
fun CleanupScreen(onBack: () -> Unit, onOpenTool: (String, Tool) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var refresh by remember { mutableStateOf(0) }
    var freed by remember { mutableStateOf<Long?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, List<ScanFile>>?>(null) }
    var showUnused by remember { mutableStateOf(false) }
    var usageOk by remember { mutableStateOf(hasUsageAccess(ctx)) }
    val analysis by produceState<CleanupAnalysis?>(StorageScanner.cachedAnalysis(), refresh) { value = withContext(Dispatchers.IO) { StorageScanner.analyze() } }
    val unused by produceState(emptyList<UnusedApp>(), usageOk, refresh) { value = if (usageOk) withContext(Dispatchers.IO) { unusedApps(ctx) } else emptyList() }
    val a = analysis
    val device = remember(refresh) { StorageScanner.deviceStorage() }
    val reclaim = a?.let { (it.duplicates + it.oldVideos + it.whatsappJunk).distinctBy { f -> f.path } }.orEmpty()
    val unusedBytes = unused.sumOf { it.size }

    fun ask(title: String, files: List<ScanFile>) { if (files.isEmpty()) scope.launch { snack.showSnackbar("Nothing to clean up") } else confirm = title to files }

    ToolScaffold("Space cleanup", onBack, snack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GlassCard(Modifier.size(170.dp), shape = CircleShape) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (a == null) CircularProgressIndicator(color = Accent) else {
                        val t = reclaim.sumOf { it.size } + unusedBytes
                        val s = formatBytes(t).split(' ')
                        Text(s[0], fontSize = 36.sp, fontWeight = FontWeight.Bold)
                        Text(s.getOrElse(1) { "" }, color = TextMuted)
                    }
                }
            }
            Text("Can be freed up", color = TextMuted, style = MaterialTheme.typography.labelMedium)
            Button({ ask("Clean up all", reclaim) }, Modifier.fillMaxWidth(.7f), enabled = a != null) { Text("Clean up all") }

            GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp)) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Device storage", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                    if (device != null) {
                        Text("${formatBytes(device.second)} of ${formatBytes(device.first)} available", fontWeight = FontWeight.SemiBold)
                        LinearProgressIndicator({ ((device.first - device.second).toFloat() / device.first).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(6.dp), color = Accent, trackColor = Fg.copy(.10f))
                    }
                }
            }
            freed?.let { GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Done. ${formatBytes(it)} freed up.", Modifier.padding(15.dp)) } }

            Section("Recommended to clean up") {
                CleanRow(Icons.Default.FileCopy, "Duplicate files", a?.duplicateBytes, onClean = { ask("Duplicate files", a?.duplicates.orEmpty()) })
                CleanRow(Icons.Default.Movie, "Old videos", a?.oldVideoBytes, onClean = { ask("Old videos (6+ months)", a?.oldVideos.orEmpty()) })
                CleanRow(Icons.Default.Apps, "Unused apps", if (usageOk) unusedBytes else null, if (usageOk) "Clean up" else "Allow access",
                    onClean = { if (usageOk) { if (unused.isEmpty()) scope.launch { snack.showSnackbar("No unused apps") } else showUnused = true } else
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }.onFailure { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } })
            }

            Section("App cleanup") {
                CleanRow(Icons.AutoMirrored.Filled.Chat, "WhatsApp cleanup", a?.whatsappBytes, onClean = { ask("WhatsApp cache & status files", a?.whatsappJunk.orEmpty()) })
                CleanRow(Icons.Default.Layers, "App cleanup", null, "Open", onClean = { onOpenTool("", Tool.APPS) })
            }

            Section("File cleanup") {
                val tiles = listOf(
                    Triple("Image cleanup", Icons.Default.Image, "IMAGES"), Triple("Video cleanup", Icons.Default.Movie, "VIDEOS"), Triple("Audio cleanup", Icons.Default.MusicNote, "AUDIO"),
                    Triple("APKs", Icons.Default.Android, "APKS"), Triple("Large files", Icons.Default.Folder, "LARGE"), Triple("Duplicate files", Icons.Default.FileCopy, "DUPES")
                )
                tiles.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (name, icon, id) ->
                            val bytes = a?.let { an -> when (id) {
                                "LARGE" -> an.large.sumOf { it.size }; "DUPES" -> an.duplicateBytes
                                else -> an.of(Category.valueOf(id)).sumOf { it.size } } }
                            GlassCard(Modifier.weight(1f), shape = RoundedCornerShape(14.dp), onClick = { onOpenTool(id, Tool.CATEGORY) }) {
                                Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(icon, null, tint = Accent)
                                    Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                    Text(bytes?.let(::formatBytes) ?: "…", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { (title, files) ->
        AlertDialog({ confirm = null }, title = { Text(title) },
            text = { Text("Permanently delete ${files.size} file(s) (${formatBytes(files.sumOf { it.size })})? This cannot be undone." +
                if (title.startsWith("Duplicate") || title == "Clean up all") "\nOne original copy of each duplicate is kept." else "") },
            confirmButton = { TextButton({
                confirm = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { StorageScanner.delete(ctx, files) }
                    freed = r.second; refresh++
                    snack.showSnackbar("Deleted ${r.first} file(s)")
                }
            }) { Text("Delete", color = DANGER) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } })
    }
    if (showUnused) AlertDialog({ showUnused = false }, title = { Text("Unused apps (30+ days)") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            unused.forEach { u ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(u.label, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(formatBytes(u.size), style = MaterialTheme.typography.labelSmall, color = TextMuted) }
                    TextButton({ runCatching { ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${u.pkg}"))) } }) { Text("Uninstall", color = DANGER) }
                }
            }
        } },
        confirmButton = {}, dismissButton = { TextButton({ showUnused = false; usageOk = hasUsageAccess(ctx); refresh++ }) { Text("Close") } })
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = TextMuted)
        GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp)) { Column(Modifier.padding(vertical = 4.dp), content = content) }
    }
}

@Composable
private fun CleanRow(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, bytes: Long?, buttonText: String = "Clean up", onClean: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Medium)
            Text(bytes?.let(::formatBytes) ?: "—", style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
        FilledTonalButton(onClean, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) { Text(buttonText) }
    }
}


/** Gallery-style grid grouped by day, like the system file manager's Images and Videos tabs. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ThumbGrid(list: List<ScanFile>, selected: Set<String>, onTap: (ScanFile) -> Unit, onHold: (ScanFile) -> Unit) {
    val groups = remember(list) {
        val dayMs = 24L * 3600 * 1000
        val now = System.currentTimeMillis()
        val tz = java.util.TimeZone.getDefault()
        fun day(t: Long) = (t + tz.getOffset(t)) / dayMs
        val today = day(now)
        val fmt = DateFormat.getDateInstance(DateFormat.LONG)
        list.groupBy { day(it.modified) }.map { (d, files) ->
            (when (d) { today -> "Today"; today - 1 -> "Yesterday"; else -> fmt.format(Date(files.first().modified)) }) to files
        }
    }
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        androidx.compose.foundation.lazy.grid.GridCells.Fixed(4), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(4.dp, 0.dp, 4.dp, 90.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        groups.forEach { (title, files) ->
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "h$title") {
                Text(title, Modifier.padding(8.dp, 10.dp, 8.dp, 4.dp), style = MaterialTheme.typography.labelLarge, color = Fg.copy(.7f))
            }
            gridItems(files, key = { it.path }) { f ->
                val on = f.path in selected
                Box(Modifier.aspectRatio(1f).combinedClickable(onClick = { onTap(f) }, onLongClick = { onHold(f) })) {
                    com.kayx.manager.ui.FileThumb(f.path, Dp.Unspecified, Modifier.fillMaxSize(), square = true)
                    if (f.category == Category.VIDEOS) Icon(Icons.Default.PlayCircle, null, tint = Color.White.copy(.9f), modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(20.dp))
                    if (on) Box(Modifier.fillMaxSize().background(Accent.copy(.35f))) { Icon(Icons.Default.CheckCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp)) }
                }
            }
        }
    }
}
