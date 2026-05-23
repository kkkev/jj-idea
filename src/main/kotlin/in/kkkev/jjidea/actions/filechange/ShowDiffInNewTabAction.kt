package `in`.kkkev.jjidea.actions.filechange

import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.changes
import `in`.kkkev.jjidea.actions.files
import `in`.kkkev.jjidea.actions.repoForFile
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

class ShowDiffInNewTabAction : DumbAwareAction(
    JujutsuBundle.message("action.show.diff.new.tab"),
    JujutsuBundle.message("action.show.diff.new.tab.description"),
    AllIcons.Actions.OpenNewTab
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        runInBackground {
            val requests = buildDiffRequests(project, e.changes, e.files)
            if (requests.isEmpty()) return@runInBackground
            val tabTitle = if (requests.size == 1) {
                requests.first().title ?: "Diff"
            } else {
                "${requests.size} files"
            }
            val file = ChainDiffVirtualFile(SimpleDiffRequestChain(requests), tabTitle)
            runLater { FileEditorManager.getInstance(project).openFile(file, true) }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.changes.isNotEmpty() || e.repoForFile != null
    }
}
