package `in`.kkkev.jjidea.actions.top

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.ui.oplog.isOperationLogEnabled
import `in`.kkkev.jjidea.vcs.isJujutsu

/**
 * The "+" in the VCS tool window tab strip. With nothing extra to offer it behaves exactly as
 * [NewLogTabAction] always did (a single click opens a new log tab); when the Operations tab
 * preview feature is enabled it becomes a small popup offering both "New Log Tab" and "Show
 * Operation Log" (jj-idea-aii0). Deciding in [update] keeps users without the preview from ever
 * seeing a one-item menu.
 */
class NewTabGroup : ActionGroup(
    JujutsuBundle.message("log.action.newtab"),
    JujutsuBundle.message("log.action.newtab.tooltip"),
    AllIcons.General.Add
) {
    init {
        isPopup = true
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val manager = ActionManager.getInstance()
        return listOfNotNull(manager.getAction(NEW_LOG_TAB), manager.getAction(SHOW_OPERATION_LOG)).toTypedArray()
    }

    override fun update(e: AnActionEvent) = with(e.presentation) {
        isVisible = e.project.isJujutsu
        val menu = isOperationLogEnabled()
        isPopupGroup = menu
        isPerformGroup = !menu
    }

    /** Only reached when [update] marked this a perform-group, i.e. there is no menu to show. */
    override fun actionPerformed(e: AnActionEvent) {
        ActionManager.getInstance().getAction(NEW_LOG_TAB)?.let {
            ActionUtil.performActionDumbAwareWithCallbacks(it, e)
        }
    }

    private companion object {
        const val NEW_LOG_TAB = "Jujutsu.NewLogTab"
        const val SHOW_OPERATION_LOG = "Jujutsu.ShowOperationLog"
    }
}
