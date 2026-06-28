package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ui.AsyncChangesTreeImpl
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesGroupingPolicyFactory
import com.intellij.openapi.vcs.changes.ui.ChangesGroupingSupport
import com.intellij.openapi.vcs.changes.ui.ChangesGroupingSupport.Companion.DIRECTORY_GROUPING
import com.intellij.openapi.vcs.changes.ui.ChangesGroupingSupport.Companion.REPOSITORY_GROUPING
import com.intellij.openapi.vcs.changes.ui.TreeModelBuilder
import com.intellij.util.ui.ThreeStateCheckBox
import `in`.kkkev.jjidea.actions.filechange.fileChangeActionGroup
import javax.swing.tree.DefaultTreeModel

/**
 * Changes tree for Jujutsu tool window using IntelliJ's built-in changes tree infrastructure.
 * Provides grouping, speed search, and standard VCS actions.
 */
class JujutsuChangesTree(project: Project, showCheckboxes: Boolean = false) :
    AsyncChangesTreeImpl.Changes(project, showCheckboxes, true) {
    /**
     * Optional additional data provider to inject context-specific data keys.
     * Called from [uiDataSnapshot] to allow parent panels to provide context like
     * [in.kkkev.jjidea.actions.JujutsuDataKeys.LOG_ENTRY].
     */
    var additionalDataProvider: ((DataSink) -> Unit)? = null

    /**
     * Changes that should be shown as **partially** included (half-checked).
     *
     * A change in this set is still counted as included in the inclusion model (checked),
     * but the checkbox renders as [ThreeStateCheckBox.State.DONT_CARE] to signal that only
     * a subset of its hunks goes into the first commit. Parent/directory nodes that contain
     * at least one partial descendant also show DONT_CARE.
     *
     * Setting this property triggers a repaint. Clearing it (empty set) restores the
     * standard binary SELECTED/NOT_SELECTED rendering from the inclusion model.
     */
    var partialChanges: Set<Change> = emptySet()
        set(value) {
            field = value
            repaint()
        }

    override fun getNodeStatus(node: ChangesBrowserNode<*>): ThreeStateCheckBox.State {
        // Note: partialChanges may be null during superclass construction (called before Kotlin
        // property initialization completes), so we capture it and guard explicitly.
        val partial = partialChanges
        @Suppress("SENSELESS_COMPARISON")
        if (partial != null && partial.isNotEmpty() && node.traverseObjectsUnder().any { it in partial }) {
            return ThreeStateCheckBox.State.DONT_CARE
        }
        return super.getNodeStatus(node)
    }

    init {
        // Use KEEP_NON_EMPTY strategy: preserves user's manual expansion/collapse actions
        // while expanding default nodes (including new ones) when tree is rebuilt
        treeStateStrategy = KEEP_NON_EMPTY
    }

    override fun buildTreeModel(grouping: ChangesGroupingPolicyFactory, changes: List<Change>): DefaultTreeModel =
        TreeModelBuilder.buildFromChanges(myProject, grouping, changes, null)

    override fun installGroupingSupport(): ChangesGroupingSupport {
        val support = ChangesGroupingSupport(myProject, this, false)

        // Initialize with directory and repository grouping by default for multi-root support
        val defaultGrouping = setOf(DIRECTORY_GROUPING, REPOSITORY_GROUPING)
        support.setGroupingKeysOrSkip(defaultGrouping)

        return support
    }

    override fun getToggleClickCount(): Int = 2 // Double-click to toggle

    override fun uiDataSnapshot(sink: DataSink) {
        super.uiDataSnapshot(sink)
        sink[VcsDataKeys.CHANGES] = selectedChanges.toTypedArray()
        additionalDataProvider?.invoke(sink)
    }

    fun installHandlers() {
        installPopupHandler(fileChangeActionGroup())
    }
}
