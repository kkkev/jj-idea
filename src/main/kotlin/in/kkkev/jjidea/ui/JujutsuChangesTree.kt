package `in`.kkkev.jjidea.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ui.AsyncChangesTreeImpl
import com.intellij.openapi.vcs.changes.ui.ChangesGroupingPolicyFactory
import com.intellij.openapi.vcs.changes.ui.ChangesGroupingSupport
import com.intellij.openapi.vcs.changes.ui.ChangesGroupingSupport.Companion.DIRECTORY_GROUPING
import com.intellij.openapi.vcs.changes.ui.ChangesGroupingSupport.Companion.REPOSITORY_GROUPING
import com.intellij.openapi.vcs.changes.ui.TreeModelBuilder
import javax.swing.tree.DefaultTreeModel

/**
 * Changes tree for Jujutsu tool window using IntelliJ's built-in changes tree infrastructure.
 * Provides grouping, speed search, and standard VCS actions.
 */
class JujutsuChangesTree(project: Project) : AsyncChangesTreeImpl.Changes(project, false, true) {
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
}
