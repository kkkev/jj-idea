package `in`.kkkev.jjidea.jj

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.JujutsuBundle

/**
 * Abstraction for executing jujutsu commands.
 * This interface allows for different implementations (CLI, native library, etc.)
 */
interface CommandExecutor {
    /**
     * Result of a jujutsu command execution
     */
    data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
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
    fun show(filePath: FilePath, revision: Revision): CommandResult

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
     * Initialises a JJ repo with the Git back end.
     * @param colocate whether or not to colocate, i.e. create the Git back end in .git so that git commands can be used
     * in the same directory.
     * @return command result
     */
    fun gitInit(colocate: Boolean): CommandResult

    /**
     * Set the description for a commit (default: working copy @)
     * @param description The description message
     * @param revision The revision to describe (default: "@")
     * @return Command result
     */
    fun describe(description: Description, revision: Revision = WorkingCopy): CommandResult

    /**
     * Create a new change on top of the current one
     * @param description Optional description for the new change
     * @param parentRevisions Optional parent revisions (default: current working copy)
     * @return Command result
     */
    fun new(description: Description, parentRevisions: List<Revision> = listOf(WorkingCopy)): CommandResult

    /**
     * Abandon a change (remove it from the log)
     * @param revision The revision to abandon
     * @return Command result
     */
    fun abandon(revision: Revision): CommandResult

    /**
     * Edit a change (move working copy to specified revision)
     * @param revision The revision to edit
     * @return Command result
     */
    fun edit(revision: Revision): CommandResult

    /**
     * Get the log for specific revset
     * @param revset Revisions to show (e.g., "@", "@-")
     * @param template Template for output (e.g., "description", "change_id")
     * @param filePaths Optional file paths to filter log (e.g., "src/main.kt")
     * @return Command result with log output
     */
    fun log(
        revset: Revset = Expression.ALL,
        template: String? = null,
        filePaths: List<FilePath> = emptyList(),
        limit: Int? = null
    ): CommandResult

    /**
     * Get line-by-line annotation (blame) for a file
     * @param file File to annotate
     * @param revision Revision from which to start annotating (default: "@")
     * @param template Template for annotation output
     * @return Annotation output with change info per line
     */
    fun annotate(file: VirtualFile, revision: Revision = WorkingCopy, template: String? = null): CommandResult

    /**
     * List all bookmarks in the repository
     * @param template Optional template for output formatting
     * @return Command result with bookmark list
     */
    fun bookmarkList(template: String? = null): CommandResult

    /**
     * Get git-format diff for a revision (to detect renames)
     * @param revision Revision to diff (e.g., "@", change ID)
     * @return Git-format diff output
     */
    fun diffGit(revision: Revision): CommandResult

    /**
     * Restore the specified files to the specified revision.
     */
    fun restore(filePaths: List<FilePath>, revision: Revision): CommandResult

    /**
     * Rebase revisions onto a new destination.
     * @param revisions Revisions to rebase
     * @param destinations Destination revisions (multiple creates a merge)
     * @param sourceMode How to select source revisions (-r, -s, -b)
     * @param destinationMode Where to place them (-d, -A, -B)
     * @return Command result
     */
    fun rebase(
        revisions: List<Revision>,
        destinations: List<Revision>,
        sourceMode: RebaseSourceMode = RebaseSourceMode.REVISION,
        destinationMode: RebaseDestinationMode = RebaseDestinationMode.ONTO
    ): CommandResult

    data class Command(
        val commandExecutor: CommandExecutor,
        val action: CommandExecutor.() -> CommandResult,
        val onSuccess: (String) -> Unit = {},
        val onFailure: CommandResult.() -> Unit = {}
    ) {
        fun onSuccess(callback: (String) -> Unit) = copy(onSuccess = callback)

        fun onFailure(callback: CommandResult.() -> Unit) = copy(onFailure = callback)

        fun onFailureTellUser(resourceKeyPrefix: String, project: Project, log: Logger) = onFailure {
            val message = JujutsuBundle.message("$resourceKeyPrefix.message", stderr)
            Messages.showErrorDialog(project, message, JujutsuBundle.message("$resourceKeyPrefix.title"))
            log.warn(message)
        }

        fun executeAsync() {
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = commandExecutor.action()
                ApplicationManager.getApplication().invokeLater {
                    if (result.isSuccess) {
                        onSuccess(result.stdout)
                    } else {
                        onFailure(result)
                    }
                }
            }
        }
    }

    fun createCommand(action: CommandExecutor.() -> CommandResult): Command = Command(this, action)
}
