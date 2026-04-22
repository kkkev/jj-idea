package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vcs.FilePath

/**
 * Service for querying Jujutsu log and change information.
 * Encapsulates template generation, command execution, and parsing.
 * This interface allows future replacement with a native JJ library.
 */
interface LogService {
    /**
     * Get log entries with full metadata (author, committer, timestamps)
     * @param revset Revset to query (default: "all()")
     * @param filePaths Optional file paths to filter by
     * @return List of log entries with complete metadata
     */
    fun getLog(
        revset: Revset = Expression.ALL,
        filePaths: List<FilePath> = emptyList(),
        limit: Int? = null
    ): Result<List<LogEntry>>

    /**
     * Get log entries with minimal metadata (no author/committer info)
     * More efficient when only basic commit info is needed.
     * @param revset Revset to query (default: "all()")
     * @param filePaths Optional file paths to filter by
     * @return List of log entries with basic metadata
     */
    fun getLogBasic(
        revset: Revset = Expression.ALL,
        filePaths: List<FilePath> = emptyList(),
        limit: Int? = null
    ): Result<List<LogEntry>>

    fun getLogAndFileStatuses(
        revset: Revset = Expression.ALL,
        filePath: FilePath,
        limit: Int? = null
    ): Result<List<FileRevision>>

    /**
     * Get file changes for a specific log entry.
     * @param logEntry log entry pointing to the revision whose file changes should be retrieved
     * @param filePath if specified, path of the single file change to get
     * @return List of file changes
     */
    fun getFileChanges(logEntry: LogEntry, filePath: FilePath? = null): Result<List<FileChange>>

    /**
     * Get all bookmarks in the repository
     * @return List of bookmarks with their associated change IDs
     */
    fun getBookmarks(): Result<List<BookmarkItem>>
}
