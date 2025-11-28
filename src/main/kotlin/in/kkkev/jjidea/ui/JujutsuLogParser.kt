package `in`.kkkev.jjidea.ui

/**
 * Parses jj log output into structured log entries
 */
object JujutsuLogParser {

    /**
     * Parse a single line from jj log output
     * Expected format: changeId|commitId|description|bookmarks|isWorkingCopy|hasConflict|isEmpty
     *
     * @param line The line to parse
     * @param shortPrefixLength The length of the short prefix to extract
     * @return Parsed log entry
     */
    fun parseLogLine(line: String, shortPrefixLength: Int = 2): JujutsuLogEntry {
        val parts = line.split("|")
        require(parts.size >= 7) {
            "Invalid log line format: expected at least 7 parts, got ${parts.size}"
        }

        val changeId = parts[0]
        val commitId = parts[1]
        val description = parts[2]
        val bookmarks = if (parts[3].isNotEmpty()) {
            parts[3].split(",").map { it.trim() }
        } else {
            emptyList()
        }
        val isWorkingCopy = parts[4].toBoolean()
        val hasConflict = parts[5].toBoolean()
        val isEmpty = parts[6].toBoolean()

        // Determine if undescribed (empty description and not explicitly marked as empty commit)
        val isUndescribed = description.isEmpty() && !isEmpty

        val shortPrefix = getShortPrefix(changeId, shortPrefixLength)

        return JujutsuLogEntry(
            changeId = changeId,
            commitId = commitId,
            description = description,
            bookmarks = bookmarks,
            isWorkingCopy = isWorkingCopy,
            hasConflict = hasConflict,
            isEmpty = isEmpty,
            isUndescribed = isUndescribed,
            shortChangeIdPrefix = shortPrefix
        )
    }

    /**
     * Parse multiple lines from jj log output
     */
    fun parseLog(logOutput: String, shortPrefixLength: Int = 2) = logOutput.trim().lines()
        .filter { it.isNotBlank() }
        .map { parseLogLine(it, shortPrefixLength) }

    /**
     * Get the short prefix of a change ID
     * @param changeId The full change ID
     * @param prefixLength The length of the prefix to extract
     * @return The short prefix
     */
    fun getShortPrefix(changeId: String, prefixLength: Int) = changeId.take(prefixLength)
}
