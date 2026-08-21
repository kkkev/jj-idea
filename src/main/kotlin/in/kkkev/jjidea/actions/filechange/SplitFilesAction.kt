package `in`.kkkev.jjidea.actions.filechange

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.change.executeSplit
import `in`.kkkev.jjidea.actions.filePaths
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.actions.singleRepoForFiles
import `in`.kkkev.jjidea.jj.ChangeService
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.split.SplitDialog
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

/** Resolves the [LogEntry] a file-change split action should operate on. */
internal fun resolveSplitEntry(e: AnActionEvent): LogEntry? = e.logEntry ?: e.singleRepoForFiles?.workingCopy

/**
 * Shared implementation for the two file-change split entry points ([SplitFilesAction] and
 * [SplitIntoNewParentFilesAction]): loads changes on a background thread, then opens
 * [SplitDialog] with the right-clicked [selectedFiles] pre-ticked. Ticking always means "moves to
 * the new commit" in both modes ([SplitDialog]'s `newParent` flag - not a tick-inversion hack -
 * decides whether that new commit is inserted before or after the original; see the two actions'
 * KDoc for the semantic difference).
 */
internal fun performFileSplit(project: Project, entry: LogEntry, selectedFiles: Set<FilePath>, newParent: Boolean) {
    runInBackground {
        val changes = ChangeService.loadChanges(entry)

        runLater {
            val dialog = SplitDialog(project, entry, changes, preSelectedFiles = selectedFiles, newParent = newParent)
            if (dialog.showAndGet()) {
                dialog.result?.let { executeSplit(project, entry, it) }
            }
        }
    }
}

/**
 * Split selected files into a new **child** change (succeeds the original, which keeps its
 * unselected files).
 *
 * Works in three contexts:
 * - Working copy panel: resolves WC entry from state model (no LOG_ENTRY)
 * - Commit details panel / historical editor: uses LOG_ENTRY from data context
 * - Project view / current editor: resolves WC entry from state model (no LOG_ENTRY)
 *
 * Hidden when the entry is immutable.
 */
class SplitFilesAction : DumbAwareAction(
    JujutsuBundle.message("action.split.files"),
    JujutsuBundle.message("action.split.files.description"),
    JujutsuIcons.Split
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val entry = resolveSplitEntry(e)
        e.presentation.isEnabledAndVisible = entry?.immutable == false
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val entry = resolveSplitEntry(e) ?: return
        performFileSplit(project, entry, e.filePaths.toSet(), newParent = false)
    }
}

/**
 * Split selected files into a new **parent** change (`jj split -B`, inserted before the original,
 * which keeps its own change ID and its unselected files) - GitHub #74's stacked-changes
 * workflow: verify a large set of changes, then peel them off backwards into small commits one at
 * a time. See [SplitDialog]'s KDoc for the full change-ID/working-copy semantics (jj-idea-tkog).
 *
 * Same contexts and immutability gating as [SplitFilesAction]; only [SplitDialog]'s `newParent`
 * flag differs.
 */
class SplitIntoNewParentFilesAction : DumbAwareAction(
    JujutsuBundle.message("action.split.files.intoParent"),
    JujutsuBundle.message("action.split.files.intoParent.description"),
    JujutsuIcons.Split
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val entry = resolveSplitEntry(e)
        e.presentation.isEnabledAndVisible = entry?.immutable == false
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val entry = resolveSplitEntry(e) ?: return
        performFileSplit(project, entry, e.filePaths.toSet(), newParent = true)
    }
}
