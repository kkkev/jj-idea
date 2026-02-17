package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ui.AsyncChangesTreeImpl
import com.intellij.openapi.vcs.changes.ui.ChangesGroupingPolicyFactory
import com.intellij.openapi.vcs.changes.ui.ChangesGroupingSupport
import com.intellij.openapi.vcs.changes.ui.ChangesGroupingSupport.Companion.DIRECTORY_GROUPING
import com.intellij.openapi.vcs.changes.ui.ChangesGroupingSupport.Companion.REPOSITORY_GROUPING
import com.intellij.openapi.vcs.changes.ui.TreeModelBuilder
import `in`.kkkev.jjidea.vcs.actions.fileChangeActionGroup
import javax.swing.tree.DefaultTreeModel

/**
 * Changes tree for Jujutsu tool window using IntelliJ's built-in changes tree infrastructure.
 * Provides grouping, speed search, and standard VCS actions.
 */
class JujutsuChangesTree(project: Project) : AsyncChangesTreeImpl.Changes(project, false, true) {
    /**
     * Optional additional data provider to inject context-specific data keys.
     * Called from [uiDataSnapshot] to allow parent panels to provide context like [JujutsuDataKeys.LOG_ENTRY].
     */
    var additionalDataProvider: ((DataSink) -> Unit)? = null

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
        sink[CommonDataKeys.VIRTUAL_FILE_ARRAY] = selectedChanges.mapNotNull { it.virtualFile }.toTypedArray()
        additionalDataProvider?.invoke(sink)
    }

    /**
     * Install standard handlers for double-click, Enter key, and context menu.
     * Uses the Diff.ShowDiff action which reads VcsDataKeys.CHANGES from our uiDataSnapshot.
     */
    fun installHandlers() {
        val diffAction = ActionManager.getInstance().getAction("Diff.ShowDiff")!!
        val invokeDiff: () -> Boolean = {
            if (selectedChanges.isNotEmpty()) {
                ActionUtil.invokeAction(diffAction, this, ActionPlaces.CHANGES_VIEW_POPUP, null, null)
                true
            } else {
                false
            }
        }
        setDoubleClickHandler { invokeDiff() }
        setEnterKeyHandler { invokeDiff() }
        installPopupHandler(fileChangeActionGroup())
    }
}
