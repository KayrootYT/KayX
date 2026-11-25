package com.kayx.manager.feature.filebrowser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kayx.manager.data.archive.ArchiveRepository
import android.content.Context
import android.net.Uri
import com.kayx.manager.data.apk.ApkSignRepo
import com.kayx.manager.data.archive.ArchiveEdit
import com.kayx.manager.data.archive.MultiExtract
import com.kayx.manager.data.filesystem.Conflict
import com.kayx.manager.data.filesystem.ExternalOps
import com.kayx.manager.data.filesystem.RecycleBin
import com.kayx.manager.data.filesystem.FileOps
import com.kayx.manager.data.filesystem.FileOperationSounds
import com.kayx.manager.data.filesystem.FileActionSound
import com.kayx.manager.data.filesystem.FileRepository
import com.kayx.manager.data.filesystem.StorageLocation
import com.kayx.manager.data.filesystem.StorageLocationRepository
import com.kayx.manager.data.filesystem.SafRepository
import com.kayx.manager.data.filesystem.FileSearchCategory
import com.kayx.manager.data.settings.SettingsStore
import com.kayx.manager.domain.model.FileItem
import com.kayx.manager.domain.model.SortBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class BrowserState(
    val root: String = "",
    val dir: String = "",
    val items: List<FileItem> = emptyList(),
    val primarySelected: Set<String> = emptySet(),
    val secondarySelected: Set<String> = emptySet(),
    val clipboard: List<String> = emptyList(),
    val cut: Boolean = false,
    val query: String = "",
    val searching: Boolean = false,
    val showHidden: Boolean = false,
    val sort: SortBy = SortBy.NAME,
    val loading: Boolean = false,
    val message: String? = null,
    val secondaryDir: String = "",
    val secondaryItems: List<FileItem> = emptyList(),
    val dualPane: Boolean = false,
    val activePane: Int = 0,
    val grep: Boolean = false,
    val searchCategory: FileSearchCategory = FileSearchCategory.ALL,
    val conflicts: List<String> = emptyList(),
    val transferProgress: TransferProgress? = null,
    val transferControl: FileOps.TransferControl? = null,
    /** false = both panes side by side (MT-style split); true = full-screen panes you swipe between. */
    val tabMode: Boolean = false,
    /** Per-pane folder history (index 0 = left/first tab, 1 = right/second tab). */
    val backStacks: List<List<String>> = listOf(emptyList(), emptyList()),
    val fwdStacks: List<List<String>> = listOf(emptyList(), emptyList()),
    /** Paths that were just copied/moved; shown highlighted (green) until the next navigation. */
    val recent: Set<String> = emptySet(),
    /** A transfer waiting for the user to resolve name conflicts. */
    val pending: PendingTransfer? = null,
    /** A failed transfer can be retried from its original source/destination plan. */
    val retryTransfer: RetryTransfer? = null,
    /** Independent browser tabs; panes are two presentation windows over these tab records. */
    val tabs: List<BrowserTab> = listOf(BrowserTab(), BrowserTab()),
    val activeTab: Int = 0
) {
    /** Selection belongs to the active pane; the two panes never share selection state. */
    val selected: Set<String> get() = if (dualPane && activePane == 1) secondarySelected else primarySelected
    /** Index of the pane that currently receives actions (always 0 in single-pane mode). */
    val activeIdx: Int get() = if (dualPane && activePane == 1) 1 else 0
    /** Folder of the pane that is NOT active: the target of "Copy ->" / "Move ->". */
    val otherDir: String get() = if (activePane == 1) dir else secondaryDir
    val canBack: Boolean get() = backStacks[activeIdx].isNotEmpty()
    val canForward: Boolean get() = fwdStacks[activeIdx].isNotEmpty()
}

data class TransferProgress(
    val completed: Int,
    val total: Int,
    val currentName: String,
    val moving: Boolean = false,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val etaSeconds: Long? = null
) {
    val fraction: Float get() = if (totalBytes > 0L) (completedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        else if (total <= 0) 0f else completed.toFloat() / total
}

data class BrowserTab(
    val path: String = "",
    val selected: Set<String> = emptySet()
)

/** Files + destination remembered while the conflict dialog is open. [sourcePane] is null for clipboard pastes. */
data class PendingTransfer(val files: List<String>, val dst: String, val cut: Boolean, val sourcePane: Int?, val fromClipboard: Boolean)

data class RetryTransfer(
    val files: List<String>,
    val dst: String,
    val cut: Boolean,
    val sourcePane: Int?,
    val fromClipboard: Boolean,
    val policy: Conflict
)

internal enum class NavMove { PUSH, BACK, FORWARD }

internal fun updateBrowserTabPath(tabs: List<BrowserTab>, index: Int, path: String): List<BrowserTab> {
    if (tabs.isEmpty()) return listOf(BrowserTab(path))
    val safeIndex = index.coerceIn(0, tabs.lastIndex)
    return tabs.toMutableList().also { current ->
        current[safeIndex] = current[safeIndex].copy(path = path, selected = emptySet())
    }
}

internal fun updateBrowserTabSelection(tabs: List<BrowserTab>, index: Int, selected: Set<String>): List<BrowserTab> {
    if (tabs.isEmpty()) return tabs
    val safeIndex = index.coerceIn(0, tabs.lastIndex)
    return tabs.toMutableList().also { current ->
        current[safeIndex] = current[safeIndex].copy(selected = selected)
    }
}

internal fun encodeBrowserPaths(paths: List<String>): String = paths.joinToString("\n") { path ->
    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray(Charsets.UTF_8))
}

internal fun decodeBrowserPaths(encoded: String?): List<String> = encoded.orEmpty()
    .lineSequence()
    .filter { it.isNotBlank() }
    .mapNotNull { value ->
        runCatching { String(java.util.Base64.getUrlDecoder().decode(value), Charsets.UTF_8) }.getOrNull()
    }
    .toList()

