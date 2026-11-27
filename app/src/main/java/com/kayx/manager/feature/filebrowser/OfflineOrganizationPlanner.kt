package com.kayx.manager.feature.filebrowser

import com.kayx.manager.data.filesystem.StorageLocation
import com.kayx.manager.data.storage.Category
import com.kayx.manager.data.storage.OfflineStorageInventory
import java.io.File
import java.nio.file.Files

/**
 * Immutable, preview-first plan for copying category matches into one explicit local folder.
 * SAF sources carry opaque, tree-relative stable keys and are revalidated by the ViewModel
 * immediately before the provider transfer starts.
 */
internal data class OfflineOrganizationPlan(
    val category: Category,
    val destinationPath: String,
    val files: List<OfflineStorageInventory.Entry>,
    val totalBytes: Long
)

internal object OfflineOrganizationPlanner {
    const val MAX_FILES_PER_OPERATION = 100

    fun create(
        entries: List<OfflineStorageInventory.Entry>,
        category: Category,
        destinationPath: String
    ): Result<OfflineOrganizationPlan> = runCatching {
        val requestedDestination = File(destinationPath)
        require(requestedDestination.isAbsolute) { "Destination must be an absolute folder path." }
        val destination = requestedDestination.canonicalFile
        require(destination.isDirectory && destination.canWrite()) { "Destination folder is unavailable or not writable." }

        val matches = entries.asSequence()
            .filter { it.category == category }
            .map { entry ->
                if (entry.saf) {
                    val source = StorageLocation.fromStableKey(entry.key) as? StorageLocation.Saf
                    require(source != null && source.relativePath.isNotBlank()) {
                        "A SAF source has no valid tree-relative key: ${entry.name}"
                    }
                } else {
                    val file = File(entry.path)
                    require(!Files.isSymbolicLink(file.toPath())) { "Symbolic links cannot be organized." }
                    val canonical = file.canonicalFile
                    require(canonical.path == file.absolutePath && canonical.isFile && canonical.canRead()) {
                        "A source file is unavailable or changed: ${entry.name}"
                    }
                    require(canonical.parentFile?.canonicalPath != destination.path) {
                        "A source file is already in the destination folder."
                    }
                    require(canonical.length() == entry.size && canonical.lastModified() == entry.modified) {
                        "A source file changed during scanning: ${entry.name}"
                    }
                }
                entry
            }
            .distinctBy { it.key }
            .toList()

        require(matches.isNotEmpty()) { "No accessible ${category.label.lowercase()} files were found." }
        require(matches.size <= MAX_FILES_PER_OPERATION) {
            "Found ${matches.size} files. Narrow the search; one operation is limited to $MAX_FILES_PER_OPERATION files."
        }
        OfflineOrganizationPlan(category, destination.path, matches, matches.sumOf { it.size })
    }

    /** Local files are checked here; SAF providers are authoritatively revalidated by the VM at confirmation. */
    fun isStillValid(plan: OfflineOrganizationPlan): Boolean = runCatching {
        val destination = File(plan.destinationPath).canonicalFile
        if (!destination.isDirectory || !destination.canWrite() || destination.path != plan.destinationPath) return false
        plan.files.isNotEmpty() && plan.files.size <= MAX_FILES_PER_OPERATION &&
            plan.files.all { entry ->
                if (entry.saf) {
                    (StorageLocation.fromStableKey(entry.key) as? StorageLocation.Saf)
                        ?.relativePath?.isNotBlank() == true
                } else {
                    val file = File(entry.path)
                    !Files.isSymbolicLink(file.toPath()) &&
                        file.canonicalFile.path == entry.path && file.isFile && file.canRead() &&
                        file.length() == entry.size && file.lastModified() == entry.modified &&
                        file.parentFile?.canonicalPath != destination.path
                }
            }
    }.getOrDefault(false)
}
