package `in`.kkkev.jjidea.ui

/**
 * Formats commit identifiers in the jj way:
 * - Short unique prefix is distinguishable (for bold display in UI)
 * - Working copy shows both commit name and @
 */
object JujutsuCommitFormatter {

    /**
     * Represents a formatted change ID with its parts separated
     */
    data class FormattedChangeId(
        val shortPart: String,
        val restPart: String
    ) {
        val full: String get() = shortPart + restPart
    }

    /**
     * Format a change ID with its short unique prefix
     * @param fullChangeId The full change ID (e.g., "qpvuntsm")
     * @param shortPrefix The short unique prefix (e.g., "qp")
     * @return Formatted change ID with separated parts
     */
    fun formatChangeId(fullChangeId: String, shortPrefix: String): FormattedChangeId {
        require(fullChangeId.startsWith(shortPrefix)) {
            "Change ID '$fullChangeId' must start with short prefix '$shortPrefix'"
        }

        val restPart = fullChangeId.substring(shortPrefix.length)
        return FormattedChangeId(shortPrefix, restPart)
    }

    /**
     * Format the working copy display showing both description and @
     * @param changeId The change ID
     * @param shortPrefix The short unique prefix
     * @param description The commit description (empty string if undescribed)
     * @return Formatted string for display
     */
    fun formatWorkingCopy(changeId: String, shortPrefix: String, description: String): String {
        val formatted = formatChangeId(changeId, shortPrefix)
        val desc = if (description.isEmpty()) "(empty)" else description

        return "${formatted.full} @ - $desc"
    }

    /**
     * Extract the short prefix that was used in display
     * @param fullId The full change ID
     * @param displayedShort The short form that was displayed
     * @return The short prefix
     */
    fun extractShortPrefix(fullId: String, displayedShort: String): String {
        require(fullId.startsWith(displayedShort)) {
            "Full ID '$fullId' must start with displayed short '$displayedShort'"
        }
        return displayedShort
    }
}
