package `in`.kkkev.jjidea.actions.file

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import `in`.kkkev.jjidea.actions.file
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor

/**
 * Action group for Jujutsu VCS in editor context menu
 */
class JujutsuEditorActionGroup : DefaultActionGroup() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.file?.let { e.project?.possibleJujutsuRepositoryFor(it) } != null
    }
}
