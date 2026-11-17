package com.kayx.manager.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.kayx.manager.domain.model.SearchMode
import com.kayx.manager.domain.model.SortBy

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark"), AMOLED("AMOLED") }

/** Animated / static backdrop behind every screen. */
enum class BgStyle(val label: String) { OFF("Off"), GLOW("Glow"), AURORA("Aurora"), ORBS("Orbs"), CUBES("Cubes") }

/** Which privileged backend the Root explorer uses. */
enum class PrivilegedBackend(val label: String) { ROOT("Root"), SHIZUKU("Shizuku") }

enum class AccentChoice(val light: Color, val dark: Color) {
    BLUE(Color(0xFF007AFF), Color(0xFF76A9FF)),
    PURPLE(Color(0xFF7B5CF0), Color(0xFFA48BFF)),
    GREEN(Color(0xFF28A745), Color(0xFF5AE07F)),
    ORANGE(Color(0xFFFF9500), Color(0xFFFFB340)),
    PINK(Color(0xFFFF2D55), Color(0xFFFF6B8A))
}

/** App-wide settings. Compose-observable and persisted in SharedPreferences. Call [init] from Application.onCreate. */
object SettingsStore {
    private var prefs: SharedPreferences? = null

    // Appearance
    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
        private set
    var accent by mutableStateOf(AccentChoice.BLUE)
        private set
    var bgStyle by mutableStateOf(BgStyle.GLOW)
        private set
    /** Layered gradient + highlight + depth shadow on file/folder type icons. */
    var icons3d by mutableStateOf(true)
        private set
    /** Folder icon follows what is inside (photos, music, video, documents …). */
    var folderIcons by mutableStateOf(true)
        private set
    var backend by mutableStateOf(PrivilegedBackend.ROOT)
        private set

    // File manager
    var showHidden by mutableStateOf(false)
        private set
    var defaultSort by mutableStateOf(SortBy.NAME)
        private set
    var dualPane by mutableStateOf(false)
        private set
    /** Dual pane layout: false = both panes visible side by side, true = full-screen panes you swipe between. */
    var dualTabs by mutableStateOf(false)
        private set
    var confirmDelete by mutableStateOf(true)
        private set

    // Tools
    var editorFontSize by mutableStateOf(13)
        private set
    var syntaxHighlight by mutableStateOf(true)
        private set
    var editorBackup by mutableStateOf(true)
        private set
    var hexBytesPerRow by mutableStateOf(8)
        private set
    var maxZoom by mutableStateOf(8f)
        private set

    var htmlJavaScript by mutableStateOf(true)
        private set

    // Search
    var searchMode by mutableStateOf(SearchMode.CONTAINS)
        private set
    /** 0 = any time, otherwise only files modified within this many milliseconds. */
    var searchAgeMs by mutableStateOf(0L)
        private set

