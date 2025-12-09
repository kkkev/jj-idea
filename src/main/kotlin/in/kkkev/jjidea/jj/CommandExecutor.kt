package `in`.kkkev.jjidea.jj

/**
 * Abstraction for executing jujutsu commands.
 * This interface allows for different implementations (CLI, native library, etc.)
 */
interface CommandExecutor {
    /**
     * Result of a jujutsu command execution
     */
    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    /**
     * Get the status of the working copy or a specific revision
     * @return List of file statuses
     */
    fun status(): CommandResult

    /**
     * Get the diff for a specific file
     * @param filePath Path relative to root
     * @return Diff output
     */
    fun diff(filePath: String): CommandResult

    /**
     * Get summary of changes for a specific revision
     * @param revision Revision (e.g., "@", "@-", commit hash)
     * @return Summary of file changes
     */
    fun diffSummary(revision: Revision): CommandResult

    /**
     * Get the content of a file at a specific revision
     * @param filePath Path relative to root
     * @param revision Revision (e.g., "@", "@-", commit hash)
     * @return File content
     */
    fun show(filePath: String, revision: Revision): CommandResult

    /**
     * Check if jujutsu is available and working
     * @return true if jj command is available
     */
    fun isAvailable(): Boolean

    /**
     * Get the version of jujutsu
     * @return Version string or null if not available
     */
    fun version(): String?

    /**
     * Set the description for a commit (default: working copy @)
     * @param message The description message
     * @param revision The revision to describe (default: "@")
     * @return Command result
     */
    fun describe(message: String, revision: Revision = WorkingCopy): CommandResult

    /**
     * Create a new change on top of the current one
     * @param message Optional description for the new change
     * @return Command result
     */
    fun new(message: String? = null): CommandResult

    /**
     * Get the log for specific revset
     * @param revset Revisions to show (e.g., "@", "@-")
     * @param template Template for output (e.g., "description", "change_id")
     * @param filePaths Optional file paths to filter log (e.g., "src/main.kt")
     * @return Command result with log output
     */
    fun log(revset: Revset = Expression.ALL, template: String? = null, filePaths: List<String> = emptyList()): CommandResult

    /**
     * Get line-by-line annotation (blame) for a file
     * @param filePath Path relative to root
     * @param revision Revision from which to start annotating (default: "@")
     * @param template Template for annotation output
     * @return Annotation output with change info per line
     */
    fun annotate(filePath: String, revision: Revision = WorkingCopy, template: String? = null): CommandResult

    /**
     * List all bookmarks in the repository
     * @return Command result with bookmark list (format: "bookmark-name: change-id")
     */
    fun bookmarkList(): CommandResult
}