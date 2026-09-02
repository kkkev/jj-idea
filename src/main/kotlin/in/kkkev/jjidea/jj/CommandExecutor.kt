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
     * Result of a jujutsu command execution.
     *
     * [Success] and [Failure] split first because that's the only distinction most callers ever
     * made against the old flat `exitCode == 0` shape. Below that:
     * - [Success.Reversible] / [Success.Irreversible] record whether the command wrote an
     *   operation we can revert via [opRevert] — see [Success.Irreversible.Reason] for why a
     *   command can succeed but still not be offered for undo (e.g. `git push`: its effect
     *   reaches beyond the `repo` scope that `jj op revert --what repo` inverts).
     * - [Failure.Executed] separates a process that actually ran ([Failure.Exited],
     *   [Failure.TimedOut]) from one that never started ([Failure.NotLaunched]) — a timed-out
     *   process has real partial [Failure.Executed.stdout] but no real stderr (the process never
     *   got to report anything), and a not-launched one has no streams at all. Both synthesize a
     *   diagnostic into [Failure.message] instead of pretending to have captured real output.
     */
    sealed interface CommandResult {
        /**
         * Kept readable on every result, matching the pre-sealed-hierarchy flat shape: jj prints
         * non-fatal warnings to stderr even on exit 0 (see [Command.onSuccessResult]), and every
         * failure kind synthesizes something meaningful here even when no real stderr exists
         * (see [Failure.message], which aliases this on [Failure]).
         */
        val stdout: String
        val stderr: String

        /** `true` for [Success]. Kept temporarily for callers not yet migrated to `is Success`. */
        val isSuccess: Boolean get() = this is Success

        sealed interface Success : CommandResult {
            /** Succeeded and wrote an operation we identified and can revert via [opRevert]. */
            data class Reversible(
                override val stdout: String,
                override val stderr: String,
                val operation: OperationId
            ) : Success

            /** Succeeded, but no undo can be offered - see [reason]. */
            data class Irreversible(
                override val stdout: String,
                override val stderr: String,
                val reason: Reason
            ) : Success {
                enum class Reason {
                    /**
                     * The command's effect reaches beyond the `repo` scope that
                     * `jj op revert --what repo` inverts - `git push`/`fetch`/`clone`/`remote`,
                     * `bookmark track`/`untrack`, `config`. Verified: reverting these either
                     * reports "Nothing changed." or desyncs local and remote-tracking state.
                     */
                    NOT_REVERSIBLE_COMMAND,

                    /** A read-only command; it wrote no operation of its own. */
                    READ_ONLY,

                    /** Reversible command, but it wrote no operation - a no-op ("Nothing changed."). */
                    NO_OPERATION,

                    /** Reversible command, but the written operation could not be identified. */
                    NOT_IDENTIFIED,

                    /** Undo tracking was not requested for this invocation - see [withUndoTracking]. */
                    NOT_TRACKED
                }
            }
        }

        sealed interface Failure : CommandResult {
            /** Exit code: real for [Exited]; the old `-1` sentinel for [TimedOut]/[NotLaunched]. */
            val exitCode: Int

            /**
             * Always-meaningful, user-facing diagnostic - what [tellUser] renders. For [Exited]
             * this is the real stderr; for [TimedOut] and [NotLaunched] it is synthesized (and
             * mirrored into [stderr] too, for source compatibility with pre-existing readers).
             */
            val message: String get() = stderr

            /** A process actually ran and produced output - as opposed to [NotLaunched]. */
            sealed interface Executed : Failure

            /** Ran to completion with a non-zero exit code. */
            data class Exited(override val stdout: String, override val stderr: String, override val exitCode: Int) :
                Executed

            /**
             * Killed at the timeout: [stdout] is real partial output; there is no exit code
             * because `runProcess(timeout)` destroys the process without setting one - [exitCode]
             * is the old `-1` sentinel.
             */
            data class TimedOut(override val stdout: String, val timeoutMillis: Long, override val stderr: String) :
                Executed {
                override val exitCode get() = -1
            }

            /** No process was created (e.g. the jj executable wasn't found), so there are no streams. */
            data class NotLaunched(val executable: String, override val stderr: String) : Failure {
                override val stdout get() = ""
                override val exitCode get() = -1
            }
        }

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
    fun diffSummary(revision: Revision, filePath: FilePath? = null): CommandResult

    /**
     * Get summary of changes between two revisions/locators (`jj diff --summary --from --to`).
     * @param from Content locator for the "before" side (e.g. a commit)
     * @param to Content locator for the "after" side (e.g. the working copy)
     * @return Summary of file changes
     */
    fun diffSummaryBetween(from: ContentLocator, to: ContentLocator, filePath: FilePath? = null): CommandResult

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
     * Create a new change.
     * @param description Optional description for the new change
     * @param parentRevisions Optional parent revisions (default: current working copy). With
     *   [destinationMode] `ONTO` (the default) these are the new change's positional parents,
     *   exactly like `jj new <revs>`. With `INSERT_AFTER`/`INSERT_BEFORE`, each is passed as a
     *   separate `-A`/`-B` (`jj new -A/-B <rev>`), inserting the new change after/before that
     *   revision and rebasing its affected descendants onto it - `jj new` has no `--onto` flag,
     *   so [RebaseDestinationMode.ONTO]'s `flag` string is never used here.
     * @param edit Whether to move the working copy to the new change (default: `true`, matching
     *   `jj new`'s default). `false` passes `--no-edit`, leaving `@` where it was - useful when
     *   inserting a placeholder mid-stack without interrupting current work.
     * @return Command result
     */
    fun new(
        description: Description,
        parentRevisions: List<Revision> = listOf(WorkingCopy),
        destinationMode: RebaseDestinationMode = RebaseDestinationMode.ONTO,
        edit: Boolean = true
    ): CommandResult

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
     * Duplicate one or more changes (identical copies with new change IDs).
     * @param revisions The revisions to duplicate
     * @param destinations Optional destination revisions; if empty, each copy is placed
     * in the same location as its original
     * @param destinationMode How to place the copies relative to [destinations] (onto,
     * insert-after, insert-before)
     * @return Command result
     */
    fun duplicate(
        revisions: List<Revision>,
        destinations: List<Revision> = emptyList(),
        destinationMode: RebaseDestinationMode = RebaseDestinationMode.ONTO
    ): CommandResult

    /**
     * Get the log for specific revset
     * @param revset Revisions to show (e.g., "@", "@-")
     * @param template Template for output (e.g., "description", "change_id")
     * @param filePaths Optional file paths to filter log (e.g., "src/main.kt")
     * @param quiet If true, suppress the WARN-level log on failure (e.g. resolving a revision that
     * may legitimately not exist, such as a free-form revision typed by the user)
     * @return Command result with log output
     */
    fun log(
        revset: Revset = Expression.ALL,
        template: String? = null,
        filePaths: List<FilePath> = emptyList(),
        limit: Int? = null,
        quiet: Boolean = false
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
     * List all tags in the repository
     * @param template Optional template for output formatting
     * @return Command result with tag list
     */
    fun tagList(template: String? = null): CommandResult

    fun tagSet(tag: Tag, revision: Revision = WorkingCopy, allowMove: Boolean = false): CommandResult

    fun tagDelete(tag: Tag): CommandResult

    /**
     * List all bookmarks in the repository
     * @param template Optional template for output formatting
     * @return Command result with bookmark list
     */
    fun bookmarkList(
        template: String? = null,
        remote: Remote? = null,
        tracked: Boolean = false,
        revision: Revision? = null
    ): CommandResult

    fun bookmarkCreate(name: BookmarkName, revision: Revision = WorkingCopy): CommandResult

    fun bookmarkDelete(name: BookmarkName): CommandResult

    fun bookmarkRename(oldName: BookmarkName, newName: BookmarkName): CommandResult

    fun bookmarkSet(
        name: BookmarkName,
        revision: Revision = WorkingCopy,
        allowBackwards: Boolean = false
    ): CommandResult

    /**
     * `jj bookmark advance`, moving bookmarks forward to [to]. With an empty [names], advances
     * every bookmark eligible per `revsets.bookmark-advance-from`; a non-empty [names] restricts
     * it to those bookmarks. Requires jj 0.39+ ([in.kkkev.jjidea.jj.JjFeature.BOOKMARK_ADVANCE])
     * — callers must check that before invoking.
     */
    fun bookmarkAdvance(names: List<BookmarkName> = emptyList(), to: Revision = WorkingCopy): CommandResult

    fun bookmarkForget(name: BookmarkName): CommandResult

    /** Tracks one or more remote bookmarks in a single command; all must share the same remote. */
    fun bookmarkTrack(names: List<BookmarkName>): CommandResult

    fun bookmarkUntrack(name: BookmarkName): CommandResult

    /**
     * Get git-format diff for a revision (to detect renames)
     * @param revision Revision to diff (e.g., "@", change ID)
     * @return Git-format diff output
     */
    fun diffGit(revision: Revision): CommandResult

    /**
     * Get git-format diff for a single file at a specific revision.
     * Used for reverse-applying to reconstruct merge parent content.
     * @param revision Revision to diff (e.g., "@", change ID)
     * @param filePath File to diff
     * @return Git-format diff output for the specific file
     */
    fun diffGitFile(revision: Revision, filePath: FilePath): CommandResult

    /**
     * Restore the specified files to the specified revision.
     */
    fun restore(filePaths: List<FilePath>, revision: Revision): CommandResult

    /**
     * Force-include the specified files even if they match `.gitignore` (`jj file track`).
     */
    fun fileTrack(filePaths: List<FilePath>): CommandResult

    /**
     * Stop tracking the specified files (`jj file untrack`). Only succeeds for files jj
     * considers ignored.
     */
    fun fileUntrack(filePaths: List<FilePath>): CommandResult

    /**
     * Lists which of the given paths jj currently tracks (`jj file list <paths>...`). A path
     * missing from stdout is untracked - jj can't distinguish "untracked because ignored" from
     * "untracked for any other reason" here, so this only answers the tracked/untracked question,
     * not why. This is the only fully reliable way to determine tracked status; see
     * docs/jj-track-untrack-model.md.
     */
    fun fileList(filePaths: List<FilePath>): CommandResult

    /** Lists all conflicted file paths in the given revision (infrastructure for the future Conflicts tool window). */
    fun resolveList(revision: Revision = WorkingCopy): CommandResult

    /**
     * Resolve one or more conflicted paths with a non-interactive tool (`jj resolve --tool`).
     * `:ours` and `:theirs` correctly turn a modify/delete conflict into an actual file
     * deletion when the deleted side is chosen, unlike writing bytes directly to disk.
     * @param paths Paths relative to the repository root
     * @param tool Tool name, e.g. `:ours` or `:theirs`
     * @param revision Revision to resolve in (default: working copy)
     */
    fun resolve(paths: List<String>, tool: String, revision: Revision = WorkingCopy): CommandResult

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
     * @param changeRevisions Revisions to push via repeated `--change` flags, each auto-generating
     *   its own bookmark
     * @return Command result
     */
    fun gitPush(
        remote: Remote? = null,
        bookmark: Bookmark? = null,
        allBookmarks: Boolean = false,
        changeRevisions: List<Revision> = emptyList(),
        revision: Revision? = null,
        dryRun: Boolean = false
    ): CommandResult

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
     * Squash one or more source changes into a destination change.
     * Uses `jj squash --from <SRC>... --into <DEST>`. Incompatible with the parent-only [squash].
     * @param sources Revisions whose changes will be moved (must be mutable, non-empty)
     * @param destination Revision to receive the changes (must be mutable, not in [sources])
     * @param filePaths Specific files to squash (empty = all files)
     * @param description Description for the combined result at [destination] (null = let jj merge)
     * @param keepEmptied Keep emptied source changes
     */
    fun squashInto(
        sources: List<Revision>,
        destination: Revision,
        filePaths: List<FilePath> = emptyList(),
        description: Description? = null,
        keepEmptied: Boolean = false
    ): CommandResult

    /**
     * Squash a single source change into a destination interactively, using a diff editor tool —
     * the squash analog of [splitInteractive]. Uses `jj squash --from <SRC> --into <DEST> --tool
     * <tool>` (implies `--interactive`), driven non-interactively by the IDE's diff-edit helper.
     * No filesets are passed - the staging tree built by [in.kkkev.jjidea.diffedit.DiffEditTool]
     * carries the selection, exactly as for [splitInteractive].
     *
     * Single-source only: jj's diff editor is one before/after pair, so hunk-level squashing
     * across multiple sources isn't well-defined - see [in.kkkev.jjidea.ui.squash.SquashIntoDialog].
     *
     * @param source The single revision whose changes will be moved
     * @param destination Revision to receive the changes
     * @param description Description for the combined result at [destination] (null = let jj merge)
     * @param keepEmptied Keep the emptied source change
     * @param configArgs Extra `--config NAME=VALUE` entries (prepended before the subcommand)
     * @param tool The diff-editor tool name registered via [configArgs]
     * @return Command result
     */
    fun squashIntoInteractive(
        source: Revision,
        destination: Revision,
        description: Description? = null,
        keepEmptied: Boolean = false,
        configArgs: List<String> = emptyList(),
        tool: String
    ): CommandResult

    /**
     * Split a change into two changes.
     * @param revision The revision to split (default: working copy)
     * @param filePaths The selected fileset passed to `jj split` (empty = interactive, but UI
     *   always provides paths). With [insertBefore] null (default), the selected fileset stays on
     *   [revision]'s original change ID and everything else becomes a new child. With
     *   [insertBefore] set, this polarity flips - see [insertBefore].
     * @param description Description for the *selected* fileset (`-m`; null = keep original)
     * @param parallel Create parallel (sibling) commits instead of parent/child. Mutually
     *   exclusive with [insertBefore] (rejected by `jj split` itself).
     * @param insertBefore When set, the selected fileset is extracted into a **new** commit
     *   inserted before this revision (`jj split -B`), and everything *not* in the fileset stays
     *   on the original commit's identity/location - the inverse of the no-flag default. Typically
     *   [revision] itself, to insert the new commit as its parent.
     * @return Command result
     */
    fun split(
        revision: Revision = WorkingCopy,
        filePaths: List<FilePath> = emptyList(),
        description: Description? = null,
        parallel: Boolean = false,
        insertBefore: Revision? = null
    ): CommandResult

    /**
     * Split a change interactively using a diff editor tool.
     *
     * Uses `jj split --tool <tool>` (implies `--interactive`), driven non-interactively by
     * the IDE's diff-edit helper. The [configArgs] list contains `NAME=VALUE` strings that are
     * prefixed with `--config` and prepended to the command (global jj options).
     *
     * @param revision The revision to split
     * @param description Description for the *selected* side (passed as `-m`; null = keep original)
     * @param parallel Create parallel (sibling) commits instead of parent/child. Mutually
     *   exclusive with [insertBefore].
     * @param configArgs Extra `--config NAME=VALUE` entries (prepended before the subcommand)
     * @param tool The diff-editor tool name registered via [configArgs]
     * @param insertBefore See [split]'s parameter of the same name.
     * @return Command result
     */
    fun splitInteractive(
        revision: Revision = WorkingCopy,
        description: Description? = null,
        parallel: Boolean = false,
        configArgs: List<String> = emptyList(),
        tool: String,
        insertBefore: Revision? = null
    ): CommandResult

    /**
     * List Git remotes.
     * @return Command result with remote names (one per line)
     */
    fun gitRemoteList(): CommandResult

    /**
     * Find the most recent ancestor of [revision] that has been pushed to [remoteName].
     * Returns the full commit hash, or null if no pushed ancestor is found.
     * @param revision Revision to search ancestors of
     * @param remoteName Remote name (e.g. "origin")
     */
    fun latestPushedAncestorCommitId(revision: Revision, remoteName: String): String?

    /**
     * Find the most recent ancestor of the working copy that has been pushed to [remoteName].
     * Returns the full commit hash, or null if no pushed ancestor is found.
     * @param remoteName Remote name (e.g. "origin")
     */
    fun latestPushedAncestorCommitId(remoteName: String): String?

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
        val onSuccessResult: CommandResult.Success.() -> Unit = {},
        val onFailure: CommandResult.Failure.() -> Unit = {}
    ) {
        fun onSuccess(callback: (String) -> Unit) = copy(onSuccess = callback)

        /**
         * Like [onSuccess], but receives the full [CommandResult.Success] (including
         * [CommandResult.Success.stderr]) instead of just stdout. Some jj subcommands print a
         * non-fatal warning to stderr on an otherwise-successful (exit 0) run - e.g.
         * `jj file untrack` on a path that's already untracked - which plain [onSuccess] can't
         * see. Prefer this when a caller needs to surface such warnings; both callbacks run on
         * success, so most callers only need one or the other.
         */
        fun onSuccessResult(callback: CommandResult.Success.() -> Unit) = copy(onSuccessResult = callback)

        fun onFailure(callback: CommandResult.Failure.() -> Unit) = copy(onFailure = callback)

        private fun handleResult(result: CommandResult) {
            runLater {
                when (result) {
                    is CommandResult.Success -> {
                        onSuccess(result.stdout)
                        result.onSuccessResult()
                    }

                    is CommandResult.Failure -> onFailure(result)
                }
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
