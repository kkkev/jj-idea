package `in`.kkkev.jjidea.jj

/**
 * Service for querying Jujutsu log and change information.
 * Encapsulates template generation, command execution, and parsing.
 * This interface allows future replacement with a native JJ library.
 */
interface JujutsuLogService {

    /**
     * Get log entries with full metadata (author, committer, timestamps)
     * @param revisions Revset to query (default: "all()")
     * @param filePaths Optional file paths to filter by
     * @return List of log entries with complete metadata
     */
    fun getLog(revisions: String = "all()", filePaths: List<String> = emptyList()): Result<List<JujutsuLogEntry>>

    /**
     * Get log entries with minimal metadata (no author/committer info)
     * More efficient when only basic commit info is needed.
     * @param revisions Revset to query (default: "all()")
     * @param filePaths Optional file paths to filter by
     * @return List of log entries with basic metadata
     */
    fun getLogBasic(revisions: String = "all()", filePaths: List<String> = emptyList()): Result<List<JujutsuLogEntry>>

    /**
     * Get refs (bookmarks and working copy marker) for all commits
     * @return List of refs
     */
    fun getRefs(): Result<List<JujutsuRef>>

    /**
     * Get minimal commit information (change IDs and parent relationships only)
     * Used for building commit graphs efficiently.
     * @param revisions Revset to query (default: "all()")
     * @return List of minimal commit info
     */
    fun getCommitGraph(revisions: String = "all()"): Result<List<CommitGraphNode>>

    /**
     * Get file changes for a specific revision
     * @param revision Revision identifier (e.g., "@", change ID)
     * @return List of file changes
     */
    fun getFileChanges(revision: String): Result<List<FileChange>>

    /**
     * Represents a ref (bookmark or working copy marker)
     */
    data class JujutsuRef(
        val changeId: ChangeId,
        val name: String,
        val type: RefType
    )

    enum class RefType {
        BOOKMARK,
        WORKING_COPY
    }

    /**
     * Minimal commit information for building graphs
     */
    data class CommitGraphNode(
        val changeId: ChangeId,
        val parentIds: List<ChangeId>,
        val timestamp: Long
    )
}
