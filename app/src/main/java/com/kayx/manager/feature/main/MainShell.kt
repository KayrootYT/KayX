package com.kayx.manager.feature.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kayx.manager.data.storage.StorageScanner
import com.kayx.manager.data.storage.formatBytes
import com.kayx.manager.domain.model.Tool
import com.kayx.manager.feature.home.HomeScreen
import com.kayx.manager.feature.search.SearchScreen
import com.kayx.manager.domain.model.FileItem
import com.kayx.manager.feature.settings.SettingsScreen
import com.kayx.manager.ui.glass.GlassCard
import com.kayx.manager.ui.glass.glassBackground
import com.kayx.manager.ui.theme.Accent
import com.kayx.manager.ui.theme.Fg
import com.kayx.manager.ui.theme.TextMuted

private enum class MainTab(val label: String, val icon: ImageVector) {
    FILES("Files", Icons.Default.Folder),
    STORAGE("Storage", Icons.Default.PieChart),
    TOOLS("Tools", Icons.Default.Build),
    SETTINGS("Settings", Icons.Default.Settings)
}

/**
 * Top-level app structure (bottom navigation):
 *  Files    - where your files are: storage, bookmarks, categories, quick folders
 *  Storage  - everything about space: cleanup, large/duplicate files, tags, private space, recycle bin
 *  Tools    - standalone tools: apps, signing keys, smali reference, terminal, streaming
 *  Settings - all preferences, grouped
 * File tools (text/hex/archive/APK/DEX/checksum...) open from a file via "Open with…".
 */
@Composable
fun MainShell(storageGranted: Boolean, onManageStorage: () -> Unit, onOpen: (String) -> Unit, onPush: (String, Tool) -> Unit, onOpenExternal: (String) -> Unit = {}) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = searchOpen) { searchOpen = false }
    BackHandler(enabled = !searchOpen && tab != 0) { tab = 0 }
    Column(Modifier.fillMaxSize().glassBackground()) {
        Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.navigationBars)) {
            AnimatedContent(
                targetState = MainTab.values()[tab],
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 14 }) togetherWith
                        (fadeOut(tween(150)) + slideOutHorizontally(tween(150)) { -it / 18 })
                },
                label = "main-tab-transition"
            ) { destination ->
            when (destination) {
                MainTab.FILES -> if (searchOpen) {
                    SearchScreen(
                        onBack = { searchOpen = false },
                        onOpenDirectory = onOpen,
                        onOpenFile = { item ->
                            val ext = item.name.substringAfterLast('.', "").lowercase()
                            val tool = when (ext) {
                                "apk" -> Tool.APK
                                "zip", "apks", "apkm", "xapk", "jar", "aar", "7z", "rar", "tar", "gz", "bz2", "xz" -> Tool.ZIP
                                "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic" -> Tool.IMAGE
                                "mp3", "m4a", "wav", "ogg", "opus", "flac", "aac" -> Tool.AUDIO
                                "mp4", "mkv", "webm", "mov", "avi", "3gp" -> Tool.VIDEO
                                "html", "htm" -> Tool.HTML
                                "ttf", "otf", "woff", "woff2" -> Tool.FONT
                                "dex" -> Tool.DEX
                                "txt", "md", "log", "json", "xml", "kt", "kts", "java", "js", "ts", "py", "sh", "css", "csv", "ini", "yaml", "yml", "toml", "properties" -> Tool.TEXT
                                else -> null
                            }
                            if (tool != null) onPush(item.path, tool) else onOpenExternal(item.path)
                        }
                    )
                } else HomeScreen(
                    onSettings = { tab = 3 },
                    onApps = { onPush("", Tool.APPS) },
                    onTool = { onPush("", it) },
                    onPush = onPush,
                    onOpen = onOpen,
                    onSearch = { searchOpen = true }
                )
                MainTab.STORAGE -> StorageTab(onPush)
                MainTab.TOOLS -> ToolsTab(onPush)
                MainTab.SETTINGS -> SettingsScreen(storageGranted, onManageStorage, onBack = { tab = 0 }, showBack = false)
            }
            }
        }
        if (!searchOpen) NavigationBar(containerColor = Color.Transparent) {
            MainTab.values().forEachIndexed { i, t ->
                NavigationBarItem(
                    selected = tab == i, onClick = { tab = i },
                    icon = { Icon(t.icon, t.label) }, label = { Text(t.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Accent, selectedTextColor = Accent, indicatorColor = Accent.copy(.15f),
                        unselectedIconColor = Fg.copy(.6f), unselectedTextColor = Fg.copy(.6f)
                    )
                )
            }
        }
    }
}

