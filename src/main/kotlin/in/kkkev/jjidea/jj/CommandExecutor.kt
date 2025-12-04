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
     * @param revision Revision to get status for (default: working copy)
     * @return List of file statuses
     */
    fun status(revision: String? = null): CommandResult

    /**
     * Get the diff for a specific file
     * @param filePath Path relative to root
     * @return Diff output
     */
    fun diff(filePath: String): CommandResult

    /**
     * Get summary of changes for a specific revision
     * @param revision Revision identifier (e.g., "@", "@-", commit hash)
     * @return Summary of file changes
     */
    fun diffSummary(revision: String): CommandResult

    /**
     * Get the content of a file at a specific revision
     * @param filePath Path relative to root
     * @param revision Revision identifier (e.g., "@", "@-", commit hash)
     * @return File content
     */
    fun show(filePath: String, revision: String): CommandResult

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
    fun describe(message: String, revision: String = "@"): CommandResult

    /**
     * Create a new change on top of the current one
     * @param message Optional description for the new change
     * @return Command result
     */
    fun new(message: String? = null): CommandResult

    /**
     * Get the log for specific revisions
     * @param revisions Revisions to show (e.g., "@", "@-")
     * @param template Template for output (e.g., "description", "change_id")
     * @param filePaths Optional file paths to filter log (e.g., "src/main.kt")
     * @return Command result with log output
     */
    fun log(revisions: String = "@", template: String? = null, filePaths: List<String> = emptyList()): CommandResult
}