class FileBrowserViewModel(private val repo: FileRepository = FileRepository(), private val archive: ArchiveRepository = ArchiveRepository()) : ViewModel() {
    private val storageLocations = StorageLocationRepository()
    private val storageTransfers = com.kayx.manager.data.filesystem.StorageTransferRepository()
    private val _s = MutableStateFlow(BrowserState(showHidden = SettingsStore.showHidden, sort = SettingsStore.defaultSort, dualPane = SettingsStore.dualPane, tabMode = SettingsStore.dualTabs))
    private var applied = Triple(SettingsStore.showHidden, SettingsStore.defaultSort, SettingsStore.dualPane)
    private var appContext: Context? = null
    val state: StateFlow<BrowserState> = _s

    fun attachSearchContext(context: Context) {
        appContext = context.applicationContext
        repo.attachSearchContext(context)
    }

    /** Start a browsing session at [path] (this becomes the root). */
    fun start(path: String) {
        val context = appContext
        val saved = context?.getSharedPreferences("kayx_browser_storage", Context.MODE_PRIVATE)
        val grantedTrees = context?.let { SafRepository.persistedTrees(it).map(Uri::toString).toSet() }.orEmpty()
        // Restore SAF only when reopening the normal browser entry point. Never override an
        // explicit path such as a Private Space session supplied by MainActivity.
        val mayRestoreSaf = path == SettingsStore.browserPath || path == "/sdcard" || path == "/storage/emulated/0"
        val restoredPrimarySaf = if (mayRestoreSaf) saved?.getString("primary_saf", null)?.let { key ->
            (StorageLocation.fromStableKey(key) as? StorageLocation.Saf)?.takeIf { it.treeUri in grantedTrees }?.stableKey()
        } else null
        val restoredSecondarySaf = if (mayRestoreSaf) saved?.getString("secondary_saf", null)?.let { key ->
            (StorageLocation.fromStableKey(key) as? StorageLocation.Saf)?.takeIf { it.treeUri in grantedTrees }?.stableKey()
        } else null
        val primary = restoredPrimarySaf ?: path
        if (_s.value.root == primary) { reload(); return }
        val storedSecondary = SettingsStore.browserSecondaryPath
        val restoredSecondary = restoredSecondarySaf ?: when (val storedLocation = StorageLocation.fromStableKey(storedSecondary)) {
            is StorageLocation.Saf -> storedLocation.takeIf { it.treeUri in grantedTrees }?.stableKey()
            is StorageLocation.Local -> storedLocation.directory.takeIf { it.isDirectory }?.absolutePath
            null -> storedSecondary.takeIf { it.isNotBlank() && File(it).isDirectory }
        }
            ?: primary
        fun restorable(key: String): Boolean = when (val location = StorageLocation.fromStableKey(key)) {
            is StorageLocation.Saf -> location.treeUri in grantedTrees
            is StorageLocation.Local -> location.directory.isDirectory && location.directory.canRead()
            null -> false
        }
        val restoredTabs = if (mayRestoreSaf) decodeBrowserPaths(saved?.getString("tabs", null))
            .filter(::restorable).take(32).map(::BrowserTab) else emptyList()
        val tabs = restoredTabs.ifEmpty { listOf(BrowserTab(primary), BrowserTab(restoredSecondary)) }
        val activeTab = (saved?.getInt("active_tab", 0) ?: 0).coerceIn(0, tabs.lastIndex)
        val restoredDir = if (!SettingsStore.dualPane) tabs[activeTab].path else primary
        val restoredActive = (saved?.getInt("active_pane", SettingsStore.browserActivePane) ?: SettingsStore.browserActivePane).coerceIn(0, 1)
        val back = if (mayRestoreSaf) decodeBrowserPaths(saved?.getString("back_primary", null)).filter(::restorable).takeLast(50) else emptyList()
        val forward = if (mayRestoreSaf) decodeBrowserPaths(saved?.getString("forward_primary", null)).filter(::restorable).takeLast(50) else emptyList()
        val secondaryBack = if (mayRestoreSaf) decodeBrowserPaths(saved?.getString("back_secondary", null)).filter(::restorable).takeLast(50) else emptyList()
        val secondaryForward = if (mayRestoreSaf) decodeBrowserPaths(saved?.getString("forward_secondary", null)).filter(::restorable).takeLast(50) else emptyList()
        _s.update { it.copy(root = primary, dir = restoredDir, secondaryDir = restoredSecondary, secondaryItems = emptyList(),
            activePane = if (SettingsStore.dualPane) restoredActive else 0, dualPane = SettingsStore.dualPane,
            backStacks = listOf(back, secondaryBack), fwdStacks = listOf(forward, secondaryForward), recent = emptySet(),
            tabs = tabs, activeTab = if (SettingsStore.dualPane) 0 else activeTab) }
        SettingsStore.updateBrowserSession(primary, restoredSecondary, restoredActive)
        persistSafBrowserSession(_s.value)
        reload()
    }