@Composable
private fun TabPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        content()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = TextMuted)

@Composable
private fun ToolRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp), onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
            Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
        }
    }
}

@Composable
private fun StorageTab(onPush: (String, Tool) -> Unit) {
    val a = StorageScanner.cachedAnalysis()
    val dev = remember { StorageScanner.deviceStorage() }
    TabPage("Storage") {
        dev?.let { (total, free) ->
            val used = (total - free).coerceAtLeast(0L)
            val frac = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
            GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp)) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Device storage", fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator({ frac }, Modifier.fillMaxWidth().height(6.dp), color = Accent, trackColor = Fg.copy(.18f))
                    Text("${formatBytes(used)} used of ${formatBytes(total)}  ·  ${formatBytes(free)} free", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                }
            }
        }
        SectionTitle("Clean up")
        ToolRow(
            "Space cleanup",
            a?.let { "${formatBytes((it.duplicates + it.oldVideos + it.whatsappJunk).distinctBy { f -> f.path }.sumOf { f -> f.size })} can be freed up" }
                ?: "Duplicates, old videos, junk and more",
            Icons.Default.CleaningServices
        ) { onPush("", Tool.CLEANUP) }
        ToolRow("Large files", a?.let { "${it.large.size} files · ${formatBytes(it.large.sumOf { f -> f.size })}" } ?: "100 MB and bigger", Icons.Default.Storage) { onPush("LARGE", Tool.CATEGORY) }
        ToolRow("Duplicate files", a?.let { "${it.duplicates.size} files · ${formatBytes(it.duplicateBytes)}" } ?: "Identical copies of the same file", Icons.Default.FileCopy) { onPush("DUPES", Tool.CATEGORY) }
        ToolRow("Old videos", a?.let { "${it.oldVideos.size} files · ${formatBytes(it.oldVideoBytes)}" } ?: "Videos older than 6 months", Icons.Default.VideoLibrary) { onPush("OLDVIDEOS", Tool.CATEGORY) }
        SectionTitle("Organise")
        ToolRow("Tags", "Group files with your own labels", Icons.AutoMirrored.Filled.Label) { onPush("", Tool.TAGS) }
        ToolRow("Private space", "PIN-protected folder", Icons.Default.Lock) { onPush("", Tool.PRIVATE) }
        SectionTitle("Deleted")
        ToolRow("Recycle bin", "Restore or permanently delete", Icons.Default.Delete) { onPush("", Tool.RECYCLE) }
    }
}

@Composable
private fun ToolsTab(onPush: (String, Tool) -> Unit) {
    TabPage("Tools") {
        SectionTitle("Apps & APK")
        ToolRow("Installed apps", "Browse apps on this device", Icons.Default.Apps) { onPush("", Tool.APPS) }
        ToolRow("Signing keys", "Create and manage APK signing keys", Icons.Default.VpnKey) { onPush("", Tool.SIGNKEY) }
        ToolRow("Smali reference", "Instruction look-up", Icons.Default.Code) { onPush("", Tool.SMALI) }
        SectionTitle("System")
        ToolRow("Terminal", "Run commands and scripts", Icons.Default.Terminal) { onPush("", Tool.TERMINAL) }
        ToolRow("Root explorer", "Browse system folders (root or Shizuku)", Icons.Default.AdminPanelSettings) { onPush("", Tool.ROOT) }
        SectionTitle("Network")
        ToolRow("FTP client", "Browse, download and upload over plain FTP", Icons.Default.Dns) { onPush("", Tool.FTP) }
        SectionTitle("Media")
        ToolRow("Streaming player", "Play media from a link", Icons.Default.Movie) { onPush("", Tool.STREAM) }
        SectionTitle("File tools")
        GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = Accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    "Text editor, Hex editor, Archive, APK, DEX, Resources (arsc), Checksums, Image, Font and more open from the file itself: tap a file in Files, then Open with…",
                    style = MaterialTheme.typography.bodySmall, color = TextMuted
                )
            }
        }
    }
}
