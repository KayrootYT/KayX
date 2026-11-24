package com.kayx.manager.feature.filebrowser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kayx.manager.data.archive.ArchiveRepository
import android.content.Context
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

data class TransferProgress(val completed: Int, val total: Int, val currentName: String, val moving: Boolean = false) {
    val fraction: Float get() = if (total <= 0) 0f else completed.toFloat() / total
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

class FileBrowserViewModel(private val repo: FileRepository = FileRepository(), private val archive: ArchiveRepository = ArchiveRepository()) : ViewModel() {
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
        if (_s.value.root == path) { reload(); return }
        val restoredSecondary = SettingsStore.browserSecondaryPath.takeIf { it.isNotBlank() && File(it).isDirectory } ?: path
        val restoredActive = SettingsStore.browserActivePane.coerceIn(0, 1)
        _s.update { it.copy(root = path, dir = path, secondaryDir = restoredSecondary, secondaryItems = emptyList(),
            activePane = if (SettingsStore.dualPane) restoredActive else 0, dualPane = SettingsStore.dualPane,
            backStacks = listOf(emptyList(), emptyList()), fwdStacks = listOf(emptyList(), emptyList()), recent = emptySet(),
            tabs = listOf(BrowserTab(path), BrowserTab(restoredSecondary)), activeTab = if (SettingsStore.dualPane) restoredActive else 0) }
        SettingsStore.updateBrowserSession(path, restoredSecondary, restoredActive)
        reload()
    }

