package `in`.kkkev.jjidea.ui.common

import com.intellij.ide.CommonActionsManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.ui.ScrollPaneFactory
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Reusable file selection panel with checkboxes, shared between squash and split dialogs.
 *
 * Wraps a [JujutsuChangesTree] with `showCheckboxes = true` and a toolbar with
 * Expand All, Collapse All, and Group By Directory actions.
 *
 * All files are checked by default. Use [includedChanges] to get the user's selection.
 */
class FileSelectionPanel(project: Project) : JPanel(BorderLayout()) {
    val changesTree = JujutsuChangesTree(project, showCheckboxes = true)

    init {
        val toolbar = createToolbar()
        toolbar.targetComponent = changesTree
        add(toolbar.component, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(changesTree), BorderLayout.CENTER)
    }

    /**
     * Set the changes to display and check all by default.
     */
    fun setChanges(changes: List<Change>) {
        changesTree.setChangesToDisplay(changes)
        changesTree.setIncludedChanges(changes)
    }

    /**
     * Set the changes to display and check only the specified subset.
     */
    fun setChanges(changes: List<Change>, included: Collection<Change>) {
        changesTree.setChangesToDisplay(changes)
        changesTree.setIncludedChanges(included)
    }

    /**
     * The currently checked (included) changes.
     */
    val includedChanges: Collection<Change> get() = changesTree.includedChanges

    /**
     * Whether all displayed changes are included.
     */
    val allIncluded: Boolean get() = includedChanges.size == changesTree.changes.size

    /**
     * Register a listener that is called when inclusion (checkbox state) changes.
     */
    fun addInclusionListener(listener: Runnable) {
        changesTree.setInclusionListener(listener)
    }

    private fun createToolbar() = ActionManager.getInstance().createActionToolbar(
        ActionPlaces.CHANGES_VIEW_TOOLBAR,
        DefaultActionGroup().apply {
            val commonActionsManager = CommonActionsManager.getInstance()
            val treeExpander = changesTree.treeExpander
            add(commonActionsManager.createExpandAllAction(treeExpander, changesTree))
            add(commonActionsManager.createCollapseAllAction(treeExpander, changesTree))
            addSeparator()
            add(ActionManager.getInstance().getAction("ChangesView.GroupBy"))
        },
        true
    )
}
