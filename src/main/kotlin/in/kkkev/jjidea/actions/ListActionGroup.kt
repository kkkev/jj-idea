package `in`.kkkev.jjidea.actions

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionUpdateThreadAware
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Immutable action group over a precomputed child list.
 *
 * Unlike [com.intellij.openapi.actionSystem.DefaultActionGroup], construction does no per-child
 * work: `DefaultActionGroup.add` resolves action ids for every existing child on each call, making
 * a group of N children O(N²) to build (jj-idea-bok6, GitHub #136). Use this for large, dynamically
 * built popup lists.
 */
class ListActionGroup(children: List<AnAction>) :
    ActionGroup(),
    ActionUpdateThreadAware.Recursive {
    private val children = children.toTypedArray()

    override fun getChildren(e: AnActionEvent?): Array<AnAction> = children

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
