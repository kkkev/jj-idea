package `in`.kkkev.jjidea.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionWrapperUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Toolbar entry that delegates to whichever action is currently registered under [actionId],
 * re-resolved via [ActionManager] on every call instead of once at construction.
 *
 * [in.kkkev.jjidea.ui.common.CommitTablePanel] builds its toolbar's action list once and keeps it
 * for the panel's lifetime. If that panel outlives a dynamic plugin unload/reload (jj-idea-nd8x —
 * e.g. an in-place update while its tab is still open), an `ActionManager.getAction(id)` instance
 * captured at construction is left over from the old plugin classloader; the toolbar keeps calling
 * its `update()`, and any cross-classloader service lookup inside it throws `ClassCastException`.
 * Resolving by id on every call always delegates to whichever instance the *current* plugin
 * classloader has registered, so a stale delegate is never invoked.
 */
class LazyActionById(private val actionId: String) : AnAction() {
    private val delegate: AnAction?
        get() = ActionManager.getInstance().getAction(actionId)

    override fun update(e: AnActionEvent) {
        val action = delegate
        if (action == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        ActionWrapperUtil.update(e, this, action)
    }

    override fun actionPerformed(e: AnActionEvent) {
        delegate?.let { ActionWrapperUtil.actionPerformed(e, this, it) }
    }

    override fun getActionUpdateThread(): ActionUpdateThread =
        delegate?.let { ActionWrapperUtil.getActionUpdateThread(this, it) } ?: ActionUpdateThread.BGT
}
