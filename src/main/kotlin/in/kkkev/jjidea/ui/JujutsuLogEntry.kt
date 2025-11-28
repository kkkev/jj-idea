package `in`.kkkev.jjidea.ui

/**
 * Represents a single entry in the jj log
 */
data class JujutsuLogEntry(
    val changeId: String,
    val commitId: String,
    val description: String,
    val bookmarks: List<String>,
    val isWorkingCopy: Boolean,
    val hasConflict: Boolean,
    val isEmpty: Boolean,
    val isUndescribed: Boolean,
    val shortChangeIdPrefix: String
) {
    /**
     * Get the formatted change ID with short prefix separated
     */
    fun getFormattedChangeId(): JujutsuCommitFormatter.FormattedChangeId {
        return JujutsuCommitFormatter.formatChangeId(changeId, shortChangeIdPrefix)
    }

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
    fun getDisplayDescription(): String {
        return when {
            hasConflict && description.isEmpty() -> "(conflict)"
            isEmpty -> "(empty)"
            isUndescribed -> "(no description)"
            description.isEmpty() -> "(no description)"
            else -> description
        }
    }

    /**
     * Get bookmark display string
     */
    fun getBookmarkDisplay(): String {
        return if (bookmarks.isEmpty()) {
            ""
        } else {
            bookmarks.joinToString(", ")
        }
    }
}
