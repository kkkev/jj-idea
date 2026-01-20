package `in`.kkkev.jjidea.jj

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
    fun getLog(revset: Revset = Expression.ALL, filePaths: List<String> = emptyList()): Result<List<LogEntry>>

    /**
     * Get log entries with minimal metadata (no author/committer info)
     * More efficient when only basic commit info is needed.
     * @param revset Revset to query (default: "all()")
     * @param filePaths Optional file paths to filter by
     * @return List of log entries with basic metadata
     */
    fun getLogBasic(revset: Revset = Expression.ALL, filePaths: List<String> = emptyList()): Result<List<LogEntry>>

    /**
     * Get refs (bookmarks and working copy marker) for all commits
     * @return List of refs
     */
    fun getRefs(): Result<List<RefAtRevision>>

    /**
     * Get minimal commit information (change IDs and parent relationships only)
     * Used for building commit graphs efficiently.
     * @param revset Revset to query (default: "all()")
     * @return List of minimal commit info
     */
    fun getCommitGraph(revset: Revset = Expression.ALL): Result<List<CommitGraphNode>>

    /**
     * Get file changes for a specific revision
     * @param revision Single revision (e.g., "@", change ID, bookmark)
     * @return List of file changes
     */
    fun getFileChanges(revision: Revision): Result<List<FileChange>>

    /**
     * Get all bookmarks in the repository
     * @return List of bookmarks with their associated change IDs
     */
    fun getBookmarks(): Result<List<BookmarkItem>>
}
