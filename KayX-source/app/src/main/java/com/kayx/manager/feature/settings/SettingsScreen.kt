package com.kayx.manager.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kayx.manager.data.advanced.AdvancedAccessRepository
import com.kayx.manager.data.settings.AccentChoice
import com.kayx.manager.data.settings.SettingsStore
import com.kayx.manager.data.settings.ThemeMode
import com.kayx.manager.domain.model.SearchMode
import com.kayx.manager.domain.model.SortBy
import com.kayx.manager.ui.glass.glassBackground
import com.kayx.manager.ui.ios.*
import com.kayx.manager.ui.theme.LocalPalette
import kotlinx.coroutines.launch

private val Blue = Color(0xFF007AFF)
private val Green = Color(0xFF34C759)
private val Orange = Color(0xFFFF9500)
private val Red = Color(0xFFFF3B30)
private val Gray = Color(0xFF8E8E93)
private val Purple = Color(0xFFAF52DE)
private val Indigo = Color(0xFF5856D6)
private val Teal = Color(0xFF30B0C7)

@Composable
fun SettingsScreen(storageGranted: Boolean, onManageStorage: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rootStatus by remember { mutableStateOf<String?>(null) }
    var checkingRoot by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val versionName = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "—" }

    Column(Modifier.fillMaxSize().glassBackground().statusBarsPadding()) {
        // iOS-style back button
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack).padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.ChevronLeft, null, tint = p.accent, modifier = Modifier.size(30.dp))
                Text("Back", color = p.accent, fontSize = 17.sp)
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            IosLargeTitle("Settings")

            // ---------------- Appearance ----------------
            IosSection("Appearance", "AMOLED uses a pure black background — saves battery on OLED screens.") {
                val modes = ThemeMode.values()
                IosSegmentedRow("Theme", modes.map { it.label }, modes.indexOf(SettingsStore.themeMode)) { SettingsStore.updateThemeMode(modes[it]) }
                IosDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("Accent colour", fontSize = 17.sp, color = p.fg)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        AccentChoice.values().forEach { c ->
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(if (p.dark) c.dark else c.light)
                                    .clickable { SettingsStore.updateAccent(c) },
                                Alignment.Center
                            ) {
                                if (c == SettingsStore.accent) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                IosDivider()
                IosSwitchRow("Liquid glass glow", SettingsStore.glowBackground, SettingsStore::updateGlow,
                    icon = Icons.Default.AutoAwesome, iconBg = Purple, subtitle = "Soft coloured backdrop (off in AMOLED)")
            }

            // ---------------- Storage ----------------
            IosSection("Storage access") {
                IosRow(
                    "All files access", icon = Icons.Default.Storage, iconBg = Blue, chevron = true, onClick = onManageStorage,
                    subtitle = if (storageGranted) "Granted" else "Not granted — tap to allow"
                )
            }

            // ---------------- File manager ----------------
            IosSection("File manager", "Defaults apply when you open the browser. You can still change them from the ⋮ menu.") {
                IosSwitchRow("Show hidden files", SettingsStore.showHidden, SettingsStore::updateShowHidden, Icons.Default.VisibilityOff, Gray)
                IosDivider(58.dp)
                IosSwitchRow("Dual pane by default", SettingsStore.dualPane, SettingsStore::updateDualPane, Icons.Default.ViewColumn, Indigo)
                IosDivider(58.dp)
                IosSwitchRow("Dual pane: swipe tabs (full screen)", SettingsStore.dualTabs, SettingsStore::updateDualTabs, Icons.Default.ViewColumn, Indigo)
                IosDivider(58.dp)
                IosSwitchRow("Confirm before delete", SettingsStore.confirmDelete, SettingsStore::updateConfirmDelete, Icons.Default.Delete, Red)
                IosDivider()
                val sorts = SortBy.values()
                IosSegmentedRow("Default sort", sorts.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, sorts.indexOf(SettingsStore.defaultSort)) {
                    SettingsStore.updateDefaultSort(sorts[it])
                }
            }

            // ---------------- Text editor ----------------
            IosSection("Text editor", "Backup copies are saved next to the file as name.ext.bak.") {
                IosRow("Font size", icon = Icons.Default.FormatSize, iconBg = Orange, subtitle = "${SettingsStore.editorFontSize} sp") {
                    IosStepper(SettingsStore.editorFontSize, SettingsStore::updateEditorFontSize, 10..24)
                }
                IosDivider(58.dp)
                IosSwitchRow("Syntax highlighting", SettingsStore.syntaxHighlight, SettingsStore::updateSyntaxHighlight, Icons.Default.Code, Indigo, subtitle = "XML, HTML, JSON, Smali, code (files up to 200 KB)")
                IosDivider(58.dp)
                IosSwitchRow("Create .bak on save", SettingsStore.editorBackup, SettingsStore::updateEditorBackup, Icons.Default.Save, Green)
            }

            // ---------------- Hex viewer ----------------
            IosSection("Hex viewer") {
                val opts = listOf(4, 8, 12)
                IosSegmentedRow("Bytes per row", opts.map { "$it" }, opts.indexOf(SettingsStore.hexBytesPerRow).coerceAtLeast(0)) {
                    SettingsStore.updateHexBytes(opts[it])
                }
            }

            // ---------------- Image viewer ----------------
            IosSection("Image viewer") {
                val opts = listOf(4f, 8f, 16f)
                IosSegmentedRow("Maximum zoom", opts.map { "${it.toInt()}×" }, opts.indexOf(SettingsStore.maxZoom).coerceAtLeast(1)) {
                    SettingsStore.updateMaxZoom(opts[it])
                }
            }

            // ---------------- HTML preview ----------------
            IosSection("HTML preview", "Opens .html files in a built-in viewer. Local files only; no network access is added.") {
                IosSwitchRow("Enable JavaScript", SettingsStore.htmlJavaScript, SettingsStore::updateHtmlJavaScript, Icons.Default.Language, Teal)
            }

            // ---------------- Search ----------------
            IosSection("File search", "Regex is case-insensitive. Filename search checks all nested folders and reuses a short-lived index for faster repeat searches.") {
                val modes = SearchMode.values()
                IosSegmentedRow("Filename matching", modes.map { it.label }, modes.indexOf(SettingsStore.searchMode)) { SettingsStore.updateSearchMode(modes[it]) }
                IosDivider()
                val day = 86_400_000L
                val ages = listOf(0L, day, 7 * day, 30 * day)
                IosSegmentedRow("Modified within", listOf("Any", "24 h", "7 days", "30 days"), ages.indexOf(SettingsStore.searchAgeMs).coerceAtLeast(0)) { SettingsStore.updateSearchAge(ages[it]) }
            }

            // ---------------- Bookmarks ----------------
            IosSection("Bookmarks", "Add bookmarks from the browser's ⋮ menu. Long-press one on Home to remove it.") {
                IosRow("Saved bookmarks", icon = Icons.Default.Bookmark, iconBg = Orange) {
                    Text("${SettingsStore.bookmarks.size}", fontSize = 17.sp, color = p.muted)
                }
                IosDivider()
                IosSegmentedRow("Add new bookmarks at", listOf("Bottom", "Top"), if (SettingsStore.bookmarksOnTop) 1 else 0) { SettingsStore.updateBookmarksOnTop(it == 1) }
            }

            // ---------------- Advanced access ----------------
            IosSection("Advanced access", "Checking root may show a permission prompt from your root manager.") {
                IosRow(
                    "Root access", icon = Icons.Default.Security, iconBg = Gray,
                    subtitle = rootStatus ?: "Tap to check",
                    onClick = {
                        if (!checkingRoot) {
                            checkingRoot = true
                            scope.launch {
                                val s = AdvancedAccessRepository().checkAccessStatus()
                                rootStatus = when {
                                    s.rootGranted -> "Granted"
                                    s.rootAvailable -> "su found, permission denied"
                                    else -> "Not available"
                                }
                                checkingRoot = false
                            }
                        }
                    }
                ) { if (checkingRoot) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = p.accent) }
                IosDivider(58.dp)
                IosRow("Shizuku", icon = Icons.Default.Bolt, iconBg = Teal, subtitle = "Not integrated yet")
            }

            // ---------------- About ----------------
            IosSection("About") {
                IosRow("KayX", icon = Icons.Default.FolderCopy, iconBg = Blue, subtitle = "Beyond File Management.") {
                    Text(versionName, fontSize = 17.sp, color = p.muted)
                }
                IosDivider(58.dp)
                IosRow("Open-source licences", icon = Icons.Default.Description, iconBg = Gray, chevron = true, onClick = { showLicenses = true })
            }

            IosSection {
                IosRow("Reset all settings", titleColor = Red, onClick = { confirmReset = true })
            }
        }
    }

    if (showLicenses) AlertDialog(
        onDismissRequest = { showLicenses = false },
        title = { Text("Open-source licences") },
        text = { Text("smali / baksmali 2.5.2\nBSD 3-Clause License\n© 2010 Ben Gruver\n\nFull text is in THIRD_PARTY_NOTICES.md.") },
        confirmButton = { TextButton({ showLicenses = false }) { Text("Close") } }
    )
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset all settings?") },
        text = { Text("Theme, accent and all tool preferences go back to their defaults.") },
        confirmButton = { TextButton({ SettingsStore.resetAll(); confirmReset = false }) { Text("Reset", color = Red) } },
        dismissButton = { TextButton({ confirmReset = false }) { Text("Cancel") } }
    )
}
