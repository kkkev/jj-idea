package `in`.kkkev.jjidea.vcs.history

import com.intellij.diff.DiffManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.history.DiffFromHistoryHandler
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vcs.history.VcsHistoryUtil
import `in`.kkkev.jjidea.jj.ContentLocator
import `in`.kkkev.jjidea.jj.FileAtVersion
import `in`.kkkev.jjidea.jj.FileChange
import `in`.kkkev.jjidea.jj.diffRequest
import `in`.kkkev.jjidea.jj.fileAt
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

/**
 * "Show Diff" for file history. File history lists `all()`, so the row below a revision is not necessarily its parent
 * (jj-idea-d0bp): the platform default would diff against it. For a single selected revision, diff against its real
 * parent instead ([com.intellij.openapi.vcs.history.VcsFileRevision] `previousRevision` is ignored).
 */
class JujutsuDiffFromHistoryHandler : DiffFromHistoryHandler {
    private val log = Logger.getInstance(javaClass)

    override fun showDiffForOne(
        e: AnActionEvent,
        project: Project,
        filePath: FilePath,
        previousRevision: VcsFileRevision,
        revision: VcsFileRevision
    ) {
        if (revision !is JujutsuFileRevision) {
            VcsHistoryUtil.showDifferencesInBackground(project, filePath, previousRevision, revision)
            return
        }
        val repo = revision.entry.repo
        runInBackground {
            val (before, after) = diffSidesFor(revision)
            val request = diffRequest(
                filePath.name,
                repo.createDiffSideFor(before),
                repo.createDiffSideFor(after)
            )
            runLater { DiffManager.getInstance().showDiff(project, request) }
        }
    }

    override fun showDiffForTwo(
        project: Project,
        filePath: FilePath,
        revision1: VcsFileRevision,
        revision2: VcsFileRevision
    ) = VcsHistoryUtil.showDifferencesInBackground(project, filePath, revision1, revision2)

    /** The file's version before and after [revision]'s change; null where the file did not exist. */
    internal fun diffSidesFor(revision: JujutsuFileRevision): Pair<FileAtVersion?, FileAtVersion?> {
        val entry = revision.entry
        val filePath = revision.path
        entry.repo.logService.getFileChanges(entry, filePath)
            .onFailure { log.warn("Could not read file change for $filePath at ${entry.id}; using fallback", it) }
            .getOrNull()
            ?.firstOrNull { change -> change.allPaths.any { it == filePath } }
            ?.let { return it.before to it.after }

        val before = if (revision.fileStatus == FileChange.Status.ADDED ||
            entry.parentContentLocator == ContentLocator.Empty
        ) {
            null
        } else {
            filePath.fileAt(entry.parentContentLocator)
        }
        val after = if (revision.fileStatus == FileChange.Status.DELETED) {
            null
        } else {
            filePath.fileAt(entry.contentLocator)
        }
        return before to after
    }
}