    // Bookmarks
    var bookmarks by mutableStateOf(listOf<String>())
        private set
    var bookmarksOnTop by mutableStateOf(false)
        private set
    var useRecycleBin by mutableStateOf(true)
        private set
    var homePath by mutableStateOf("")
        private set

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences("kayx_settings", Context.MODE_PRIVATE)
        prefs = p
        load(p)
    }

    private fun load(p: SharedPreferences) {
        themeMode = p.getString("theme_mode", null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
        accent = p.getString("accent", null)?.let { runCatching { AccentChoice.valueOf(it) }.getOrNull() } ?: AccentChoice.BLUE
        bgStyle = p.getString("bg_style", null)?.let { runCatching { BgStyle.valueOf(it) }.getOrNull() }
            ?: if (p.getBoolean("glow", true)) BgStyle.GLOW else BgStyle.OFF
        icons3d = p.getBoolean("icons_3d", true)
        folderIcons = p.getBoolean("folder_icons", true)
        backend = p.getString("backend", null)?.let { runCatching { PrivilegedBackend.valueOf(it) }.getOrNull() } ?: PrivilegedBackend.ROOT
        showHidden = p.getBoolean("show_hidden", false)
        defaultSort = p.getString("default_sort", null)?.let { runCatching { SortBy.valueOf(it) }.getOrNull() } ?: SortBy.NAME
        dualPane = p.getBoolean("dual_pane", false)
        dualTabs = p.getBoolean("dual_tabs", false)
        confirmDelete = p.getBoolean("confirm_delete", true)
        editorFontSize = p.getInt("editor_font", 13).coerceIn(10, 24)
        syntaxHighlight = p.getBoolean("syntax", true)
        editorBackup = p.getBoolean("editor_backup", true)
        hexBytesPerRow = p.getInt("hex_bytes", 8).let { if (it in listOf(4, 8, 12)) it else 8 }
        maxZoom = p.getFloat("max_zoom", 8f).coerceIn(2f, 16f)
        htmlJavaScript = p.getBoolean("html_js", true)
        searchMode = p.getString("search_mode", null)?.let { runCatching { SearchMode.valueOf(it) }.getOrNull() } ?: SearchMode.CONTAINS
        searchAgeMs = p.getLong("search_age", 0L)
        bookmarks = (p.getString("bookmarks", "") ?: "").split('\n').filter { it.isNotBlank() }
        bookmarksOnTop = p.getBoolean("bookmarks_top", false)
        useRecycleBin = p.getBoolean("recycle_bin", true)
        homePath = p.getString("home_path", "") ?: ""
    }

    private fun save(block: SharedPreferences.Editor.() -> Unit) {
        prefs?.edit()?.let { it.block(); it.apply() }
    }

    fun updateThemeMode(v: ThemeMode) { themeMode = v; save { putString("theme_mode", v.name) } }
    fun updateAccent(v: AccentChoice) { accent = v; save { putString("accent", v.name) } }
    fun updateBgStyle(v: BgStyle) { bgStyle = v; save { putString("bg_style", v.name) } }
    fun updateFolderIcons(v: Boolean) { folderIcons = v; save { putBoolean("folder_icons", v) } }
    fun updateIcons3d(v: Boolean) { icons3d = v; save { putBoolean("icons_3d", v) } }
    fun updateBackend(v: PrivilegedBackend) { backend = v; save { putString("backend", v.name) } }
    fun updateShowHidden(v: Boolean) { showHidden = v; save { putBoolean("show_hidden", v) } }
    fun updateDefaultSort(v: SortBy) { defaultSort = v; save { putString("default_sort", v.name) } }
    fun updateDualPane(v: Boolean) { dualPane = v; save { putBoolean("dual_pane", v) } }
    fun updateDualTabs(v: Boolean) { dualTabs = v; save { putBoolean("dual_tabs", v) } }
    fun updateConfirmDelete(v: Boolean) { confirmDelete = v; save { putBoolean("confirm_delete", v) } }
    fun updateEditorFontSize(v: Int) { editorFontSize = v.coerceIn(10, 24); save { putInt("editor_font", editorFontSize) } }
    fun updateSyntaxHighlight(v: Boolean) { syntaxHighlight = v; save { putBoolean("syntax", v) } }
    fun updateEditorBackup(v: Boolean) { editorBackup = v; save { putBoolean("editor_backup", v) } }
    fun updateHexBytes(v: Int) { hexBytesPerRow = v; save { putInt("hex_bytes", v) } }
    fun updateMaxZoom(v: Float) { maxZoom = v; save { putFloat("max_zoom", v) } }

    fun updateHtmlJavaScript(v: Boolean) { htmlJavaScript = v; save { putBoolean("html_js", v) } }
    fun updateSearchMode(v: SearchMode) { searchMode = v; save { putString("search_mode", v.name) } }
    fun updateSearchAge(v: Long) { searchAgeMs = v; save { putLong("search_age", v) } }
    fun updateUseRecycleBin(v: Boolean) { useRecycleBin = v; save { putBoolean("recycle_bin", v) } }
    fun updateHomePath(v: String) { homePath = v; save { putString("home_path", v) } }
    fun updateBookmarksOnTop(v: Boolean) { bookmarksOnTop = v; save { putBoolean("bookmarks_top", v) } }
    fun addBookmark(path: String): Boolean {
        if (path in bookmarks) return false
        bookmarks = if (bookmarksOnTop) listOf(path) + bookmarks else bookmarks + path
        save { putString("bookmarks", bookmarks.joinToString("\n")) }
        return true
    }
    fun removeBookmark(path: String) {
        bookmarks = bookmarks - path
        save { putString("bookmarks", bookmarks.joinToString("\n")) }
    }

    fun resetAll() {
        prefs?.let { it.edit().clear().apply(); load(it) }
    }
}