    private fun persistSafBrowserSession(state: BrowserState) {
        val context = appContext ?: return
        fun isPublicPath(key: String): Boolean = when (val location = StorageLocation.fromStableKey(key)) {
            is StorageLocation.Saf -> true
            is StorageLocation.Local -> {
                val path = runCatching { location.directory.canonicalPath }.getOrNull()
                if (path == null) false else {
                    val privateRoots = listOf(context.filesDir, context.cacheDir, context.noBackupFilesDir)
                        .mapNotNull { runCatching { it.canonicalPath.trimEnd(File.separatorChar) + File.separator }.getOrNull() }
                    privateRoots.none { path == it.removeSuffix(File.separator) || path.startsWith(it) }
                }
            }
            null -> false
        }
        val prefs = context.getSharedPreferences("kayx_browser_storage", Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putString("primary_saf", state.dir.takeIf { StorageLocation.fromStableKey(it) is StorageLocation.Saf })
            .putString("secondary_saf", state.secondaryDir.takeIf { StorageLocation.fromStableKey(it) is StorageLocation.Saf })
            .putInt("active_pane", state.activePane.coerceIn(0, 1))
        if (isPublicPath(state.dir) && state.tabs.all { isPublicPath(it.path) }) {
            editor.putString("tabs", encodeBrowserPaths(state.tabs.map { it.path }))
                .putInt("active_tab", state.activeTab.coerceIn(0, state.tabs.lastIndex))
                .putString("back_primary", encodeBrowserPaths(state.backStacks[0].filter(::isPublicPath)))
                .putString("forward_primary", encodeBrowserPaths(state.fwdStacks[0].filter(::isPublicPath)))
                .putString("back_secondary", encodeBrowserPaths(state.backStacks[1].filter(::isPublicPath)))
                .putString("forward_secondary", encodeBrowserPaths(state.fwdStacks[1].filter(::isPublicPath)))
        } else {
            editor.remove("tabs").remove("active_tab")
                .remove("back_primary").remove("forward_primary")
                .remove("back_secondary").remove("forward_secondary")
        }
        editor.apply()
    }

    fun open(path: String) = navigatePane(0, path, NavMove.PUSH)

    /** Open a persisted SAF tree or a local directory in the active browser pane. */
    fun openLocation(location: StorageLocation, pane: Int = _s.value.activeIdx) {
        val key = location.stableKey()
        if (pane == 1) openSecondary(key) else open(key)
    }

    private fun locationOf(key: String): StorageLocation =
        StorageLocation.fromStableKey(key) ?: StorageLocation.Local(File(key))

    fun activeLocation(): StorageLocation = locationOf(
        if (_s.value.dualPane && _s.value.activePane == 1) _s.value.secondaryDir else _s.value.dir
    )

    /** Change the folder of one pane and keep that pane's back/forward history. */
    private fun navigatePane(pane: Int, path: String, move: NavMove) {
        _s.update { st ->
            val cur = if (pane == 1) st.secondaryDir else st.dir
            val back = st.backStacks[pane]; val fwd = st.fwdStacks[pane]
            val (nb, nf) = when (move) {
                NavMove.PUSH -> if (cur.isBlank() || cur == path) back to fwd else (back + cur).takeLast(50) to emptyList<String>()
                NavMove.BACK -> back.dropLast(1) to (fwd + cur)
                NavMove.FORWARD -> (back + cur) to fwd.dropLast(1)
            }
            val backs = st.backStacks.toMutableList().also { it[pane] = nb }
            val fwds = st.fwdStacks.toMutableList().also { it[pane] = nf }
            val tabIndex = if (pane == 1) 1 else if (st.dualPane) 0 else st.activeTab.coerceIn(0, st.tabs.lastIndex)
            val tabs = updateBrowserTabPath(st.tabs, tabIndex, path)
            if (pane == 1) {
                st.copy(secondaryDir = path, secondarySelected = emptySet(), activePane = 1, backStacks = backs, fwdStacks = fwds, recent = emptySet(), tabs = tabs, activeTab = 1)
            } else {
                st.copy(dir = path, primarySelected = emptySet(), query = "", searching = false, activePane = 0, backStacks = backs, fwdStacks = fwds, recent = emptySet(), tabs = tabs, activeTab = if (st.dualPane) 0 else tabIndex)
            }
        }
        val saved = _s.value
        SettingsStore.updateBrowserSession(saved.dir, saved.secondaryDir, saved.activePane)
        persistSafBrowserSession(saved)
        reload()
    }

    /** Back in the active pane's own folder history (the "<" button). */
    fun goBack() {
        val s = _s.value
        val target = s.backStacks[s.activeIdx].lastOrNull() ?: run { notify("No previous folder"); return }
        navigatePane(s.activeIdx, target, NavMove.BACK)
    }

    /** Forward in the active pane's folder history (the ">" button). */
    fun goForward() {
        val s = _s.value
        val target = s.fwdStacks[s.activeIdx].lastOrNull() ?: run { notify("No next folder"); return }
        navigatePane(s.activeIdx, target, NavMove.FORWARD)
    }

    fun toggleTabMode() {
        val v = !_s.value.tabMode
        SettingsStore.updateDualTabs(v)
        _s.update { it.copy(tabMode = v) }
    }

    /** Re-apply browser defaults only when they were changed in Settings (keeps in-browser toggles otherwise). */
    fun syncDefaults() {
        if (_s.value.tabMode != SettingsStore.dualTabs) _s.update { it.copy(tabMode = SettingsStore.dualTabs) }
        val now = Triple(SettingsStore.showHidden, SettingsStore.defaultSort, SettingsStore.dualPane)
        if (now == applied) return
        applied = now
        _s.update { c -> c.copy(showHidden = now.first, sort = now.second, dualPane = now.third,
            secondaryDir = c.secondaryDir.ifBlank { c.root.ifBlank { c.dir } }) }
        reload()
    }

    fun toggleDualPane() {
        _s.update { current ->
            val enabled = !current.dualPane
            current.copy(dualPane = enabled, activePane = if (enabled) current.activePane else 0, secondaryDir = current.secondaryDir.ifBlank { current.root.ifBlank { current.dir } })
        }
        val saved = _s.value
        SettingsStore.updateDualPane(saved.dualPane)
        SettingsStore.updateBrowserSession(saved.dir, saved.secondaryDir, saved.activePane)
        persistSafBrowserSession(saved)
        reload()
    }

    fun addTab(path: String = _s.value.dir) {
        if (_s.value.dualPane) {
            openSecondary(path)
            return
        }
        _s.update { st ->
            val tabs = st.tabs + BrowserTab(path)
            st.copy(
                tabs = tabs,
                activeTab = tabs.lastIndex,
                activePane = 0,
                dir = path,
                primarySelected = emptySet(),
                query = "",
                searching = false,
                backStacks = listOf(emptyList(), st.backStacks[1]),
                fwdStacks = listOf(emptyList(), st.fwdStacks[1])
            )
        }
        val saved = _s.value
        SettingsStore.updateBrowserSession(saved.dir, saved.secondaryDir, saved.activePane)
        persistSafBrowserSession(saved)
        reload()
    }

    fun closeTab(index: Int) {
        _s.update { st ->
            if (st.tabs.size <= 1) st else {
                val tabs = st.tabs.toMutableList().also { it.removeAt(index.coerceIn(0, it.lastIndex)) }
                val active = when {
                    st.activeTab > index -> st.activeTab - 1
                    st.activeTab == index -> (index - 1).coerceAtLeast(0).coerceAtMost(tabs.lastIndex)
                    else -> st.activeTab
                }
                val tab = tabs[active]
                st.copy(
                    tabs = tabs,
                    activeTab = active,
                    activePane = 0,
                    dir = tab.path,
                    primarySelected = tab.selected,
                    backStacks = listOf(emptyList(), st.backStacks[1]),
                    fwdStacks = listOf(emptyList(), st.fwdStacks[1])
                )
            }
        }
        val saved = _s.value
        SettingsStore.updateBrowserSession(saved.dir, saved.secondaryDir, saved.activePane)
        persistSafBrowserSession(saved)
        reload()
    }

    fun setActiveTab(index: Int) {
        _s.update { st ->
            val tab = st.tabs.getOrNull(index.coerceIn(0, st.tabs.lastIndex)) ?: return@update st
            st.copy(
                activeTab = index.coerceIn(0, st.tabs.lastIndex),
                activePane = 0,
                dir = tab.path,
                primarySelected = tab.selected,
                query = "",
                searching = false,
                backStacks = listOf(emptyList(), st.backStacks[1]),
                fwdStacks = listOf(emptyList(), st.fwdStacks[1])
            )
        }
        val saved = _s.value
        SettingsStore.updateBrowserSession(saved.dir, saved.secondaryDir, saved.activePane)
        persistSafBrowserSession(saved)
        reload()
    }

    fun setActivePane(pane: Int) {
        _s.update { it.copy(activePane = pane.coerceIn(0, 1)) }
        val saved = _s.value
        SettingsStore.updateBrowserSession(saved.dir, saved.secondaryDir, saved.activePane)
        persistSafBrowserSession(saved)
    }

    fun openSecondary(path: String) = navigatePane(1, path, NavMove.PUSH)

    fun openActive(path: String) {
        if (_s.value.dualPane && _s.value.activePane == 1) openSecondary(path) else open(path)
    }

    fun swapPanes() {
        _s.update { it.copy(dir = it.secondaryDir, secondaryDir = it.dir, primarySelected = it.secondarySelected, secondarySelected = it.primarySelected,
            backStacks = it.backStacks.reversed(), fwdStacks = it.fwdStacks.reversed(), activePane = 1 - it.activePane) }
        val saved = _s.value
        SettingsStore.updateBrowserSession(saved.dir, saved.secondaryDir, saved.activePane)
        persistSafBrowserSession(saved)
        reload()
    }

    private fun activeLocation(s: BrowserState = _s.value): StorageLocation =
        locationOf(if (s.dualPane && s.activePane == 1) s.secondaryDir else s.dir)

    private fun activeDirectory(s: BrowserState = _s.value): File =
        (activeLocation(s) as? StorageLocation.Local)?.directory ?: File("/")

    fun navigateUpActive(): Boolean {
        val s = _s.value
        if (s.selected.isNotEmpty()) { clearSelection(); return true }
        return when (val location = activeLocation(s)) {
            is StorageLocation.Saf -> {
                if (location.relativePath.isNotBlank()) {
                    val parent = location.relativePath.substringBeforeLast('/', "")
                    openLocation(StorageLocation.Saf(location.treeUri, parent), s.activeIdx)
                    true
                } else if (s.dualPane && s.activePane == 1) { setActivePane(0); true } else false
            }
            is StorageLocation.Local -> {
                val current = location.directory
                val root = File(s.root)
                if (current.absolutePath == root.absolutePath) {
                    if (s.dualPane) {
                        val other = File(if (s.activePane == 0) s.secondaryDir else s.dir)
                        if (other.absolutePath != root.absolutePath) { setActivePane(1 - s.activePane); return true }
                    }
                    return false
                }
                val parent = current.parentFile ?: return false
                openLocation(StorageLocation.Local(parent), s.activeIdx)
                true
            }
        }
    }

    /** Go to the parent folder (also leaves the browser root, unlike back). */
    fun goUp() {
        when (val location = activeLocation()) {
            is StorageLocation.Saf -> {
                if (location.relativePath.isBlank()) notify("Already at the selected storage root")
                else openLocation(StorageLocation.Saf(location.treeUri, location.relativePath.substringBeforeLast('/', "")))
            }
            is StorageLocation.Local -> {
                val parent = location.directory.parentFile
                if (parent == null) { notify("Already at the top"); return }
                if (_s.value.root.isNotEmpty() && location.directory.absolutePath == _s.value.root) _s.update { it.copy(root = "/") }
                openActive(parent.absolutePath)
            }
        }
    }

    /** @return false when already at root (caller should leave the browser). */
    fun up(): Boolean {
        val s = _s.value
        if (s.selected.isNotEmpty()) { clearSelection(); return true }
        if (s.searching) { _s.update { it.copy(searching = false, query = "") }; reload(); return true }
        return navigateUpActive()
    }

    private var reloadJob: Job? = null

    fun reload() {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            // Debounce typing in search so we don't walk the disk on every keystroke.
            if (_s.value.searching && _s.value.query.isNotBlank()) delay(120)
            val snapshot = _s.value
            val searchPane = if (snapshot.dualPane && snapshot.activePane == 1) 1 else 0
            val searchActive = snapshot.searching && snapshot.query.isNotBlank()
            _s.update { it.copy(loading = true) }
            val (main, secondary) = coroutineScope {
                val scope = this
                suspend fun load(dir: String, pane: Int): List<FileItem> {
                    val location = locationOf(dir)
                    if (location is StorageLocation.Saf) {
                        val context = appContext ?: return emptyList()
                        return try {
                            if (searchActive && searchPane == pane) {
                                if (snapshot.grep && !isFilenamePattern(snapshot.query)) {
                                    _s.update { it.copy(message = "Content search is not supported by this SAF provider; searching names instead") }
                                }
                                storageLocations.searchSaf(
                                    context, location, snapshot.query.trim(), snapshot.showHidden, snapshot.sort,
                                    SettingsStore.searchMode, snapshot.searchCategory.extensions
                                )
                            } else storageLocations.list(context, location, snapshot.showHidden, snapshot.sort)
                        } catch (error: Exception) {
                            _s.update { it.copy(message = "Storage unavailable: " + (error.message ?: "permission revoked or device disconnected")) }
                            emptyList()
                        }
                    }
                    val local = (location as StorageLocation.Local).directory
                    return if (searchActive && searchPane == pane) {
                        if (snapshot.grep && !isFilenamePattern(snapshot.query)) repo.searchContents(local, snapshot.query, snapshot.showHidden, isActive = { scope.isActive })
                        else repo.search(local, snapshot.query, snapshot.showHidden, SettingsStore.searchMode, SettingsStore.searchAgeMs, snapshot.searchCategory.extensions, snapshot.sort)
                    } else repo.list(local, snapshot.showHidden, snapshot.sort)
                }
                val mainTask = async(Dispatchers.IO) { load(snapshot.dir, 0) }
                val secondaryTask = if (snapshot.dualPane) async(Dispatchers.IO) { load(snapshot.secondaryDir, 1) } else null
                mainTask.await() to (secondaryTask?.await() ?: snapshot.secondaryItems)
            }
            if (main.isEmpty() && !snapshot.searching && locationOf(snapshot.dir) is StorageLocation.Local) {
                val d = (locationOf(snapshot.dir) as StorageLocation.Local).directory
                if (d.isDirectory && d.listFiles() == null) _s.update { it.copy(message = "Permission denied: this folder can't be read") }
            }
            _s.update { current ->
                if (current.dir != snapshot.dir || current.secondaryDir != snapshot.secondaryDir ||
                    current.query != snapshot.query || current.searching != snapshot.searching ||
                    current.showHidden != snapshot.showHidden || current.grep != snapshot.grep || current.searchCategory != snapshot.searchCategory || current.sort != snapshot.sort || current.dualPane != snapshot.dualPane) current
                else current.copy(items = main, secondaryItems = secondary, loading = false)
            }
        }
    }

