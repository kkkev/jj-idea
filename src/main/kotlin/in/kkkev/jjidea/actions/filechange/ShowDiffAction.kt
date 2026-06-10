package `in`.kkkev.jjidea.actions.filechange

import com.intellij.diff.DiffManager
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.changes
import `in`.kkkev.jjidea.actions.files
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.actions.repoForFile
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

/**
 * Compare file(s) from the parent of a revision with the revision itself.
 *
 * When invoked from a panel that exposes [DiffDataKeys.EDITOR_TAB_DIFF_PREVIEW], routes through
 * the live preview tab so the diff opens/focuses the single reusable editor tab. Falls back to
 * individual diff dialogs in other contexts (project view, editor gutter, etc.).
 */
class ShowDiffAction :
    DumbAwareAction(
        JujutsuBundle.message("action.show.diff"),
        JujutsuBundle.message("action.show.diff.description"),
        AllIcons.Actions.Diff
    ) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val preview = e.getData(DiffDataKeys.EDITOR_TAB_DIFF_PREVIEW)
        if (preview != null && preview.performDiffAction()) return

        val changes = e.changes
        val files = e.files
        runInBackground {
            val requests = buildDiffRequests(project, changes, files)
            if (requests.isNotEmpty()) {
                val diffManager = DiffManager.getInstance()
                runLater { requests.forEach { diffManager.showDiff(project, it) } }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val hasChanges = e.changes.isNotEmpty()
        val hasLogEntry = e.logEntry != null
        val hasRepo = e.repoForFile != null
        e.presentation.isEnabledAndVisible =
            hasChanges ||
            hasLogEntry ||
            (hasRepo && e.place != ActionPlaces.KEYBOARD_SHORTCUT)
    }
}
