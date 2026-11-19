package com.kayx.manager.feature.home
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Send
import com.kayx.manager.ui.theme.Fg

import android.os.Environment
import androidx.compose.animation.animateContentSize
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.kayx.manager.domain.model.Tool
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import com.kayx.manager.data.filesystem.RecentFiles
import com.kayx.manager.data.settings.SettingsStore
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kayx.manager.ui.glass.GlassCard
import com.kayx.manager.ui.glass.glassBackground
import com.kayx.manager.ui.theme.Accent
import com.kayx.manager.ui.theme.Accent2
import com.kayx.manager.ui.theme.TextMuted
import java.io.File
import android.widget.Toast
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import com.kayx.manager.data.storage.Category
import com.kayx.manager.data.storage.PrivateSpace
import com.kayx.manager.data.storage.QuickAccess
import com.kayx.manager.data.storage.ScanFile
import com.kayx.manager.data.storage.StorageScanner
import com.kayx.manager.data.storage.TagStore
import com.kayx.manager.data.storage.formatBytes
import com.kayx.manager.data.storage.MediaCounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(onSettings: () -> Unit, onApps: () -> Unit = {}, onTool: (Tool) -> Unit = {}, onPush: (String, Tool) -> Unit = { _, _ -> }, onOpen: (String) -> Unit, onSearch: () -> Unit = {}) {
    val ctx = LocalContext.current
    val root = Environment.getExternalStorageDirectory()

    Column(
        Modifier.fillMaxSize().glassBackground().statusBarsPadding()
            .navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        // Compact manager-style toolbar
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlassCard(shape = RoundedCornerShape(13.dp), modifier = Modifier.size(42.dp)) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Icon(Icons.Default.FolderCopy, null, tint = Accent, modifier = Modifier.size(23.dp))
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("KayX", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("File manager", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
            IconButton(onClick = { onOpen(SettingsStore.homePath.ifEmpty { root.absolutePath }) }) { Icon(Icons.Default.FolderOpen, "Browse files", tint = Fg) }
        }

        // Full-screen local search entry, styled like a modern system file manager.
        GlassCard(
            Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(30.dp),
            onClick = onSearch
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, tint = TextMuted, modifier = Modifier.size(23.dp))
                Spacer(Modifier.width(12.dp))
                Text("Search files", Modifier.weight(1f), color = TextMuted, style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.Default.Tune, null, tint = TextMuted, modifier = Modifier.size(21.dp))
            }
        }

        // Local storage (collapsible)
        var localOpen by remember { mutableStateOf(true) }
        GlassCard(Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(17.dp)) {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().clickable { localOpen = !localOpen }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Local", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = TextMuted)
                    Icon(if (localOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = TextMuted)
                }
                if (localOpen) {
                    StorageRow("Root Directory", Icons.Default.PhoneAndroid, "/") { onOpen("/") }
                    StorageRow("Storage Directory", Icons.Default.SdCard, root.absolutePath) { onOpen(root.absolutePath) }
                }
            }
        }

        val marks = SettingsStore.bookmarks
        var removeMark by remember { mutableStateOf<String?>(null) }
        if (marks.isNotEmpty()) {
            Text("Bookmarks", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                marks.forEach { m ->
                    GlassCard(shape = RoundedCornerShape(13.dp), onClick = { onOpen(m) }, onLongClick = { removeMark = m }) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Bookmark, null, tint = Accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(m.substringAfterLast('/').ifEmpty { "/" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        removeMark?.let { m ->
            AlertDialog(
                onDismissRequest = { removeMark = null },
                title = { Text("Remove bookmark?") }, text = { Text(m) },
                confirmButton = { TextButton({ SettingsStore.removeBookmark(m); removeMark = null }) { Text("Remove", color = Color(0xFFFF6B6B)) } },
                dismissButton = { TextButton({ removeMark = null }) { Text("Cancel") } }
            )
        }
        // File categories with live counts
        val counts by produceState(emptyMap<String, Int?>()) {
            value = withContext(Dispatchers.IO) {
                val m = HashMap<String, Int?>()
                Category.values().forEach { m[it.name] = MediaCounts.category(ctx, it) }
                QuickAccess.targets.forEach { m["QA:" + it.name] = MediaCounts.folders(ctx, it.dirs) }
                m
            }
        }
        fun num(v: Int?) = v?.let { "%,d".format(it) } ?: "…"
        fun cnt(c: Category) = num(counts[c.name])
        val cats = listOf(
            CatTile("Images", Icons.Default.Image, Color(0xFF4A90F5), cnt(Category.IMAGES)) { onPush(Category.IMAGES.name, Tool.CATEGORY) },
            CatTile("Videos", Icons.Default.PlayArrow, Color(0xFFEF5B6B), cnt(Category.VIDEOS)) { onPush(Category.VIDEOS.name, Tool.CATEGORY) },
            CatTile("Audio", Icons.Default.GraphicEq, Color(0xFF5A5F6B), cnt(Category.AUDIO)) { onPush(Category.AUDIO.name, Tool.CATEGORY) },
            CatTile("Documents", Icons.Default.Description, Color(0xFFF5B82E), cnt(Category.DOCUMENTS)) { onPush(Category.DOCUMENTS.name, Tool.CATEGORY) },
            CatTile("APKs", Icons.Default.Android, Color(0xFF2ECC71), cnt(Category.APKS)) { onPush(Category.APKS.name, Tool.CATEGORY) },
            CatTile("Archives", Icons.Default.FolderZip, Color(0xFFF2B01E), cnt(Category.ARCHIVES)) { onPush(Category.ARCHIVES.name, Tool.CATEGORY) },
        )
        GlassCard(Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(17.dp)) {
            Column(Modifier.padding(vertical = 10.dp, horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                cats.chunked(4).forEach { row -> Row(Modifier.fillMaxWidth()) { row.forEach { CategoryTileView(it, Modifier.weight(1f)) }; repeat(4 - row.size) { Spacer(Modifier.weight(1f)) } } }
            }
        }

        // Quick access with counts
        var allQuick by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Quick access", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Row(Modifier.clickable { allQuick = !allQuick }, verticalAlignment = Alignment.CenterVertically) {
                Text(if (allQuick) "Less" else "All", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                Icon(if (allQuick) Icons.Default.KeyboardArrowUp else Icons.Default.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(18.dp))
            }
        }
        fun open(target: QuickAccess.Target) {
            val f = QuickAccess.firstExisting(target)
            if (f != null) onOpen(f.absolutePath) else Toast.makeText(ctx, "${target.name} folder not found", Toast.LENGTH_SHORT).show()
        }
        fun qCount(t: QuickAccess.Target) = num(counts["QA:" + t.name])
        fun target(n: String) = QuickAccess.targets.first { it.name == n }
        val quick = listOf(
            CatTile("WhatsApp", Icons.AutoMirrored.Filled.Chat, Color(0xFF25D366), qCount(target("WhatsApp"))) { open(target("WhatsApp")) },
            CatTile("Telegram", Icons.AutoMirrored.Filled.Send, Color(0xFF2AABEE), qCount(target("Telegram"))) { open(target("Telegram")) },
            CatTile("Screenshots", Icons.Default.Crop, Color(0xFF3D7BF0), qCount(target("Screenshots"))) { open(target("Screenshots")) },
            CatTile("Downloads", Icons.Default.Download, Color(0xFFE0A030), qCount(target("Downloads"))) { open(target("Downloads")) },
            CatTile("Recorder", Icons.Default.Mic, Color(0xFF6B7488), qCount(target("Recorder"))) { open(target("Recorder")) },
            CatTile("Bluetooth", Icons.Default.Bluetooth, Color(0xFF3D7BF0), qCount(target("Bluetooth"))) { open(target("Bluetooth")) }
        )
        val folders = listOf(
            "DCIM" to Environment.DIRECTORY_DCIM, "Documents" to Environment.DIRECTORY_DOCUMENTS, "Music" to Environment.DIRECTORY_MUSIC,
            "Pictures" to Environment.DIRECTORY_PICTURES, "Movies" to Environment.DIRECTORY_MOVIES
        ).map { (n, d) -> CatTile(n, Icons.Default.Folder, Color(0xFFE0A030), "") { val f = Environment.getExternalStoragePublicDirectory(d); f.mkdirs(); onOpen(f.absolutePath) } }
        GlassCard(Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(17.dp)) {
            Column(Modifier.padding(vertical = 10.dp, horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                (if (allQuick) quick + folders else quick).chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth()) { row.forEach { CategoryTileView(it, Modifier.weight(1f)) }; repeat(4 - row.size) { Spacer(Modifier.weight(1f)) } }
                }
            }
        }

        // Recent files
        val recent by produceState(emptyList<RecentFiles.Item>(), ctx) { value = withContext(Dispatchers.IO) { RecentFiles.query(ctx) } }
        if (recent.isNotEmpty()) {
            Text("Recent", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp)) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    recent.forEach { r ->
                        Row(
                            Modifier.fillMaxWidth().clickable { File(r.path).parent?.let(onOpen) }.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Description, null, tint = Accent, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    formatBytes(r.size) + "  ·  " + android.text.format.DateUtils.getRelativeTimeSpanString(r.modifiedMs, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS),
                                    style = MaterialTheme.typography.labelSmall, color = TextMuted
                                )
                            }
                        }
                    }
                }
            }
        }

    }
}

