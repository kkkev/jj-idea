package `in`.kkkev.jjidea.commands

import com.intellij.openapi.vfs.VirtualFile

/**
 * Abstraction for executing jujutsu commands.
 * This interface allows for different implementations (CLI, native library, etc.)
 */
interface JujutsuCommandExecutor {
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
     * Get the status of the working copy
     * @param root The VCS root directory
     * @return List of file statuses
     */
    fun status(root: VirtualFile): CommandResult

    /**
     * Get the diff for a specific file
     * @param root The VCS root directory
     * @param filePath Path relative to root
     * @return Diff output
     */
    fun diff(root: VirtualFile, filePath: String): CommandResult

    /**
     * Get the content of a file at a specific revision
     * @param root The VCS root directory
     * @param filePath Path relative to root
     * @param revision Revision identifier (e.g., "@", "@-", commit hash)
     * @return File content
     */
    fun show(root: VirtualFile, filePath: String, revision: String): CommandResult

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
     * @param root The VCS root directory
     * @param message The description message
     * @param revision The revision to describe (default: "@")
     * @return Command result
     */
    fun describe(root: VirtualFile, message: String, revision: String = "@"): CommandResult

    /**
     * Create a new change on top of the current one
     * @param root The VCS root directory
     * @param message Optional description for the new change
     * @return Command result
     */
    fun new(root: VirtualFile, message: String? = null): CommandResult

    /**
     * Get the log for specific revisions
     * @param root The VCS root directory
     * @param revisions Revisions to show (e.g., "@", "@-")
     * @param template Template for output (e.g., "description", "change_id")
     * @return Command result with log output
     */
    fun log(root: VirtualFile, revisions: String = "@", template: String? = null): CommandResult
}