    /** Jump straight to any absolute path. If it lies outside the current root the root becomes "/" so back-navigation still works. */
    fun jump(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val saf = StorageLocation.fromStableKey(text) as? StorageLocation.Saf
        if (saf != null) {
            viewModelScope.launch {
                val context = appContext ?: return@launch notify("Storage context is not ready")
                val valid = withContext(Dispatchers.IO) {
                    SafRepository.persistedTrees(context).contains(Uri.parse(saf.treeUri)) &&
                        SafRepository.findByRelativePath(context, Uri.parse(saf.treeUri), saf.relativePath)?.isDirectory == true
                }
                if (!valid) notify("SAF folder is unavailable or its permission was revoked")
                else openLocation(saf)
            }
            return
        }
        val f = File(text)
        if (!f.exists()) { notify("Path not found"); return }
        if (!f.isDirectory) { notify("Not a folder"); return }
        val target = f.absolutePath
        val r = _s.value.root.trimEnd('/')
        if (r.isNotEmpty() && target != r && !target.startsWith("$r/")) _s.update { it.copy(root = "/") }
        if (!f.canRead()) notify("Folder may not be readable")
        openActive(target)
    }

    fun moveToPrivate(ctx: Context) {
        if (_s.value.selected.any { locationOf(it) is StorageLocation.Saf }) {
            notify("Move from SAF to Private space is not supported yet")
            return
        }
        if (!com.kayx.manager.data.storage.PrivateSpace.hasPin(ctx)) { notify("Open Private space on Home and create a PIN first"); return }
        if (!com.kayx.manager.data.storage.PrivateSpace.isUnlocked()) { notify("Unlock Private space before moving files into it"); return }
        val files = _s.value.selected.map(::File)
        val dst = com.kayx.manager.data.storage.PrivateSpace.dir(ctx)
        run({ FileOps.transfer(files, dst, true, Conflict.KEEP_BOTH).map { } }, "Moved ${files.size} to Private space", FileActionSound.MOVE)
        com.kayx.manager.data.storage.StorageScanner.invalidate()
    }