@Composable
private fun QuickTile(name: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(modifier, shape = RoundedCornerShape(15.dp), onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(10.dp))
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

private data class CatTile(val name: String, val icon: ImageVector, val color: Color, val count: String, val onClick: () -> Unit)

@Composable
private fun CategoryTileView(t: CatTile, modifier: Modifier) {
    Column(modifier.clickable(onClick = t.onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.size(44.dp).background(t.color, RoundedCornerShape(13.dp)), Alignment.Center) { Icon(t.icon, null, tint = Color.White, modifier = Modifier.size(25.dp)) }
        Text(t.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (t.count.isNotEmpty()) Text(t.count, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

@Composable
private fun StorageRow(title: String, icon: ImageVector, path: String, onClick: () -> Unit) {
    val stat = remember(path) { runCatching { StatFs(path) }.getOrNull() }
    val total = stat?.totalBytes ?: 0L
    val avail = stat?.availableBytes ?: 0L
    val used = (total - avail).coerceAtLeast(0L)
    val frac = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
    val systemRoot = path == "/" // read-only system partition: free space is always 0, so a usage bar would be misleading
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).background(Fg.copy(.08f), CircleShape), Alignment.Center) { Icon(icon, null, tint = Fg.copy(.85f)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (!systemRoot) Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator({ frac }, Modifier.weight(1f).height(4.dp), color = Accent, trackColor = Fg.copy(.18f))
                Text("  ${(frac * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = Accent)
            }
            Text(
                if (systemRoot) "${formatBytes(total)} system partition  ·  read-only" else "${formatBytes(used)} used, ${formatBytes(avail)} available",
                style = MaterialTheme.typography.labelSmall, color = TextMuted
            )
        }
    }
}
