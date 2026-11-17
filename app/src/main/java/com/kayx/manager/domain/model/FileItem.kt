package com.kayx.manager.domain.model

data class FileItem(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val hidden: Boolean
)

enum class SortBy { NAME, TYPE, SIZE, DATE }

enum class SearchMode(val label: String) { CONTAINS("Contains"), EXACT("Exact"), REGEX("Regex") }
