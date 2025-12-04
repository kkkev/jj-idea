package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.JujutsuLogEntry

/**
 * Parses jj log output into structured log entries
 * Uses null byte (\0) as field separator to handle descriptions with special characters
 */
object JujutsuLogParser {

    private const val FIELD_SEPARATOR = "\u0000" // Null byte

    /**
     * Parse a single line from jj log output
     * Expected format: changeId\0shortId\0commitId\0description\0bookmarks\0parentIds\0isWorkingCopy\0hasConflict\0isEmpty\0authorTimestamp\0committerTimestamp\0authorName\0authorEmail\0committerName\0committerEmail\0
     * Note: Trailing \0 may create an empty 16th field, we accept both 15 and 16 fields
     *
     * @param line The line to parse
     * @return Parsed log entry
     */
    fun parseLogLine(line: String): JujutsuLogEntry {
        val parts = line.split(FIELD_SEPARATOR)
        require(parts.size >= 15) {
            "Invalid log line format: expected at least 15 parts (14 null bytes), got ${parts.size}"
        }

        val fullChangeId = parts[0]
        val shortChangeId = parts[1]
        val commitId = parts[2]
        val description = parts[3]
        val bookmarks = if (parts[4].isNotEmpty()) {
            parts[4].split(",").map { it.trim() }
        } else {
            emptyList()
        }
        val parentIds = if (parts[5].isNotEmpty()) {
            // Format is "fullId~shortId, fullId~shortId"
            // Extract both full and short IDs
            parts[5].split(",").map { parent ->
                val trimmed = parent.trim()
                val parts = trimmed.split("~")
                if (parts.size == 2) {
                    ChangeId(parts[0], parts[1])
                } else {
                    // Fallback if format is unexpected
                    ChangeId(trimmed)
                }
            }
        } else {
            emptyList()
        }
        val isWorkingCopy = parts[6].toBoolean()
        val hasConflict = parts[7].toBoolean()
        val isEmpty = parts[8].toBoolean()
        val authorTimestamp = parts[9].toLongOrNull()?.times(1000) ?: 0L  // Convert seconds to milliseconds
        val committerTimestamp = parts[10].toLongOrNull()?.times(1000) ?: 0L  // Convert seconds to milliseconds
        val authorName = parts[11]
        val authorEmail = parts[12]
        val committerName = parts[13]
        val committerEmail = parts[14]
        // parts[15] may be present and empty from a trailing null byte

        // Determine if undescribed (empty description and not explicitly marked as empty commit)
        val isUndescribed = description.isEmpty() && !isEmpty

        return JujutsuLogEntry(
            changeId = ChangeId(fullChangeId, shortChangeId),
            commitId = commitId,
            description = description,
            bookmarks = bookmarks,
            parentIds = parentIds,
            isWorkingCopy = isWorkingCopy,
            hasConflict = hasConflict,
            isEmpty = isEmpty,
            isUndescribed = isUndescribed,
            authorTimestamp = authorTimestamp,
            committerTimestamp = committerTimestamp,
            authorName = authorName,
            authorEmail = authorEmail,
            committerName = committerName,
            committerEmail = committerEmail
        )
    }

    /**
     * Parse jj log output into log entries
     * Simply splits on null bytes and groups into chunks of 15 fields
     */
    fun parseLog(logOutput: String): List<JujutsuLogEntry> {
        val trimmed = logOutput.trim()
        if (trimmed.isBlank()) return emptyList()

        // Split entire output on null bytes
        val fields = trimmed.split(FIELD_SEPARATOR)

        // Group into chunks of 15 (15 data fields per entry)
        // The trailing null byte at the very end creates an empty final element which we filter out
        return fields.chunked(15)
            .filter { it.size == 15 }  // Only complete entries (exactly 15 fields)
            .map { chunk ->
                JujutsuLogEntry(
                    changeId = ChangeId(chunk[0], chunk[1]),
                    commitId = chunk[2],
                    description = chunk[3],
                    bookmarks = if (chunk[4].isNotEmpty()) chunk[4].split(",").map { it.trim() } else emptyList(),
                    // Format is "fullId~shortId, fullId~shortId" - extract both parts
                    parentIds = if (chunk[5].isNotEmpty()) {
                        chunk[5].split(",").map { parent ->
                            val parts = parent.trim().split("~")
                            if (parts.size == 2) {
                                ChangeId(parts[0], parts[1])
                            } else {
                                ChangeId(parts[0])
                            }
                        }
                    } else {
                        emptyList()
                    },
                    isWorkingCopy = chunk[6].toBoolean(),
                    hasConflict = chunk[7].toBoolean(),
                    isEmpty = chunk[8].toBoolean(),
                    isUndescribed = chunk[3].isEmpty() && !chunk[8].toBoolean(),
                    authorTimestamp = chunk[9].toLongOrNull()?.times(1000) ?: 0L,
                    committerTimestamp = chunk[10].toLongOrNull()?.times(1000) ?: 0L,
                    authorName = chunk[11],
                    authorEmail = chunk[12],
                    committerName = chunk[13],
                    committerEmail = chunk[14]
                )
            }
    }
}