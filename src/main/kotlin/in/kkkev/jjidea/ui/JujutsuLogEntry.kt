package `in`.kkkev.jjidea.ui

/**
 * Represents a single entry in the jj log
 */
data class JujutsuLogEntry(
    val changeId: String,
    val commitId: String,
    val description: String,
    val shortChangeIdPrefix: String,
    val bookmarks: List<String> = emptyList(),
    val parentIds: List<String> = emptyList(),
    val isWorkingCopy: Boolean = false,
    val hasConflict: Boolean = false,
    val isEmpty: Boolean = false,
    val isUndescribed: Boolean = false
) {
    /**
     * Get the formatted change ID with short prefix separated
     */
    fun getFormattedChangeId() = JujutsuCommitFormatter.formatChangeId(changeId, shortChangeIdPrefix)

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
