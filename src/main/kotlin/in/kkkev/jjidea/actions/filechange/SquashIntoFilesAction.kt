package `in`.kkkev.jjidea.actions.filechange

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vcs.FilePath
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.change.executeSquashInto
import `in`.kkkev.jjidea.actions.filePaths
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.actions.singleRepoForFiles
import `in`.kkkev.jjidea.jj.ChangeService
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.runRecoverableInBackground
import `in`.kkkev.jjidea.jj.whenWorkingCopyAvailable
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.squash.SquashIntoDialog
import `in`.kkkev.jjidea.ui.squash.SquashMode
import `in`.kkkev.jjidea.util.runLater

/**
 * Squash [selectedFiles] from [source] into [destination] with the destination fixed - the
 * squash-by-drag counterpart of [SquashIntoFilesAction] (jj-idea-yvry), called from
 * [in.kkkev.jjidea.ui.dnd.DropPerformers.forLogTable] once a
 * [in.kkkev.jjidea.ui.dnd.DropOperation.SquashFiles] drop resolves. Unlike that action's free
 * destination picker, the drop target is the whole point of the gesture, so
 * [SquashMode.PickDestination.candidates] is pinned to exactly [destination] - the
 * predefined-candidates path already auto-selects a single candidate
 * ([SquashIntoDialog]'s `setupPredefinedCandidates`), which is what "pre-filled destination"
 * means for this gesture's acceptance criteria. [SquashMode.PickDestination.fixedDestination] is
 * also set, so the dialog's title reads "Squash Into" rather than the parent-candidates flow's
 * "Squash into Parent" - a drop target is rarely the parent, and a fixed title naming the wrong
 * relationship would mislead regardless of what it's actually squashing into.
 */
internal fun performFileSquashInto(
    source: LogEntry,
    destination: LogEntry,
    selectedFiles: Set<FilePath>
) {
    val repo: JujutsuRepository = source.repo
    repo.runRecoverableInBackground(retry = { performFileSquashInto(source, destination, selectedFiles) }) {
        val changes = ChangeService.loadChanges(source)

        runLater {
            val dialog = SquashIntoDialog(
                repo,
                SquashMode.PickDestination(listOf(source), candidates = listOf(destination), fixedDestination = true),
                changes,
                preSelectedFiles = selectedFiles
            )
            if (dialog.showAndGet()) {
                dialog.result?.let { executeSquashInto(repo, listOf(source), it) }
            }
        }
    }
}

/**
 * Squash selected files into an arbitrary destination change, from a file changes context.
 *
 * Works in the same contexts as [SquashFilesAction] (working copy panel, commit details,
 * project view, editor) but opens the destination-picker dialog instead of squashing into
 * the parent. Enabled for any mutable change, including merge commits.
 */
class SquashIntoFilesAction : DumbAwareAction(
    JujutsuBundle.message("action.squash.into.files"),
    JujutsuBundle.message("action.squash.into.files.description"),
    JujutsuIcons.Squash
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val entry = resolveEntry(e)
        e.presentation.isEnabledAndVisible = entry != null && !entry.immutable
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entry = resolveEntry(e) ?: return
        val preSelectedFiles = e.filePaths.toSet()
        val repo = entry.repo

        repo.runRecoverableInBackground(retry = { actionPerformed(e) }) {
            val changes = ChangeService.loadChanges(entry)

            runLater {
                val dialog = SquashIntoDialog(
                    repo,
                    SquashMode.PickDestination(listOf(entry)),
                    changes,
                    preSelectedFiles
                )
                if (dialog.showAndGet()) {
                    dialog.result?.let { executeSquashInto(repo, listOf(entry), it) }
                }
            }
        }
    }

    private fun resolveEntry(e: AnActionEvent) = e.logEntry ?: e.singleRepoForFiles?.whenWorkingCopyAvailable { it }
}
