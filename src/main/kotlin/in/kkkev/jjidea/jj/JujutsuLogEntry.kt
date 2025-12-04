package `in`.kkkev.jjidea.jj

import `in`.kkkev.jjidea.ui.JujutsuCommitFormatter

/**
 * Represents a single entry in the jj log.
 * This is a pure data class / DTO representing parsed JJ log output.
 * Conversion to VCS framework objects (VcsUser, VcsCommitMetadata, etc.)
 * is handled by JujutsuCommitMetadataBase and its subclasses.
 */
data class JujutsuLogEntry(
    val changeId: ChangeId,
    val commitId: String,
    val description: String,
    val bookmarks: List<String> = emptyList(),
    val parentIds: List<ChangeId> = emptyList(),
    val isWorkingCopy: Boolean = false,
    val hasConflict: Boolean = false,
    val isEmpty: Boolean = false,
    val isUndescribed: Boolean = false,
    val authorTimestamp: Long = 0,  // Unix timestamp in milliseconds
    val committerTimestamp: Long = 0,  // Unix timestamp in milliseconds
    val authorName: String = "",
    val authorEmail: String = "",
    val committerName: String = "",
    val committerEmail: String = ""
) {
    /**
     * Get the formatted change ID with short prefix separated
     */
    fun getFormattedChangeId() = JujutsuCommitFormatter.format(changeId)

    /**
     * Get markers for special states (conflict, empty, undescribed)
     */
    fun getMarkers(): List<String> {
        val markers = mutableListOf<String>()

        if (hasConflict) {
            markers.add("conflict")
        }
        if (isEmpty) {
            markers.add("empty")
        }
        if (isUndescribed) {
            markers.add("(no description)")
        }

        return markers
    }

    /**
     * Get a display-friendly description
     */
    fun getDisplayDescription() = when {
        hasConflict && description.isEmpty() -> "(conflict)"
        isEmpty -> "(empty)"
        isUndescribed -> "(no description)"
        description.isEmpty() -> "(no description)"
        else -> description
    }

    /**
     * Get bookmark display string
     */
    fun getBookmarkDisplay(): String = bookmarks.joinToString(", ")

    /**
     * Get parent IDs display string
     */
    fun getParentIdsDisplay(): String = parentIds.joinToString(", ")
}