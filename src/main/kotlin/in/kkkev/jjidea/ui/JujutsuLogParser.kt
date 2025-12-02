package `in`.kkkev.jjidea.ui

/**
 * Parses jj log output into structured log entries
 * Uses null byte (\0) as field separator to handle descriptions with special characters
 */
object JujutsuLogParser {

    private const val FIELD_SEPARATOR = "\u0000" // Null byte

    /**
     * Parse a single line from jj log output
     * Expected format: changeId\0shortId\0commitId\0description\0bookmarks\0parentIds\0isWorkingCopy\0hasConflict\0isEmpty\0
     * Note: Trailing \0 may create an empty 10th field, we accept both 9 and 10 fields
     *
     * @param line The line to parse
     * @return Parsed log entry
     */
    fun parseLogLine(line: String): JujutsuLogEntry {
        val parts = line.split(FIELD_SEPARATOR)
        require(parts.size >= 9) {
            "Invalid log line format: expected at least 9 parts (8 null bytes), got ${parts.size}"
        }

        val changeId = parts[0]
        val changeIdShort = parts[1]
        val commitId = parts[2]
        val description = parts[3]
        val bookmarks = if (parts[4].isNotEmpty()) {
            parts[4].split(",").map { it.trim() }
        } else {
            emptyList()
        }
        val parentIds = if (parts[5].isNotEmpty()) {
            // Format is "fullId~shortId, fullId~shortId"
            // Extract just the full IDs (shortId is computed during rendering with first 2 chars as fallback)
            parts[5].split(",").map { it.trim().substringBefore("~") }
        } else {
            emptyList()
        }
        val isWorkingCopy = parts[6].toBoolean()
        val hasConflict = parts[7].toBoolean()
        val isEmpty = parts[8].toBoolean()
        // parts[9] may be present and empty from a trailing null byte

        // Determine if undescribed (empty description and not explicitly marked as empty commit)
        val isUndescribed = description.isEmpty() && !isEmpty

        return JujutsuLogEntry(
            changeId = changeId,
            commitId = commitId,
            description = description,
            shortChangeIdPrefix = changeIdShort,
            bookmarks = bookmarks,
            parentIds = parentIds,
            isWorkingCopy = isWorkingCopy,
            hasConflict = hasConflict,
            isEmpty = isEmpty,
            isUndescribed = isUndescribed
        )
    }

    /**
     * Parse jj log output into log entries
     * Simply splits on null bytes and groups into chunks of 9 fields
     */
    fun parseLog(logOutput: String): List<JujutsuLogEntry> {
        val trimmed = logOutput.trim()
        if (trimmed.isBlank()) return emptyList()

        // Split entire output on null bytes
        val fields = trimmed.split(FIELD_SEPARATOR)

        // Group into chunks of 9 (9 data fields per entry)
        // The trailing null byte at the very end creates an empty final element which we filter out
        return fields.chunked(9)
            .filter { it.size == 9 }  // Only complete entries (exactly 9 fields)
            .map { chunk ->
                JujutsuLogEntry(
                    changeId = chunk[0],
                    commitId = chunk[2],
                    description = chunk[3],
                    shortChangeIdPrefix = chunk[1],
                    bookmarks = if (chunk[4].isNotEmpty()) chunk[4].split(",").map { it.trim() } else emptyList(),
                    parentIds = if (chunk[5].isNotEmpty()) chunk[5].split(",").map { it.trim().substringBefore("~") } else emptyList(),
                    isWorkingCopy = chunk[6].toBoolean(),
                    hasConflict = chunk[7].toBoolean(),
                    isEmpty = chunk[8].toBoolean(),
                    isUndescribed = chunk[3].isEmpty() && !chunk[8].toBoolean()
                )
            }
    }
}
