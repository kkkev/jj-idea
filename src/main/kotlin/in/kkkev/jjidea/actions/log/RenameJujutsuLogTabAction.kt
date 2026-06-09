package `in`.kkkev.jjidea.actions.log

import com.intellij.ide.actions.ToolWindowTabRenameActionBase
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.Content
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.ui.log.JujutsuCustomLogTabManager

/**
 * Right-click "Rename…" action for Jujutsu log tabs in the VCS tool window.
 *
 * Uses the platform's [ToolWindowTabRenameActionBase] to show an inline balloon editor on the
 * tab label. Only enabled for tabs that carry [JujutsuCustomLogTabManager.JUJUTSU_LOG_CONTENT_KEY]
 * so it does not appear on "Local Changes", Git "Log", or other unrelated tool-window tabs.
 *
 * Registered in `ToolWindowContextMenu` via plugin.xml so it appears in the right-click popup
 * of every tool-window tab; the update() check suppresses it everywhere except our own tabs.
 */
class RenameJujutsuLogTabAction : ToolWindowTabRenameActionBase(
    ChangesViewContentManager.TOOLWINDOW_ID,
    JujutsuBundle.message("log.action.renametab.label")
) {
    override fun update(e: AnActionEvent, toolWindow: ToolWindow, selectedContent: Content?) {
        // Let the base class verify the tool-window ID; then additionally restrict to our tabs.
        super.update(e, toolWindow, selectedContent)
        if (e.presentation.isEnabledAndVisible) {
            e.presentation.isEnabledAndVisible =
                selectedContent?.getUserData(JujutsuCustomLogTabManager.JUJUTSU_LOG_CONTENT_KEY) == true
        }
    }

    override fun applyContentDisplayName(content: Content, project: Project, newContentName: String) {
        JujutsuCustomLogTabManager.getInstance(project).renameTab(content, newContentName)
    }
}
