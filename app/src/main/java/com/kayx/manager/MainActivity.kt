package com.kayx.manager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kayx.manager.domain.model.Tool
import com.kayx.manager.feature.filebrowser.FileBrowserScreen
import com.kayx.manager.feature.tools.*
import com.kayx.manager.feature.home.HomeScreen
import com.kayx.manager.feature.settings.SettingsScreen
import com.kayx.manager.ui.theme.isKayXDark
import com.kayx.manager.ui.glass.GlassCard
import com.kayx.manager.ui.glass.glassBackground
import com.kayx.manager.ui.theme.KayXTheme

private val ToolStackSaver = listSaver<List<Pair<String, Tool>>, String>(
    save = { list -> list.map { "${it.second.name}|${it.first}" } },
    restore = { saved -> saved.mapNotNull { v -> runCatching { v.substringAfter('|') to Tool.valueOf(v.substringBefore('|')) }.getOrNull() } }
)

class MainActivity : ComponentActivity() {
    private val granted = mutableStateOf(false)
    private val legacyPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted.value = hasAccess() }
    private val safTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) com.kayx.manager.data.filesystem.SafRepository.persistTreePermission(this, uri)
    }

    private fun hasAccess() = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30)
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        else legacyPerm.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }

    private fun requestSafTree() { safTree.launch(null) }

    override fun onResume() { super.onResume(); granted.value = hasAccess() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val shortcutFile = intent?.getStringExtra("path")?.let { java.io.File(it) }?.takeIf { it.exists() }
        val startDir: String? = shortcutFile?.let { if (it.isDirectory) it.absolutePath else it.parent }
        if (shortcutFile != null && shortcutFile.isFile && savedInstanceState == null) window.decorView.post { com.kayx.manager.data.filesystem.ExternalOps.openWith(this, shortcutFile) }
        setContent {
            // Keep status/navigation bar icons readable in Light vs Dark/AMOLED.
            val dark = isKayXDark()
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            KayXTheme {
                // Saveable so rotation / dark-mode / font-scale changes don't drop the user back to Home.
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var path by rememberSaveable { mutableStateOf<String?>(startDir) }
                var stack by rememberSaveable(stateSaver = ToolStackSaver) { mutableStateOf(listOf<Pair<String, Tool>>()) }
                val push = { f: String, k: Tool -> stack = stack + (f to k) }
                if (!granted.value) Box(Modifier.fillMaxSize().glassBackground().padding(24.dp), Alignment.Center) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Storage access needed", style = MaterialTheme.typography.titleLarge)
                            Text("KayX only needs file access to browse and manage your files. It uses the internet only for features you open yourself, like the FTP client.")
                            Button(::requestAccess) { Text("Grant access") }
                            OutlinedButton(::requestSafTree) { Text("Choose SD card / SAF folder") }
                        }
                    }
                } else {
                    val p = path
                    val t = stack.lastOrNull()
                    if (t != null) {
                        val close = { stack = stack.dropLast(1) }
                        when (t.second) {
                            Tool.TEXT -> TextEditorPro(t.first, close, push)
                            Tool.HEX -> com.kayx.manager.feature.tools.HexEditorScreen(t.first, close)
                            Tool.IMAGE -> ImageViewerScreen(t.first, close)
                            Tool.ZIP -> ZipScreen(t.first, close, push)
                            Tool.CHECKSUM -> ChecksumScreen(t.first, close)
                            Tool.APK -> ApkInspectorScreen(t.first, close) { push(t.first, Tool.DEX) }
                            Tool.DEX -> DexInspectorScreen(t.first, close)
                            Tool.COMPARE -> { val ps = t.first.split('\u001f'); FileComparisonScreen(ps[0], ps.getOrElse(1) { ps[0] }, close) }
                            Tool.HTML -> HtmlPreviewScreen(t.first, close)
                            Tool.ZIPTEXT -> ZipEntryEditorScreen(t.first, close)
                            Tool.AXML -> AxmlViewerScreen(t.first, close)
                            Tool.APPS -> com.kayx.manager.feature.apps.AppManagerScreen(close) { push(it, Tool.APK) }
                            Tool.AUDIO -> AudioPlayerScreen(t.first, close)
                            Tool.VIDEO -> VideoPlayerScreen(t.first, close)
                            Tool.STREAM -> StreamPlayerScreen(close)
                            Tool.FONT -> FontViewerScreen(t.first, close)
                            Tool.RECYCLE -> RecycleBinScreen(close)
                            Tool.TERMINAL -> TerminalScreen(t.first, close)
                            Tool.SIGNKEY -> SigningKeyScreen(close)
                            Tool.SMALI -> SmaliRefScreen(close)
                            Tool.CATEGORY -> com.kayx.manager.feature.storage.CategoryScreen(t.first, close) { f, k -> push(f, k) }
                            Tool.TAGS -> com.kayx.manager.feature.storage.TagsScreen(close) { f, k -> push(f, k) }
                            Tool.PRIVATE -> com.kayx.manager.feature.storage.PrivateSpaceScreen(close) { dir -> stack = stack.dropLast(1); path = dir }
                            Tool.CLEANUP -> com.kayx.manager.feature.storage.CleanupScreen(close) { f, k -> push(f, k) }
                            Tool.ARSC -> ArscViewerScreen(t.first, close)
                            Tool.FTP -> FtpScreen(close)
                            Tool.ROOT -> RootExplorerScreen(close)
                        }
                    } else if (showSettings) {
                        SettingsScreen(storageGranted = granted.value, onManageStorage = ::requestAccess, onBack = { showSettings = false })
                    } else if (p != null) {
                        FileBrowserScreen(p, onExit = { path = null }, onOpenFile = { f, k -> push(f, k) }, onSettings = { showSettings = true })
                    } else com.kayx.manager.feature.main.MainShell(
                        storageGranted = granted.value, onManageStorage = ::requestAccess,
                        onOpen = { path = it },
                        onPush = { a, b -> push(a, b) },
                        onOpenExternal = { filePath -> com.kayx.manager.data.filesystem.ExternalOps.openWith(this, java.io.File(filePath)) }
                    )
                }
            }
        }
    }
}