    fun open(path: String) = navigatePane(0, path, NavMove.PUSH)

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
            val tabs = st.tabs.toMutableList()
            if (pane == 1) {
                if (tabs.size > 1) tabs[1] = tabs[1].copy(path = path, selected = emptySet())
                st.copy(secondaryDir = path, secondarySelected = emptySet(), activePane = 1, backStacks = backs, fwdStacks = fwds, recent = emptySet(), tabs = tabs, activeTab = 1)
            } else {
                if (tabs.isNotEmpty()) tabs[0] = tabs[0].copy(path = path, selected = emptySet())
                st.copy(dir = path, primarySelected = emptySet(), query = "", searching = false, activePane = 0, backStacks = backs, fwdStacks = fwds, recent = emptySet(), tabs = tabs, activeTab = 0)
            }
        }
        val saved = _s.value
        SettingsStore.updateBrowserSession(saved.dir, saved.secondaryDir, saved.activePane)
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
        reload()
    }

    fun addTab(path: String = _s.value.dir) {
        _s.update { it.copy(tabs = it.tabs + BrowserTab(path), activeTab = it.tabs.size) }
        if (_s.value.dualPane) {
            setActivePane(1)
            openSecondary(path)
        } else open(path)
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
                st.copy(tabs = tabs, activeTab = active, dir = tab.path, primarySelected = tab.selected)
            }
        }
        reload()
    }

    fun setActiveTab(index: Int) {
        _s.update { st ->
            val tab = st.tabs.getOrNull(index.coerceIn(0, st.tabs.lastIndex)) ?: return@update st
            st.copy(
                activeTab = index.coerceIn(0, st.tabs.lastIndex),
                activePane = if (st.dualPane) index.coerceIn(0, 1) else 0,
                dir = tab.path,
                primarySelected = tab.selected
            )
        }
        reload()
    }

    fun setActivePane(pane: Int) {
        _s.update { it.copy(activePane = pane.coerceIn(0, 1)) }
        val saved = _s.value
        SettingsStore.updateBrowserSession(saved.dir, saved.secondaryDir, saved.activePane)
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
        reload()
    }

    private fun activeDirectory(s: BrowserState = _s.value): File = File(if (s.dualPane && s.activePane == 1) s.secondaryDir else s.dir)

    fun navigateUpActive(): Boolean {
        val s = _s.value
        if (s.selected.isNotEmpty()) { clearSelection(); return true }
        val current = activeDirectory(s)
        val root = File(s.root)
        if (current.absolutePath == root.absolutePath) {
            if (s.dualPane) {
                val other = if (s.activePane == 0) File(s.secondaryDir) else File(s.dir)
                if (other.absolutePath != root.absolutePath) { setActivePane(1 - s.activePane); return true }
            }
            return false
        }
        val parent = current.parentFile ?: return false
        if (s.dualPane && s.activePane == 1) openSecondary(parent.absolutePath) else open(parent.absolutePath)
        return true
    }

    /** Go to the parent folder (also leaves the browser root, unlike back). */
    fun goUp() {
        val cur = activeDirectory(); val parent = cur.parentFile
        if (parent == null) { notify("Already at the top"); return }
        if (_s.value.root.isNotEmpty() && cur.absolutePath == _s.value.root) _s.update { it.copy(root = "/") }
        openActive(parent.absolutePath)
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
                suspend fun load(dir: String, pane: Int): List<FileItem> =
                    if (searchActive && searchPane == pane) {
                        if (snapshot.grep && !isFilenamePattern(snapshot.query)) repo.searchContents(File(dir), snapshot.query, snapshot.showHidden, isActive = { scope.isActive })
                        else repo.search(File(dir), snapshot.query, snapshot.showHidden, SettingsStore.searchMode, SettingsStore.searchAgeMs, snapshot.searchCategory.extensions, snapshot.sort)
                    } else repo.list(File(dir), snapshot.showHidden, snapshot.sort)
                val mainTask = async(Dispatchers.IO) { load(snapshot.dir, 0) }
                val secondaryTask = if (snapshot.dualPane) async(Dispatchers.IO) { load(snapshot.secondaryDir, 1) } else null
                mainTask.await() to (secondaryTask?.await() ?: snapshot.secondaryItems)
            }
            if (main.isEmpty() && !snapshot.searching) {
                val d = File(snapshot.dir)
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
        if (st.dualPane && st.activePane == 1) {
            val selected = transform(st.secondarySelected)
            val tabs = st.tabs.toMutableList()
            if (tabs.size > 1) tabs[1] = tabs[1].copy(selected = selected)
            st.copy(secondarySelected = selected, tabs = tabs)
        } else {
            val selected = transform(st.primarySelected)
            val tabs = st.tabs.toMutableList()
            if (tabs.isNotEmpty()) tabs[0] = tabs[0].copy(selected = selected)
            st.copy(primarySelected = selected, tabs = tabs)
        }
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
        _s.update { if (pane == 1) it.copy(secondarySelected = next, activePane = 1) else it.copy(primarySelected = next, activePane = 0) }
    }
    fun selectAll() = _s.update { st ->
        val paths = (if (st.dualPane && st.activePane == 1) st.secondaryItems else st.items).map { it.path }.toSet()
        if (st.dualPane && st.activePane == 1) st.copy(secondarySelected = paths) else st.copy(primarySelected = paths)
    }
    fun selectPaths(paths: Set<String>) = updateActiveSelection { paths }
    fun clearSelection() = updateActiveSelection { emptySet() }
    private fun clearPaneSelection(pane: Int) = _s.update { st ->
        if (pane == 1) st.copy(secondarySelected = emptySet()) else st.copy(primarySelected = emptySet())
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
            startTransfer(pending.files.map(::File), File(pending.dst), pending.cut, policy, pending.sourcePane, pending.fromClipboard)
            return
        }
        startTransfer(s.clipboard.map(::File), activeDirectory(s), s.cut, policy, null, true)
    }

    /**
     * "Copy ->" / "Move ->": send the active pane's selection straight into the OTHER pane's folder
     * (the confirm dialog shows that folder). No clipboard and no separate paste step.
     */
    fun transferSelectedToOther(cut: Boolean, policy: Conflict? = null) {
        val s = _s.value
        if (!s.dualPane) { notify("Turn on dual pane first"); return }
        startTransfer(s.selected.map(::File), File(s.otherDir), cut, policy, s.activePane, false)
    }

    private fun startTransfer(files: List<File>, dst: File, cut: Boolean, policy: Conflict?, sourcePane: Int?, fromClipboard: Boolean) {
        if (files.isEmpty()) { notify("Nothing to ${if (cut) "move" else "copy"}"); return }
        if (policy == null) {
            val c = FileOps.conflicts(files, dst)
            if (c.isNotEmpty()) {
                _s.update { it.copy(conflicts = c, pending = PendingTransfer(files.map { f -> f.absolutePath }, dst.absolutePath, cut, sourcePane, fromClipboard)) }
                return
            }
        }
        val st = _s.value
        val destinationPane = if (st.dualPane && st.activePane == 1) 1 else 0
        val control = FileOps.TransferControl()
        _s.update { it.copy(conflicts = emptyList(), pending = null, recent = emptySet(), transferControl = control, retryTransfer = null) }
        viewModelScope.launch {
            _s.update { it.copy(transferProgress = TransferProgress(0, files.size, "Preparing", cut)) }
            val before = withContext(Dispatchers.IO) { dst.list()?.toSet().orEmpty() }
            var completedBeforeFailure = 0
            val result = withContext(Dispatchers.IO) {
                FileOps.transfer(files, dst, cut, policy ?: Conflict.KEEP_BOTH, { completed, total, name ->
                    completedBeforeFailure = maxOf(completedBeforeFailure, completed)
                    _s.update { it.copy(transferProgress = TransferProgress(completed, total, name, cut)) }
                }, control).map { }
            }
            repo.invalidateSearchCache()
            // Everything new in the destination is highlighted so the user can see what just arrived.
            val fresh = withContext(Dispatchers.IO) {
                val after = dst.list()?.toSet().orEmpty()
                ((after - before) + files.map { it.name }.filter { n -> File(dst, n).exists() }).map { n -> File(dst, n).absolutePath }.toSet()
            }
            clearPaneSelection(sourcePane ?: destinationPane)
            _s.update {
                it.copy(
                    transferProgress = null,
                    recent = if (result.isSuccess) fresh else emptySet(),
                    transferControl = null,
                    retryTransfer = if (result.isFailure && completedBeforeFailure < files.size) RetryTransfer(
                        files.drop(completedBeforeFailure).map { it.absolutePath }, dst.absolutePath, cut, sourcePane, fromClipboard, policy ?: Conflict.KEEP_BOTH
                    ) else null,
                    message = result.exceptionOrNull()?.message ?: (if (cut) "Moved ${files.size}" else "Copied ${files.size}")
                )
            }
            if (result.isSuccess) FileOperationSounds.play(appContext, if (cut) FileActionSound.MOVE else if (fromClipboard) FileActionSound.PASTE else FileActionSound.COPY)
            if (fromClipboard && cut && result.isSuccess) clearClipboard()
            reload()
        }
    }
    fun dismissConflicts() = _s.update { it.copy(conflicts = emptyList(), pending = null) }
    fun pauseTransfer() { _s.value.transferControl?.pause() }
    fun resumeTransfer() { _s.value.transferControl?.resume() }
    fun cancelTransfer() { _s.value.transferControl?.cancel() }
    fun retryTransfer() {
        val r = _s.value.retryTransfer ?: return
        startTransfer(r.files.map(::File), File(r.dst), r.cut, r.policy, r.sourcePane, r.fromClipboard)
    }
    fun newFolder(n: String) = run({ repo.createFolder(activeDirectory(), n) }, "Folder created: $n", FileActionSound.CREATE)
    fun newFile(n: String) = run({ repo.createFile(activeDirectory(), n) }, "File created: $n", FileActionSound.CREATE)
    fun rename(path: String, n: String) = run({ repo.rename(File(path), n) }, "Renamed to $n", FileActionSound.RENAME)
    fun zipSelected(name: String) {
        val f = _s.value.selected.map(::File)
        val n = name.trim().replace('/', '_').ifEmpty { "archive" }.let { if (it.endsWith(".zip", true)) it else "$it.zip" }
        val out = File(activeDirectory(), n)
        run({ archive.create(f, out) }, "Created $n", FileActionSound.SUCCESS)
    }
    fun deleteSelected() {
        val f = _s.value.selected.map(::File)
        if (SettingsStore.useRecycleBin) run({ withContext(Dispatchers.IO) { RecycleBin.trash(f) } }, "Moved ${f.size} to recycle bin", FileActionSound.DELETE)
        else run({ repo.delete(f) }, "Deleted ${f.size}", FileActionSound.DELETE)
    }

    // ---- MT-Manager-style extras ----
    fun toggleGrep() { _s.update { it.copy(grep = !it.grep) }; reload() }
    fun invertSelection() = _s.update { st ->
        val all = (if (st.dualPane && st.activePane == 1) st.secondaryItems else st.items).map { it.path }.toSet()
        if (st.dualPane && st.activePane == 1) st.copy(secondarySelected = all - st.secondarySelected) else st.copy(primarySelected = all - st.primarySelected)
    }
    fun selectPattern(p: String) = _s.update { st ->
        val rx = FileOps.globToRegex(p.trim())
        val list = if (st.dualPane && st.activePane == 1) st.secondaryItems else st.items
        if (st.dualPane && st.activePane == 1) st.copy(secondarySelected = list.filter { rx.matches(it.name) }.map { it.path }.toSet()) else st.copy(primarySelected = list.filter { rx.matches(it.name) }.map { it.path }.toSet())
    }
    fun duplicateSelected() { val f = _s.value.selected.map(::File); run({ FileOps.duplicate(f) }, "Duplicated ${f.size}", FileActionSound.COPY) }
    fun batchRename(find: String, replace: String, regex: Boolean, start: Int) {
        val f = _s.value.selected.map(::File).sortedBy { it.name.lowercase() }
        run({ FileOps.batchRename(f, find, replace, regex, start) }, "Renamed ${f.size}", FileActionSound.RENAME)
    }
    fun symlink(target: String, name: String) = run({ runCatching { FileOps.symlink(File(target), File(activeDirectory(), name)) } }, "Symlink created", FileActionSound.CREATE)

    fun extractHere(path: String) {
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
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { ArchiveEdit.rewrite(File(path)) }
            repo.invalidateSearchCache()
            _s.update { st -> st.copy(message = r.fold({ "Aligned" }, { e -> e.message ?: "Align failed" })) }
            reload()
        }
    }
    fun signApk(ctx: Context, path: String) {
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
