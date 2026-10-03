package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.rollback.RollbackEnvironment
import com.intellij.openapi.vcs.rollback.RollbackProgressListener
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.services.offerUndo

/**
 * Backs the platform's Rollback action (and its pre-checked "Rollback Changes" dialog) with
 * `jj restore -f @-` (jj-idea-ad5d, GitHub #56/#84). The operation is named "Restore" after jj's
 * own nomenclature; "Revert" is reserved for `jj revert`.
 *
 * Scale: O(selected changes) in memory and exactly one jj invocation per repository (with an Undo balloon each) - no
 * per-file subprocess, no filesystem traversal.
 */
class JujutsuRollbackEnvironment(
    project: Project,
    private val repoFor: (FilePath) -> JujutsuRepository? = { project.possibleJujutsuRepositoryFor(it) },
    private val onRestored: (JujutsuRepository) -> Unit = { it.invalidate(vfsChanged = true) }
) : RollbackEnvironment {
    override fun getRollbackOperationName(): String = JujutsuBundle.message("vcs.rollback.operation.name")

    override fun rollbackChanges(
        changes: List<Change>,
        vcsExceptions: MutableList<VcsException>,
        listener: RollbackProgressListener
    ) {
        val byRepo = LinkedHashMap<JujutsuRepository, MutableList<Change>>()
        for (change in changes) {
            val path = change.paths.firstOrNull()
            val repo = path?.let(repoFor)
            if (repo == null) {
                vcsExceptions.add(
                    VcsException(JujutsuBundle.message("vcs.error.no.root", path ?: change.toString()))
                )
                continue
            }
            byRepo.getOrPut(repo) { mutableListOf() }.add(change)
        }

        for ((repo, repoChanges) in byRepo) {
            // Renames contribute both paths, so the old file returns and the new one goes away.
            val paths = repoChanges.flatMap { it.paths }.distinct()
            when (val result = repo.commandExecutor.withUndoTracking().restore(paths, WorkingCopy.parent)) {
                is CommandResult.Success -> {
                    repoChanges.forEach(listener::accept)
                    onRestored(repo)
                    offerUndo(repo, result, "action.restore.selection.undo")
                }

                is CommandResult.Failure -> vcsExceptions.add(
                    VcsException(JujutsuBundle.message("vcs.rollback.error", result.message))
                )
            }
        }
    }

    override fun rollbackMissingFileDeletion(
        files: List<FilePath>,
        exceptions: MutableList<in VcsException>,
        listener: RollbackProgressListener
    ) {
        // No-op: jj has no "missing" state - a deleted file is an ordinary change that restore recovers.
    }

    override fun rollbackModifiedWithoutCheckout(
        files: List<VirtualFile>, exceptions: MutableList<in VcsException>, listener: RollbackProgressListener
    ) {
        // No-op: jj has no checkout lock.
    }

    override fun rollbackIfUnchanged(file: VirtualFile) {
        // No-op.
    }

    private val Change.paths: List<FilePath>
        get() = listOfNotNull(beforeRevision?.file, afterRevision?.file).distinct()
}
