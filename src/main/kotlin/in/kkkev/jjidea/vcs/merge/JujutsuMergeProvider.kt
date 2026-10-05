package `in`.kkkev.jjidea.vcs.merge

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vcs.merge.MergeData
import com.intellij.openapi.vcs.merge.MergeProvider2
import com.intellij.openapi.vcs.merge.MergeSession
import com.intellij.openapi.vcs.merge.MergeSessionEx
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.EditorNotifications
import com.intellij.util.ui.ColumnInfo
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.conflict.ConflictExtractor
import `in`.kkkev.jjidea.jj.conflict.ExtractedConflict
import `in`.kkkev.jjidea.jj.conflict.JjMarkerConflictExtractor
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.jj.relativePathOf
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
import `in`.kkkev.jjidea.ui.services.addExpiringAction
import `in`.kkkev.jjidea.ui.workingcopy.WorkingCopyToolWindowFactory
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor

class JujutsuMergeProvider(
    private val project: Project,
    private val extractor: ConflictExtractor = JjMarkerConflictExtractor(),
    private val repoFor: (VirtualFile) -> JujutsuRepository? = { project.possibleJujutsuRepositoryFor(it) },
    private val refreshAfterResolve: (JujutsuRepository) -> Unit = { it.invalidate(vfsChanged = true) },
    private val refreshEditorNotifications: (VirtualFile) -> Unit = {
        EditorNotifications.getInstance(project).updateNotifications(it)
    },
    private val notifyError: (title: String, message: String) -> Unit = { title, message ->
        JujutsuNotifications.notify(project, title, message, NotificationType.ERROR)
    },
    // Fired when [loadRevisions] refuses: the platform dialog can only show our exception as an
    // error, so also offer a friendlier info balloon that leads to the Working Copy window.
    private val notifyRefused: () -> Unit = { showRefusedNotification(project) },
    // Defaults to false: the key doesn't exist on older builds, whose merge flow is one-shot.
    private val iterativeMergeEnabled: () -> Boolean = {
        Registry.`is`("vcs.merge.conflict.iterative.resolution", false)
    }
) : MergeProvider2 {
    /**
     * Called on a background thread by the merge framework.
     *
     * Refuses (jj-idea-ddcd) when the platform's iterative merge flow is on. The platform's native
     * `MultipleFileMergeDialog` (the Commit tool window's "Merge Conflicts → Resolve" link, which
     * jj-idea can't suppress) calls this before every path that edits the real file's Document -
     * "Merge...", "Resolve automatically" and iterative Accept Yours/Theirs - and in that flow
     * cancelling saves the Document without restoring it, silently discarding a side of the
     * conflict (see [JujutsuConflictResolver], GitHub #63). The dialog catches [VcsException] and
     * shows it as an error, leaving the file untouched.
     *
     * jj-idea's own code must use [loadConflict], which never refuses.
     *
     * ### Why refuse instead of disabling the native flow for jj repos?
     * Every way of switching that flow off is unavailable or too broad:
     * - The "Merge Conflicts" node and its "Resolve" link are built by the platform
     *   (`ChangesBrowserConflictsNode`, `@ApiStatus.Internal`) and have no suppression API.
     * - The one extension point that touches that link (`MergeResolveActionProvider`) can only add
     *   alternatives beside it, is `@ApiStatus.Internal`, and exists on 2026.2+ only. Registering
     *   into it on older builds needs `@TestOnly`/internal API at runtime.
     * - `vcs.merge.conflict.iterative.resolution=false` is an application-wide registry flag that
     *   would also change Git's merge UX, and can't be toggled per-VCS or just in time.
     * - Reporting jj conflicts under a plugin-defined `FileStatus` (so the node never appears)
     *   was rejected: it would lose platform affordances and touch every consumer of
     *   `MERGED_WITH_CONFLICTS` (see jj-idea-ddcd notes).
     * - For jj-only projects the whole standard Commit tool window is already hidden (jj-idea-wb5l),
     *   so this only matters in mixed jj + Git projects, or when the user un-hides that window.
     *
     * Refusing here is the one lever that is both public API and scoped to jj. Revisit if the
     * platform stops routing the dialog through [loadRevisions], renames or removes the registry
     * key, or exposes a supported way to suppress or replace the native link.
     */
    override fun loadRevisions(file: VirtualFile): MergeData {
        if (iterativeMergeEnabled()) {
            notifyRefused()
            throw VcsException(JujutsuBundle.message("merge.nativeDialog.unsafe"))
        }
        return loadConflict(file).mergeData
    }

    /**
     * Like [loadRevisions], but also returns jj's own label for whichever side landed in
     * [MergeData.CURRENT]/[MergeData.LAST] (GitHub #112) - used for merge-pane titles
     * ([JujutsuConflictResolver]) and to keep [acceptFilesRevisions]'s `:ours`/`:theirs` mapping
     * consistent with a reoriented dialog.
     *
     * Deliberately working-copy scoped, unlike
     * [in.kkkev.jjidea.vcs.diff.JujutsuConflictDiffRequestProvider]'s read-only conflict diff for
     * an arbitrary revision (GitHub #119, jj-idea-ct7e): resolving a conflict writes to disk, so
     * it only ever makes sense for `@` (see `resolveConflictsAvailability`'s `NEEDS_EDIT` state
     * for every other commit) - there is no working-copy-independent equivalent to reuse here.
     */
    fun loadConflict(file: VirtualFile): ExtractedConflict {
        // The extractor handles all three jj conflict marker styles (snapshot, diff, git).
        // For the working copy, createContentRevision reads the file from disk directly.
        val bytes = repoFor(file)
            ?.createContentRevision(file.filePath, WorkingCopy)
            ?.content
            ?.toByteArray(Charsets.UTF_8)
            ?: file.contentsToByteArray()
        return extractor.extract(bytes)
            ?: throw VcsException("Could not extract conflict data from ${file.name}")
    }

    /**
     * "Yours" isn't always jj's side #1 - a rebase conflict's panes may be reoriented so the
     * user's own change is "Yours" even when it's jj's side #2 (GitHub #112, see
     * [ExtractedConflict.currentIsJjSide1]). Without this, this bulk accept path would pick
     * the opposite side from what the interactive dialog just showed for the same file.
     * Falls back to the literal `:ours`/`:theirs` mapping when the file can no longer be
     * extracted (e.g. already resolved externally).
     */
    internal fun toolFor(file: VirtualFile, resolution: MergeSession.Resolution): String {
        val conflict = try {
            loadConflict(file)
        } catch (_: VcsException) {
            null
        }
        return toolFor(conflict, resolution)
    }

    /** [toolFor] for an already-loaded [conflict] (null = couldn't be extracted). */
    internal fun toolFor(conflict: ExtractedConflict?, resolution: MergeSession.Resolution): String {
        val acceptingCurrent = resolution == MergeSession.Resolution.AcceptedYours
        return when {
            conflict == null -> if (acceptingCurrent) ":ours" else ":theirs"
            acceptingCurrent -> conflict.toolForCurrent
            else -> conflict.toolForLast
        }
    }

    /** The jj repository owning [file], or null when it isn't under one. */
    internal fun repositoryFor(file: VirtualFile): JujutsuRepository? = repoFor(file)

    override fun conflictResolvedForFile(file: VirtualFile) = refreshResolved(listOf(file))

    override fun isBinary(file: VirtualFile) = file.fileType.isBinary

    override fun createMergeSession(files: List<VirtualFile>): MergeSession = JujutsuMergeSession(files)

    /**
     * Mark files dirty and invalidate their repos so jj re-snapshots the working copy
     * and clears the conflict from the change provider.
     *
     * Without the repo-level invalidate, [VcsDirtyScopeManager.fileDirty] alone is insufficient:
     * the cached working-copy [LogEntry] keeps `hasConflict = true`, the stateKey doesn't change,
     * and the dirty cascade never fires — leaving resolved files perpetually conflicted in the panel.
     *
     * Also refreshes any open editor's [in.kkkev.jjidea.ui.editor.JujutsuConflictEditorNotificationProvider]
     * banner directly: that provider re-evaluates from [ChangeListManager][com.intellij.openapi.vcs.changes.ChangeListManager],
     * which the `fileDirty`/`invalidate` calls above only update asynchronously, so without this the
     * banner could linger stale for a file the user just resolved.
     */
    private fun refreshResolved(files: List<VirtualFile>) {
        files.forEach { VcsDirtyScopeManager.getInstance(project).fileDirty(it) }
        files.mapNotNull { repoFor(it) }.distinct().forEach(refreshAfterResolve)
        files.forEach(refreshEditorNotifications)
    }

    private inner class JujutsuMergeSession(files: List<VirtualFile>) : MergeSessionEx {
        // Must return exactly 2 columns (not 0): IntelliJ 2026.2's iterative merge dialog
        // (IterativeMergeFlowDelegate) builds its column-name list as
        // [file name] + getMergeInfoColumns() and unconditionally indexes into it for the
        // "Accept Yours"/"Accept Theirs" labels. An empty array — which the interface
        // otherwise documents as valid — leaves that list too short and throws
        // IndexOutOfBoundsException before the dialog can show (jj-idea-qfgl, GitHub #55).
        // Git4Idea's MyMergeSession always returns 2 columns for the same reason; we don't
        // have a cheap per-side status to report (jj's conflict markers don't distinguish
        // added/modified/deleted the way git's index does), so these are label-only.
        override fun getMergeInfoColumns(): Array<ColumnInfo<*, *>> = arrayOf(yoursColumn, theirsColumn)

        override fun canMerge(file: VirtualFile) = !file.isDirectory && !file.fileType.isBinary

        override fun conflictResolvedForFile(file: VirtualFile, resolution: MergeSession.Resolution) =
            conflictResolvedForFiles(listOf(file), resolution)

        override fun conflictResolvedForFiles(files: List<VirtualFile>, resolution: MergeSession.Resolution) =
            refreshResolved(files)

        // Called on a background thread inside a modal task. Goes through `jj resolve --tool`
        // rather than writing CURRENT/LAST bytes to disk directly: for a modify/delete conflict
        // where the chosen side is the deletion, `:ours`/`:theirs` actually remove the file,
        // whereas writing its (empty) bytes would leave behind an empty file instead.
        override fun acceptFilesRevisions(files: List<VirtualFile>, resolution: MergeSession.Resolution) {
            if (resolution != MergeSession.Resolution.AcceptedYours &&
                resolution != MergeSession.Resolution.AcceptedTheirs
            ) {
                return
            }
            val failures = mutableListOf<Pair<VirtualFile, String>>()
            for (file in files) {
                val repo = repoFor(file)
                if (repo == null) {
                    failures += file to JujutsuBundle.message("merge.resolve.noRepo")
                    continue
                }
                val tool = toolFor(file, resolution)
                val result = repo.commandExecutor.resolve(listOf(repo.relativePathOf(file)), tool)
                if (result is CommandExecutor.CommandResult.Failure) {
                    failures += file to result.stderr.ifBlank { "exit ${result.exitCode}" }
                }
            }
            if (failures.isNotEmpty()) reportFailures(failures)
        }

        private fun reportFailures(failures: List<Pair<VirtualFile, String>>) {
            val detail = failures.joinToString("\n") { (file, reason) -> "${file.name}: $reason" }
            notifyError(
                JujutsuBundle.message("notification.resolve.failed.title"),
                JujutsuBundle.message("notification.resolve.failed.message", detail)
            )
        }
    }

    private companion object {
        fun showRefusedNotification(project: Project) {
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("Jujutsu")
                .createNotification(
                    JujutsuBundle.message("merge.nativeDialog.notification.title"),
                    JujutsuBundle.message("merge.nativeDialog.notification.message"),
                    NotificationType.INFORMATION
                )
            notification.addExpiringAction("merge.nativeDialog.notification.open") {
                ToolWindowManager.getInstance(project)
                    .getToolWindow(WorkingCopyToolWindowFactory.TOOL_WINDOW_ID)
                    ?.activate(null, true)
            }
            notification.notify(project)
        }

        // See the comment on getMergeInfoColumns above for why these exist. valueOf is blank —
        // the names alone are what the platform needs.
        val yoursColumn = createColumn("merge.column.yours")
        val theirsColumn = createColumn("merge.column.theirs")

        private fun createColumn(nameResourceKey: String): ColumnInfo<VirtualFile, String> =
            object : ColumnInfo<VirtualFile, String>(JujutsuBundle.message(nameResourceKey)) {
                override fun valueOf(item: VirtualFile) = ""
            }
    }
}
