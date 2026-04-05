package `in`.kkkev.jjidea.jj

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.util.saveAllDocuments

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

        fun tellUser(project: Project, resourceKeyPrefix: String) {
            val message = JujutsuBundle.message("$resourceKeyPrefix.message", stderr)
            Messages.showErrorDialog(project, message, JujutsuBundle.message("$resourceKeyPrefix.title"))
        }
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
    fun bookmarkList(template: String? = null, remote: Remote? = null, tracked: Boolean = false): CommandResult

    fun bookmarkCreate(name: Bookmark, revision: Revision = WorkingCopy): CommandResult

    fun bookmarkDelete(name: Bookmark): CommandResult

    fun bookmarkRename(oldName: Bookmark, newName: Bookmark): CommandResult

    fun bookmarkSet(name: Bookmark, revision: Revision = WorkingCopy, allowBackwards: Boolean = false): CommandResult

    fun bookmarkTrack(name: Bookmark): CommandResult

    fun bookmarkUntrack(name: Bookmark): CommandResult

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

    /**
     * Fetch from a Git remote.
     * @param remote Specific remote to fetch from (null = default)
     * @param allRemotes Fetch from all remotes
     * @return Command result
     */
    fun gitFetch(remote: Remote? = null, allRemotes: Boolean = false): CommandResult

    /**
     * Push to a Git remote.
     * @param remote Specific remote to push to (null = default)
     * @param bookmark Specific bookmark to push (null = tracking bookmarks)
     * @param allBookmarks Push all bookmarks
     * @return Command result
     */
    fun gitPush(remote: Remote? = null, bookmark: Bookmark? = null, allBookmarks: Boolean = false): CommandResult

    /**
     * Squash a change into its parent.
     * @param revision The revision to squash (default: working copy)
     * @param filePaths Specific files to squash (empty = all files)
     * @param description Description for the combined result (null = let jj merge)
     * @param keepEmptied Keep the emptied source change
     * @return Command result
     */
    fun squash(
        revision: Revision = WorkingCopy,
        filePaths: List<FilePath> = emptyList(),
        description: Description? = null,
        keepEmptied: Boolean = false
    ): CommandResult

    /**
     * Split a change into two changes.
     * @param revision The revision to split (default: working copy)
     * @param filePaths Files to keep in the first commit (empty = interactive, but UI always provides paths)
     * @param description Description for the first commit (null = keep original)
     * @param parallel Create parallel (sibling) commits instead of parent/child
     * @return Command result
     */
    fun split(
        revision: Revision = WorkingCopy,
        filePaths: List<FilePath> = emptyList(),
        description: Description? = null,
        parallel: Boolean = false
    ): CommandResult

    /**
     * List Git remotes.
     * @return Command result with remote names (one per line)
     */
    fun gitRemoteList(): CommandResult

    /**
     * Clone a Git repository and create a Jujutsu repository.
     * @param source URL or path of the Git repo to clone
     * @param destination Target directory path
     * @param colocate Whether to colocate with Git (.git alongside .jj)
     * @return Command result
     */
    fun gitClone(source: String, destination: String, colocate: Boolean = true): CommandResult

    /**
     * Scope for getting/setting configuration values.
     */
    enum class ConfigScope {
        /**
         * Global across all jj interactions for the current user.
         */
        USER,

        /**
         * Configuration specific to the repository.
         */
        REPO;

        val param = "--${name.lowercase()}"
    }

    /**
     * Get a jj config value. Works in the same way as jj; looks in the repository first, falling back to user scope.
     * @param key Config key (e.g., "user.name", "user.email")
     * @return Command result (stdout contains value if exists, exit code 1 if not set)
     */
    fun configGet(key: String): CommandResult

    fun configList(key: String? = null, scope: ConfigScope? = null): CommandResult

    /**
     * Set a jj config value at user level.
     * @param scope Scope at which to set the config value
     * @param key Config key (e.g., "user.name", "user.email")
     * @param value Config value
     * @return Command result
     */
    fun configSetUser(scope: ConfigScope, key: String, value: String): CommandResult

    fun configUnset(scope: ConfigScope, key: String): CommandResult

    data class Command(
        val commandExecutor: CommandExecutor,
        val action: CommandExecutor.() -> CommandResult,
        val onSuccess: (String) -> Unit = {},
        val onFailure: CommandResult.() -> Unit = {}
    ) {
        fun onSuccess(callback: (String) -> Unit) = copy(onSuccess = callback)

        fun onFailure(callback: CommandResult.() -> Unit) = copy(onFailure = callback)

        private fun handleResult(result: CommandResult) {
            runLater {
                if (result.isSuccess) onSuccess(result.stdout) else onFailure(result)
            }
        }

        fun executeAsync() {
            saveAllDocuments()
            runInBackground {
                handleResult(commandExecutor.action())
            }
        }

        fun executeWithProgress(project: Project, title: String) {
            saveAllDocuments()
            object : Task.Backgroundable(project, title, false) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    handleResult(commandExecutor.action())
                }
            }.queue()
        }
    }

    fun createCommand(action: CommandExecutor.() -> CommandResult): Command = Command(this, action)
}