    fun toggleSearch() { _s.update { it.copy(searching = !it.searching, query = "", searchCategory = FileSearchCategory.ALL) }; reload() }
    fun setSearchCategory(category: FileSearchCategory) { _s.update { it.copy(searchCategory = category) }; reload() }
    fun setQuery(q: String) { _s.update { it.copy(query = q) }; reload() }
    fun toggleHidden() { _s.update { it.copy(showHidden = !it.showHidden) }; reload() }
    fun setSort(s: SortBy) { _s.update { it.copy(sort = s) }; reload() }
    private fun updateActiveSelection(transform: (Set<String>) -> Set<String>) = _s.update { st ->
        val secondary = st.dualPane && st.activePane == 1
        val selected = transform(if (secondary) st.secondarySelected else st.primarySelected)
        val tabIndex = if (secondary) 1 else if (st.dualPane) 0 else st.activeTab
        val tabs = updateBrowserTabSelection(st.tabs, tabIndex, selected)
        if (secondary) st.copy(secondarySelected = selected, tabs = tabs)
        else st.copy(primarySelected = selected, tabs = tabs)
    }
    fun toggleSelect(path: String) = updateActiveSelection { if (path in it) it - path else it + path }
    /** Long-press menu: make [pane] active and select only [path] so Copy / Cut / Delete / Zip act on that one item. */
    fun selectOnly(path: String, pane: Int) { setActivePane(pane); updateActiveSelection { setOf(path) } }

