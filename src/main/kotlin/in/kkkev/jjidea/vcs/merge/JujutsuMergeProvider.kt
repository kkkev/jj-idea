package `in`.kkkev.jjidea.vcs.merge

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vcs.merge.MergeData
import com.intellij.openapi.vcs.merge.MergeProvider2
import com.intellij.openapi.vcs.merge.MergeSession
import com.intellij.openapi.vcs.merge.MergeSessionEx
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotifications
import com.intellij.util.ui.ColumnInfo
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.conflict.ConflictExtractor
import `in`.kkkev.jjidea.jj.conflict.JjMarkerConflictExtractor
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
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
    }
) : MergeProvider2 {
    // Called on a background thread by the merge framework
    override fun loadRevisions(file: VirtualFile): MergeData {
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
            val tool = when (resolution) {
                MergeSession.Resolution.AcceptedYours -> ":ours"
                MergeSession.Resolution.AcceptedTheirs -> ":theirs"
                else -> return
            }
            val failures = mutableListOf<Pair<VirtualFile, String>>()
            for (file in files) {
                val repo = repoFor(file)
                if (repo == null) {
                    failures += file to JujutsuBundle.message("merge.resolve.noRepo")
                    continue
                }
                val relativePath = file.path.removePrefix(repo.directory.path).removePrefix("/")
                val result = repo.commandExecutor.resolve(listOf(relativePath), tool)
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
