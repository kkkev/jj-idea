package `in`.kkkev.jjidea.ui.common

import com.intellij.ide.CommonActionsManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup

/**
 * Standard Expand All / Collapse All / Group By toolbar for a [JujutsuChangesTree], shared by
 * [FileSelectionPanel], [in.kkkev.jjidea.ui.log.JujutsuCommitDetailsPanel] and
 * [JujutsuCompareChangesPanel] so the action set can't drift between them.
 */
fun changesTreeToolbar(tree: JujutsuChangesTree, place: String): ActionToolbar {
    val group = DefaultActionGroup()
    val treeExpander = tree.treeExpander
    val commonActionsManager = CommonActionsManager.getInstance()
    group.add(commonActionsManager.createExpandAllAction(treeExpander, tree))
    group.add(commonActionsManager.createCollapseAllAction(treeExpander, tree))
    group.addSeparator()
    group.add(ActionManager.getInstance().getAction("ChangesView.GroupBy"))

    return ActionManager.getInstance()
        .createActionToolbar(place, group, true)
        .apply { targetComponent = tree }
}