    private val swipeAnchor = arrayOfNulls<String>(2)

    /**
     * MT-Manager style swipe selection. The first swipe selects a row and remembers it; a later swipe on another row
     * selects every row between the remembered one and that row (both included) and moves the anchor there.
     * Swiping the anchor row again deselects it.
     */
    fun swipeSelect(path: String, pane: Int) {
        val st = _s.value
        val paths = (if (pane == 1) st.secondaryItems else st.items).map { it.path }
        val sel = if (pane == 1) st.secondarySelected else st.primarySelected
        val to = paths.indexOf(path)
        if (to < 0) return
        val a = swipeAnchor[pane]
        val from = if (a != null && a in sel) paths.indexOf(a) else -1
        val next: Set<String> = when {
            from < 0 -> { swipeAnchor[pane] = if (path in sel) null else path; if (path in sel) sel - path else sel + path }
            from == to -> { swipeAnchor[pane] = null; sel - path }
            else -> { swipeAnchor[pane] = path; sel + paths.subList(minOf(from, to), maxOf(from, to) + 1) }
        }
        _s.update { st ->
            val secondary = pane == 1 && st.dualPane
            val tabIndex = if (secondary) 1 else if (st.dualPane) 0 else st.activeTab
            val tabs = updateBrowserTabSelection(st.tabs, tabIndex, next)
            if (secondary) st.copy(secondarySelected = next, activePane = 1, tabs = tabs)
            else st.copy(primarySelected = next, activePane = 0, tabs = tabs)
        }
    }
    fun selectAll() {
        val st = _s.value
        val paths = (if (st.dualPane && st.activePane == 1) st.secondaryItems else st.items).map { it.path }.toSet()
        updateActiveSelection { paths }
    }
    fun selectPaths(paths: Set<String>) = updateActiveSelection { paths }
    fun clearSelection() = updateActiveSelection { emptySet() }
    private fun clearPaneSelection(pane: Int) = _s.update { st ->
        val secondary = pane == 1 && st.dualPane
        val tabIndex = if (secondary) 1 else if (st.dualPane) 0 else st.activeTab
        val tabs = updateBrowserTabSelection(st.tabs, tabIndex, emptySet())
        if (secondary) st.copy(secondarySelected = emptySet(), tabs = tabs)
        else st.copy(primarySelected = emptySet(), tabs = tabs)
    }
    fun notify(msg: String) = _s.update { it.copy(message = msg) }
    fun consumeMessage() = _s.update { it.copy(message = null) }
    fun copySelected(cut: Boolean) {
        FileOperationSounds.play(appContext, if (cut) FileActionSound.MOVE else FileActionSound.COPY)
        _s.update { st ->
        val picked = st.selected.toList()
        if (st.dualPane && st.activePane == 1) st.copy(clipboard = picked, cut = cut, secondarySelected = emptySet(), message = if (cut) "Cut ${picked.size}" else "Copied ${picked.size}")
        else st.copy(clipboard = picked, cut = cut, primarySelected = emptySet(), message = if (cut) "Cut ${picked.size}" else "Copied ${picked.size}")
        }
    }
    fun clearClipboard() = _s.update { it.copy(clipboard = emptyList()) }

    private fun run(op: suspend () -> Result<Unit>, ok: String? = null, sound: FileActionSound? = null) = viewModelScope.launch {
        val initial = _s.value
        val sourcePane = if (initial.dualPane && initial.activePane == 1) 1 else 0
        val r = withContext(Dispatchers.IO) { op() }
        if (r.isSuccess && sound != null) FileOperationSounds.play(appContext, sound)
        repo.invalidateSearchCache()
        clearPaneSelection(sourcePane)
        _s.update { it.copy(message = r.exceptionOrNull()?.message ?: ok) }
        reload()
    }

    /** Paste the clipboard into the active pane's folder ("single-window" copy/move). */
    fun paste(policy: Conflict? = null) {
        val s = _s.value
        val pending = s.pending
        if (policy != null && pending != null) {
            startStorageTransfer(pending.files, pending.dst, pending.cut, policy, pending.sourcePane, pending.fromClipboard)
            return
        }
        startStorageTransfer(s.clipboard, activeLocation(s).stableKey(), s.cut, policy, null, true)
    }

    /**
     * "Copy ->" / "Move ->": send the active pane's selection straight into the OTHER pane's folder
     * (the confirm dialog shows that folder). No clipboard and no separate paste step.
     */
    fun transferSelectedToOther(cut: Boolean, policy: Conflict? = null) {
        val s = _s.value
        if (!s.dualPane) { notify("Turn on dual pane first"); return }
        startStorageTransfer(s.selected.toList(), s.otherDir, cut, policy, s.activePane, false)
    }

    fun transferSelectedToSaf(treeUri: Uri, cut: Boolean) {
        val selected = _s.value.selected.toList()
        if (selected.isEmpty()) { notify("Select files first"); return }
        startStorageTransfer(selected, StorageLocation.Saf(treeUri.toString(), "").stableKey(), cut, Conflict.KEEP_BOTH, _s.value.activeIdx, false)
    }

