package com.kayx.manager.feature.filebrowser

import com.kayx.manager.domain.model.SortBy
import java.util.Locale

/**
 * Deterministic command grammar for KayX's fully local assistant.
 * It never invokes a hosted model, evaluates code, or interprets arbitrary shell commands.
 */
sealed interface OfflineAssistantCommand {
    data object Help : OfflineAssistantCommand
    data class Search(val query: String) : OfflineAssistantCommand
    data class OpenFolder(val name: String) : OfflineAssistantCommand
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
    data object StorageSummary : OfflineAssistantCommand
}

object OfflineAssistantParser {
    const val HELP_TEXT = "Try: search photos, open folder Download, go back, go up, show hidden, sort by size, select all, select report.pdf, copy, cut, paste, new folder Work, new file notes.txt, rename selected to notes-old.txt, delete selected, undo, storage info."

    fun parse(raw: String): OfflineAssistantCommand? {
        val input = raw.trim().replace(Regex("\\s+"), " ")
        if (input.isEmpty() || input.length > 512) return null
        val value = input.lowercase(Locale.ROOT)
        fun tail(vararg prefixes: String): String? = prefixes.firstNotNullOfOrNull { prefix ->
            value.takeIf { it.startsWith("$prefix ") }?.substring(prefix.length + 1)?.trim()
        }?.takeIf { it.isNotBlank() }?.let { input.substring(input.length - it.length) }

        return when {
            value in setOf("help", "commands", "madad", "sahayata") -> OfflineAssistantCommand.Help
            value in setOf("back", "go back", "piche jao", "peeche jao") -> OfflineAssistantCommand.GoBack
            value in setOf("up", "go up", "parent folder", "upar jao", "ek folder upar") -> OfflineAssistantCommand.GoUp
            value in setOf("refresh", "reload", "refresh folder", "folder refresh karo") -> OfflineAssistantCommand.Refresh
            value in setOf("show hidden", "show hidden files", "hidden files dikhao") -> OfflineAssistantCommand.ShowHidden(true)
            value in setOf("hide hidden", "hide hidden files", "hidden files chhupao") -> OfflineAssistantCommand.ShowHidden(false)
            value in setOf("select all", "sab select karo", "sabhi select karo") -> OfflineAssistantCommand.SelectAll
            value in setOf("clear selection", "deselect all", "selection hatao") -> OfflineAssistantCommand.ClearSelection
            value in setOf("copy", "copy selected", "selected copy karo") -> OfflineAssistantCommand.Copy(false)
            value in setOf("cut", "cut selected", "selected cut karo") -> OfflineAssistantCommand.Copy(true)
            value in setOf("paste", "paste here", "yahan paste karo") -> OfflineAssistantCommand.Paste
            value in setOf("delete", "delete selected", "selected delete karo") -> OfflineAssistantCommand.DeleteSelected
            value in setOf("undo", "undo last", "last action undo karo") -> OfflineAssistantCommand.Undo
            value in setOf("storage info", "storage", "storage dikhao", "kitni storage khali hai") -> OfflineAssistantCommand.StorageSummary
            value.startsWith("search ") || value.startsWith("find ") || value.startsWith("dhundo ") || value.startsWith("खोज ") ->
                tail("search", "find", "dhundo", "खोज")?.let(OfflineAssistantCommand::Search)
            value.startsWith("open folder ") || value.startsWith("folder kholo ") || value.startsWith("open ") ->
                tail("open folder", "folder kholo", "open")?.let(OfflineAssistantCommand::OpenFolder)
            value.startsWith("select ") || value.startsWith("select file ") || value.startsWith("select folder ") ->
                tail("select file", "select folder", "select")?.let(OfflineAssistantCommand::SelectName)
            value.startsWith("new folder ") || value.startsWith("create folder ") || value.startsWith("naya folder ") ->
                tail("new folder", "create folder", "naya folder")?.let(OfflineAssistantCommand::NewFolder)
            value.startsWith("new file ") || value.startsWith("create file ") || value.startsWith("nayi file ") ->
                tail("new file", "create file", "nayi file")?.let(OfflineAssistantCommand::NewFile)
            value.startsWith("rename selected to ") || value.startsWith("rename to ") || value.startsWith("rename ") ->
                tail("rename selected to", "rename to", "rename")?.let(OfflineAssistantCommand::RenameSelected)
            value in setOf("sort by name", "name se sort", "naam se sort") -> OfflineAssistantCommand.Sort(SortBy.NAME)
            value in setOf("sort by size", "size se sort", "badi file pehle") -> OfflineAssistantCommand.Sort(SortBy.SIZE)
            value in setOf("sort by date", "date se sort", "recent first", "latest first") -> OfflineAssistantCommand.Sort(SortBy.DATE)
            value in setOf("sort by type", "type se sort", "extension se sort") -> OfflineAssistantCommand.Sort(SortBy.TYPE)
            else -> null
        }
    }
}
