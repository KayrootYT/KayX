package com.kayx.manager.feature.filebrowser

import com.kayx.manager.domain.model.SortBy
import com.kayx.manager.data.storage.Category
import java.util.Locale

/** Deterministic local command grammar; it never calls a hosted model or executes arbitrary shell text. */
sealed interface OfflineAssistantCommand {
    data object Help : OfflineAssistantCommand
    data class Search(val query: String) : OfflineAssistantCommand
    data class SearchContent(val query: String) : OfflineAssistantCommand
    data class OpenFolder(val name: String) : OfflineAssistantCommand
    data class OpenItem(val name: String) : OfflineAssistantCommand
    data object GoBack : OfflineAssistantCommand
    data object GoUp : OfflineAssistantCommand
    data object Refresh : OfflineAssistantCommand
    data class ShowHidden(val visible: Boolean) : OfflineAssistantCommand
    data class Sort(val by: SortBy) : OfflineAssistantCommand
    data object SelectAll : OfflineAssistantCommand
    data object ClearSelection : OfflineAssistantCommand
    data class SelectName(val name: String) : OfflineAssistantCommand
    data class Copy(val cut: Boolean) : OfflineAssistantCommand
    data object Paste : OfflineAssistantCommand
    data class NewFolder(val name: String) : OfflineAssistantCommand
    data class NewFile(val name: String) : OfflineAssistantCommand
    data class RenameSelected(val newName: String) : OfflineAssistantCommand
    data object DeleteSelected : OfflineAssistantCommand
    data object Undo : OfflineAssistantCommand
    data object ZipSelected : OfflineAssistantCommand
    data object ShareSelected : OfflineAssistantCommand
    data object InvertSelection : OfflineAssistantCommand
    data class SelectPattern(val pattern: String) : OfflineAssistantCommand
    data class Extract(val name: String) : OfflineAssistantCommand
    data object BookmarkCurrent : OfflineAssistantCommand
    data object ToggleDualPane : OfflineAssistantCommand
    data object StorageSummary : OfflineAssistantCommand
    data object OpenRecycleBin : OfflineAssistantCommand
    data object OpenTags : OfflineAssistantCommand
    data object OpenPrivateSpace : OfflineAssistantCommand
    data object TagSelected : OfflineAssistantCommand
    data object OpenNetworkStorage : OfflineAssistantCommand
    data class InspectApk(val name: String) : OfflineAssistantCommand
    data class BrowseArchive(val name: String) : OfflineAssistantCommand
    data object ScanStorage : OfflineAssistantCommand
    data object ShowLastScan : OfflineAssistantCommand
    data object ResumeStorageScan : OfflineAssistantCommand
    data object FindLargeFiles : OfflineAssistantCommand
    data object FindOldFiles : OfflineAssistantCommand
    data object FindDuplicates : OfflineAssistantCommand
    data object CancelStorageScan : OfflineAssistantCommand
    data class FindCategory(val category: Category) : OfflineAssistantCommand
    data class FolderSize(val folderName: String) : OfflineAssistantCommand
    data class PreviewOrganize(val category: Category, val destinationPath: String, val move: Boolean = false) : OfflineAssistantCommand
}

object OfflineAssistantParser {
    const val HELP_TEXT = "Try: open recycle bin, open tags, open private space, open network storage, tag selected, inspect apk app.apk, browse archive backup.zip, plus: search photos, search content invoice, open folder Download, open report.pdf, go back, go up, show hidden, sort by size, select all, select report.pdf, copy, cut, paste, new folder Work, new file notes.txt, rename selected to notes-old.txt, delete selected, zip selected, share selected, extract archive.zip, undo, storage info, scan storage, show last scan, resume scan, find large files, find old files, find duplicates, find images, find videos, find audio, find documents, find apks, folder size Download, organize images to /storage/emulated/0/Pictures, move images to /storage/emulated/0/Pictures, or organize images to here (current folder), cancel scan."