    private fun startStorageTransfer(keys: List<String>, destinationKey: String, cut: Boolean, policy: Conflict?, sourcePane: Int?, fromClipboard: Boolean) {
        val context = appContext
        if (context == null) { notify("Storage context is not ready"); return }
        if (keys.isEmpty()) { notify("Nothing selected to transfer"); return }
        val destinationLocation = locationOf(destinationKey)
        if (policy == null && destinationLocation is StorageLocation.Local && keys.all { locationOf(it) is StorageLocation.Local }) {
            val sources = keys.map { (locationOf(it) as StorageLocation.Local).directory }
            val conflicts = FileOps.conflicts(sources, destinationLocation.directory)
            if (conflicts.isNotEmpty()) {
                _s.update { it.copy(conflicts = conflicts, pending = PendingTransfer(keys, destinationKey, cut, sourcePane, fromClipboard)) }
                return
            }
        }
        val effectivePolicy = policy ?: Conflict.KEEP_BOTH
        val control = FileOps.TransferControl()
        _s.update { it.copy(conflicts = emptyList(), pending = null, recent = emptySet(), transferControl = control, retryTransfer = null) }
        viewModelScope.launch {
            _s.update { it.copy(transferProgress = TransferProgress(0, keys.size, "Preparing", cut)) }
            val transferStartedAt = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                storageTransfers.transfer(context, keys, destinationKey, cut, effectivePolicy, control) { completed, total, name, completedBytes, totalBytes ->
                    val elapsedMs = (System.currentTimeMillis() - transferStartedAt).coerceAtLeast(1L)
                    val speed = if (completedBytes > 0L) completedBytes * 1000L / elapsedMs else 0L
                    val eta = if (speed > 0L && totalBytes > completedBytes) (totalBytes - completedBytes) / speed else null
                    _s.update {
                        it.copy(transferProgress = TransferProgress(
                            completed, total, name, cut, completedBytes, totalBytes, speed, eta
                        ))
                    }
                }
            }
            repo.invalidateSearchCache()
            val outcome = result.getOrNull()
            val failed = outcome?.failed.orEmpty()
            if (result.isSuccess && !fromClipboard) clearPaneSelection(sourcePane ?: _s.value.activeIdx)
            _s.update { current ->
                current.copy(
                    transferProgress = null,
                    transferControl = null,
                    retryTransfer = if (failed.isNotEmpty()) RetryTransfer(
                        keys.filter { it in failed },
                        destinationKey, cut, sourcePane, fromClipboard, effectivePolicy
                    ) else null,
                    message = result.exceptionOrNull()?.message ?: buildString {
                        val cleanupIssue = outcome?.cleanupWarnings?.isNotEmpty() == true
                        append(if (cut && !cleanupIssue) "Moved " else if (cleanupIssue) "Copied (source cleanup incomplete) " else "Copied ")
                        append(outcome?.completed ?: 0)
                        append(" item(s)")
                        outcome?.let { if (it.skipped > 0) append("; ${it.skipped} skipped") }
                        if (failed.isNotEmpty()) append("; " + failed.size + " failed")
                        if (outcome?.cleanupWarnings?.isNotEmpty() == true) append("; source cleanup warning: " + outcome.cleanupWarnings.joinToString())
                    }
                )
            }
            if (result.isSuccess && failed.isEmpty() && (outcome?.completed ?: 0) > 0) FileOperationSounds.play(appContext, if (cut) FileActionSound.MOVE else FileActionSound.COPY)
            if (fromClipboard && cut && result.isSuccess && failed.isEmpty()) clearClipboard()
            reload()
        }
    }

    fun dismissConflicts() = _s.update { it.copy(conflicts = emptyList(), pending = null) }
    fun pauseTransfer() { _s.value.transferControl?.pause() }
    fun resumeTransfer() { _s.value.transferControl?.resume() }
    fun cancelTransfer() { _s.value.transferControl?.cancel() }
    fun retryTransfer() {
        val r = _s.value.retryTransfer ?: return
        startStorageTransfer(r.files, r.dst, r.cut, r.policy, r.sourcePane, r.fromClipboard)
    }
    fun newFolder(n: String) {
        when (val location = activeLocation()) {
            is StorageLocation.Local -> run({ repo.createFolder(location.directory, n) }, "Folder created: $n", FileActionSound.CREATE)
            is StorageLocation.Saf -> viewModelScope.launch {
                val context = appContext ?: return@launch notify("Storage context is not ready")
                val result = withContext(Dispatchers.IO) { SafRepository.createFolder(context, Uri.parse(location.treeUri), location.relativePath, n) }
                notify(if (result != null) "Folder created: $n" else "Could not create folder")
                reload()
            }
        }
    }
    fun newFile(n: String) {
        when (val location = activeLocation()) {
            is StorageLocation.Local -> run({ repo.createFile(location.directory, n) }, "File created: $n", FileActionSound.CREATE)
            is StorageLocation.Saf -> viewModelScope.launch {
                val context = appContext ?: return@launch notify("Storage context is not ready")
                val result = withContext(Dispatchers.IO) { SafRepository.createFile(context, Uri.parse(location.treeUri), location.relativePath, n) }
                notify(if (result != null) "File created: $n" else "Could not create file")
                reload()
            }
        }
    }
    fun rename(path: String, n: String) {
        when (val location = locationOf(path)) {
            is StorageLocation.Local -> run({ repo.rename(location.directory, n) }, "Renamed to $n", FileActionSound.RENAME)
            is StorageLocation.Saf -> viewModelScope.launch {
                val context = appContext ?: return@launch notify("Storage context is not ready")
                val result = withContext(Dispatchers.IO) {
                    val doc = SafRepository.findByRelativePath(context, Uri.parse(location.treeUri), location.relativePath)
                    doc?.let { SafRepository.rename(context, it.uri, n) }
                }
                if (result != null) clearSelection()
                notify(if (result != null) "Renamed to $n" else "Rename failed")
                reload()
            }
        }
    }
    fun zipSelected(name: String) {
        if (_s.value.selected.any { locationOf(it) is StorageLocation.Saf }) { notify("Archive creation from SAF is not supported yet"); return }
        val f = _s.value.selected.map(::File)
        val n = name.trim().replace('/', '_').ifEmpty { "archive" }.let { if (it.endsWith(".zip", true)) it else "$it.zip" }
        val out = File(activeDirectory(), n)
        run({ archive.create(f, out) }, "Created $n", FileActionSound.SUCCESS)
    }
    fun deleteSelected() {
        val keys = _s.value.selected.toList()
        if (keys.all { locationOf(it) is StorageLocation.Local }) {
            val files = keys.map { (locationOf(it) as StorageLocation.Local).directory }
            if (SettingsStore.useRecycleBin) run({ withContext(Dispatchers.IO) { RecycleBin.trash(files) } }, "Moved ${files.size} to recycle bin", FileActionSound.DELETE)
            else run({ repo.delete(files) }, "Deleted ${files.size}", FileActionSound.DELETE)
            return
        }
        viewModelScope.launch {
            val context = appContext ?: return@launch notify("Storage context is not ready")
            val results = withContext(Dispatchers.IO) {
                keys.map { key ->
                    when (val location = locationOf(key)) {
                        is StorageLocation.Local -> runCatching { location.directory.deleteRecursively() }.getOrDefault(false)
                        is StorageLocation.Saf -> {
                            val doc = SafRepository.findByRelativePath(context, Uri.parse(location.treeUri), location.relativePath)
                            doc?.let { SafRepository.delete(context, it.uri) } ?: false
                        }
                    }
                }
            }
            notify("Deleted ${results.count { it }} of ${results.size} item(s)")
            clearSelection()
            reload()
        }
    }

    // ---- MT-Manager-style extras ----
    fun toggleGrep() { _s.update { it.copy(grep = !it.grep) }; reload() }
    fun invertSelection() {
        val st = _s.value
        val all = (if (st.dualPane && st.activePane == 1) st.secondaryItems else st.items).map { it.path }.toSet()
        updateActiveSelection { all - it }
    }
    fun selectPattern(p: String) {
        val st = _s.value
        val rx = FileOps.globToRegex(p.trim())
        val list = if (st.dualPane && st.activePane == 1) st.secondaryItems else st.items
        val selected = list.filter { rx.matches(it.name) }.map { it.path }.toSet()
        updateActiveSelection { selected }
    }
    fun duplicateSelected() {
        if (_s.value.selected.any { locationOf(it) is StorageLocation.Saf }) { notify("Duplicate is not available for SAF items yet"); return }
        val f = _s.value.selected.map(::File)
        run({ FileOps.duplicate(f) }, "Duplicated ${f.size}", FileActionSound.COPY)
    }
    fun batchRename(find: String, replace: String, regex: Boolean, start: Int) {
        if (_s.value.selected.any { locationOf(it) is StorageLocation.Saf }) { notify("Batch rename is not available for SAF items yet"); return }
        val f = _s.value.selected.map(::File).sortedBy { it.name.lowercase() }
        run({ FileOps.batchRename(f, find, replace, regex, start) }, "Renamed ${f.size}", FileActionSound.RENAME)
    }
    fun symlink(target: String, name: String) {
        if (activeLocation() is StorageLocation.Saf || locationOf(target) is StorageLocation.Saf) { notify("Symbolic links are not supported by SAF storage"); return }
        run({ runCatching { FileOps.symlink(File(target), File(activeDirectory(), name)) } }, "Symlink created", FileActionSound.CREATE)
    }

    fun extractHere(path: String) {
        if (locationOf(path) is StorageLocation.Saf) { notify("Extracting archives from SAF is not supported yet"); return }
        viewModelScope.launch {
            notify("Extracting…")
            val r = withContext(Dispatchers.IO) { MultiExtract.extract(File(path)) }
            if (r.isSuccess) FileOperationSounds.play(appContext, FileActionSound.EXTRACT)
            repo.invalidateSearchCache()
            _s.update { st -> st.copy(message = r.fold({ p -> "Extracted ${p.second} file(s) → ${p.first.name}" }, { e -> e.message ?: "Extraction failed" })) }
            reload()
        }
    }
    fun alignZip(path: String) {
        if (locationOf(path) is StorageLocation.Saf) { notify("ZIP alignment from SAF is not supported yet"); return }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { ArchiveEdit.rewrite(File(path)) }
            repo.invalidateSearchCache()
            _s.update { st -> st.copy(message = r.fold({ "Aligned" }, { e -> e.message ?: "Align failed" })) }
            reload()
        }
    }
    fun signApk(ctx: Context, path: String) {
        if (locationOf(path) is StorageLocation.Saf) { notify("APK signing from SAF is not supported yet"); return }
        viewModelScope.launch {
            notify("Signing…")
            val f = File(path)
            val out = FileOps.uniqueName(f.parentFile ?: f, f.nameWithoutExtension + "_signed.apk")
            val r = withContext(Dispatchers.IO) { ApkSignRepo.sign(ctx.applicationContext, f, out) }
            _s.update { st -> st.copy(message = r.fold({ "Signed → ${out.name}" }, { e -> "Sign failed: ${e.message}" })) }
            reload()
        }
    }

    fun convert(ctx: Context, path: String) {
        if (locationOf(path) is StorageLocation.Saf) { notify("Conversion from SAF is not supported yet"); return }
        viewModelScope.launch {
            notify("Converting…")
            val result = withContext(Dispatchers.IO) {
                ExternalOps.convert(ctx.applicationContext, File(path))
            }
            repo.invalidateSearchCache()
            notify(result)
            reload()
        }
    }
}

/** Extension and wildcard expressions always search file names, even if content-search mode is selected. */
private fun isFilenamePattern(raw: String): Boolean {
    val query = raw.trim()
    val dot = query.lastIndexOf('.')
    val extension = query.substringAfterLast('.', "")
    return query.startsWith(".") || '*' in query || '?' in query ||
        (dot > 0 && extension.length in 1..12 && extension.all { it.isLetterOrDigit() })
}
