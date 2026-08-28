package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.actionSystem.ActionPlaces
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
        val toolbar = changesTreeToolbar(changesTree, ActionPlaces.CHANGES_VIEW_TOOLBAR)
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

    /**
     * Mark [changes] as partially included so the tree renders them with a half-checked box.
     * Pass an empty set to clear all partial marks.
     */
    fun setPartialChanges(changes: Set<Change>) {
        changesTree.partialChanges = changes
    }

    /**
     * Tick or untick a single [change], a no-op if it's already in the requested state.
     * Used by hunk-picking (see [in.kkkev.jjidea.ui.common.HunkPickPreviewController]) to sync
     * the checkbox when a picker result collapses to a whole-file "fully moved"/"fully stays"
     * outcome.
     */
    fun setIncluded(change: Change, included: Boolean) {
        val current = includedChanges.toMutableList()
        val already = change in current
        if (included && !already) {
            current.add(change)
            changesTree.setIncludedChanges(current)
        } else if (!included && already) {
            current.remove(change)
            changesTree.setIncludedChanges(current)
        }
    }
}