    fun parse(raw: String): OfflineAssistantCommand? {
        val input = raw.trim().replace(Regex("\\s+"), " ")
        if (input.isEmpty() || input.length > 512) return null
        val value = input.lowercase(Locale.ROOT)
        fun tail(vararg prefixes: String): String? {
            val prefix = prefixes.firstOrNull { value.startsWith("$it ") } ?: return null
            return input.substring(prefix.length + 1).trim().takeIf { it.isNotBlank() }
        }

        return when {
            value in setOf("help", "commands", "madad", "sahayata") -> OfflineAssistantCommand.Help
            value in setOf("back", "go back", "piche jao", "peeche jao", "वापस जाओ") -> OfflineAssistantCommand.GoBack
            value in setOf("up", "go up", "parent folder", "upar jao", "ek folder upar", "ऊपर जाओ") -> OfflineAssistantCommand.GoUp
            value in setOf("refresh", "reload", "refresh folder", "folder refresh karo") -> OfflineAssistantCommand.Refresh
            value in setOf("show hidden", "show hidden files", "hidden files dikhao", "छिपी फाइलें दिखाओ") -> OfflineAssistantCommand.ShowHidden(true)
            value in setOf("hide hidden", "hide hidden files", "hidden files chhupao", "छिपी फाइलें छुपाओ") -> OfflineAssistantCommand.ShowHidden(false)
            value in setOf("select all", "sab select karo", "sabhi select karo", "सब चुनो") -> OfflineAssistantCommand.SelectAll
            value in setOf("clear selection", "deselect all", "selection hatao", "चयन हटाओ") -> OfflineAssistantCommand.ClearSelection
            value in setOf("copy", "copy selected", "selected copy karo", "कॉपी") -> OfflineAssistantCommand.Copy(false)
            value in setOf("cut", "cut selected", "selected cut karo", "कट") -> OfflineAssistantCommand.Copy(true)
            value in setOf("paste", "paste here", "yahan paste karo", "पेस्ट") -> OfflineAssistantCommand.Paste
            value in setOf("delete", "delete selected", "selected delete karo", "चुनी हुई फाइलें हटाओ") -> OfflineAssistantCommand.DeleteSelected
            value in setOf("undo", "undo last", "last action undo karo") -> OfflineAssistantCommand.Undo
            value in setOf("open recycle bin", "recycle bin kholo", "recently deleted") -> OfflineAssistantCommand.OpenRecycleBin
            value in setOf("open tags", "tags kholo", "tag manager kholo") -> OfflineAssistantCommand.OpenTags
            value in setOf("open private space", "private space kholo") -> OfflineAssistantCommand.OpenPrivateSpace
            value in setOf("tag selected", "selected ko tag karo", "tag lagao") -> OfflineAssistantCommand.TagSelected
            value in setOf("open network storage", "network storage kholo", "ftp sftp smb webdav") -> OfflineAssistantCommand.OpenNetworkStorage
            value.startsWith("inspect apk ") || value.startsWith("apk info ") -> tail("inspect apk", "apk info")?.let(OfflineAssistantCommand::InspectApk)
            value.startsWith("browse archive ") || value.startsWith("open archive ") -> tail("browse archive", "open archive")?.let(OfflineAssistantCommand::BrowseArchive)
            value in setOf("storage info", "storage", "storage dikhao", "kitni storage khali hai") -> OfflineAssistantCommand.StorageSummary
            value in setOf("scan storage", "scan all storage", "storage scan karo", "पूरी स्टोरेज स्कैन करो") -> OfflineAssistantCommand.ScanStorage
            value in setOf("show last scan", "last scan dikhao", "pichla scan dikhao") -> OfflineAssistantCommand.ShowLastScan
            value in setOf("resume scan", "continue scan", "scan resume karo", "स्कैन जारी रखो") -> OfflineAssistantCommand.ResumeStorageScan
            value in setOf("find large files", "large files", "badi files dhundo", "बड़ी फाइलें खोजो") -> OfflineAssistantCommand.FindLargeFiles
            value in setOf("find old files", "old files", "purani files dhundo", "पुरानी फाइलें खोजो") -> OfflineAssistantCommand.FindOldFiles
            value in setOf("find duplicates", "duplicate files", "same files dhundo", "डुप्लीकेट फाइलें खोजो") -> OfflineAssistantCommand.FindDuplicates
            value in setOf("cancel scan", "stop scan", "scan band karo") -> OfflineAssistantCommand.CancelStorageScan
            value in setOf("find images", "find photos", "photos dhundo", "तस्वीरें खोजो") -> OfflineAssistantCommand.FindCategory(Category.IMAGES)
            value in setOf("find videos", "videos dhundo", "वीडियो खोजो") -> OfflineAssistantCommand.FindCategory(Category.VIDEOS)
            value in setOf("find audio", "find music", "audio dhundo", "ऑडियो खोजो") -> OfflineAssistantCommand.FindCategory(Category.AUDIO)
            value in setOf("find documents", "find docs", "documents dhundo", "दस्तावेज खोजो") -> OfflineAssistantCommand.FindCategory(Category.DOCUMENTS)
            value in setOf("find apks", "find apk files", "apk dhundo", "एपीके खोजो") -> OfflineAssistantCommand.FindCategory(Category.APKS)
            value.startsWith("folder size ") || value.startsWith("size of folder ") || value.startsWith("folder ka size ") ->
                tail("folder size", "size of folder", "folder ka size")?.let(OfflineAssistantCommand::FolderSize)
            value.startsWith("organize ") || value.startsWith("move ") -> {
                val match = Regex("^(organize|move)\\s+(images|photos|videos|audio|music|documents|docs|apks)\\s+to\\s+(.+)$", RegexOption.IGNORE_CASE)
                    .matchEntire(input)
                match?.let {
                    val category = when (it.groupValues[2].lowercase(Locale.ROOT)) {
                        "images", "photos" -> Category.IMAGES
                        "videos" -> Category.VIDEOS
                        "audio", "music" -> Category.AUDIO
                        "documents", "docs" -> Category.DOCUMENTS
                        "apks" -> Category.APKS
                        else -> null
                    }
                    category?.let { value -> OfflineAssistantCommand.PreviewOrganize(value, it.groupValues[3].trim(), it.groupValues[1].equals("move", true)) }
                }
            }
            value in setOf("zip selected", "compress selected", "selected zip karo") -> OfflineAssistantCommand.ZipSelected
            value in setOf("share selected", "selected share karo", "share") -> OfflineAssistantCommand.ShareSelected
            value in setOf("invert selection", "selection ulta karo") -> OfflineAssistantCommand.InvertSelection
            value in setOf("bookmark current", "bookmark this folder") -> OfflineAssistantCommand.BookmarkCurrent
            value in setOf("toggle dual pane", "dual pane on off") -> OfflineAssistantCommand.ToggleDualPane
            value.startsWith("search content ") || value.startsWith("find text ") ->
                tail("search content", "find text")?.let(OfflineAssistantCommand::SearchContent)
            value.startsWith("search ") || value.startsWith("find ") || value.startsWith("dhundo ") || value.startsWith("खोज ") ->
                tail("search", "find", "dhundo", "खोज")?.let(OfflineAssistantCommand::Search)
            value.startsWith("open folder ") || value.startsWith("folder kholo ") ->
                tail("open folder", "folder kholo")?.let(OfflineAssistantCommand::OpenFolder)
            value.startsWith("open ") -> tail("open")?.let(OfflineAssistantCommand::OpenItem)
            value.startsWith("select pattern ") -> tail("select pattern")?.let(OfflineAssistantCommand::SelectPattern)
            value.startsWith("select file ") || value.startsWith("select folder ") || value.startsWith("select ") ->
                tail("select file", "select folder", "select")?.let(OfflineAssistantCommand::SelectName)
            value.startsWith("new folder ") || value.startsWith("create folder ") || value.startsWith("naya folder ") ->
                tail("new folder", "create folder", "naya folder")?.let(OfflineAssistantCommand::NewFolder)
            value.startsWith("new file ") || value.startsWith("create file ") || value.startsWith("nayi file ") ->
                tail("new file", "create file", "nayi file")?.let(OfflineAssistantCommand::NewFile)
            value.startsWith("rename selected to ") || value.startsWith("rename to ") || value.startsWith("rename ") ->
                tail("rename selected to", "rename to", "rename")?.let(OfflineAssistantCommand::RenameSelected)
            value.startsWith("extract ") -> tail("extract")?.let(OfflineAssistantCommand::Extract)
            value in setOf("sort by name", "name se sort", "naam se sort") -> OfflineAssistantCommand.Sort(SortBy.NAME)
            value in setOf("sort by size", "size se sort", "badi file pehle") -> OfflineAssistantCommand.Sort(SortBy.SIZE)
            value in setOf("sort by date", "date se sort", "recent first", "latest first") -> OfflineAssistantCommand.Sort(SortBy.DATE)
            value in setOf("sort by type", "type se sort", "extension se sort") -> OfflineAssistantCommand.Sort(SortBy.TYPE)
            else -> null
        }
    }
}
