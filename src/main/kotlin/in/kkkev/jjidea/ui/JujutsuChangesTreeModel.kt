package `in`.kkkev.jjidea.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import java.io.File
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Tree model for displaying changes grouped by directory
 */
class JujutsuChangesTreeModel(
    private val project: Project?,
    private val groupByDirectory: Boolean
) {

    fun buildModel(changes: List<Change>): DefaultTreeModel {
        val root = DefaultMutableTreeNode("Changes")

        if (groupByDirectory) {
            buildGroupedTree(root, changes)
        } else {
            buildFlatTree(root, changes)
        }

        return DefaultTreeModel(root)
    }

    private fun buildFlatTree(root: DefaultMutableTreeNode, changes: List<Change>) {
        for (change in changes) {
            val node = ChangeNode(change)
            root.add(node)
        }
    }

    private fun buildGroupedTree(root: DefaultMutableTreeNode, changes: List<Change>) {
        // Group changes by directory
        val changesByDirectory = mutableMapOf<String, MutableList<Change>>()

        for (change in changes) {
            val filePath = change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path ?: continue
            val directory = File(filePath).parent ?: ""
            changesByDirectory.getOrPut(directory) { mutableListOf() }.add(change)
        }

        // Create directory nodes
        for ((directory, dirChanges) in changesByDirectory.toSortedMap()) {
            val dirNode = DirectoryNode(directory, dirChanges.size)
            root.add(dirNode)

            for (change in dirChanges.sortedBy {
                File(it.afterRevision?.file?.path ?: it.beforeRevision?.file?.path ?: "").name
            }) {
                dirNode.add(ChangeNode(change))
            }
        }
    }

    /**
     * Node representing a directory with a file count
     */
    class DirectoryNode(val directory: String, val fileCount: Int) : DefaultMutableTreeNode() {
        override fun toString(): String {
            val dirName = if (directory.isEmpty()) "." else File(directory).name
            return "$dirName ($fileCount ${if (fileCount == 1) "file" else "files"})"
        }
    }

    /**
     * Node representing a single change
     */
    class ChangeNode(val change: Change) : DefaultMutableTreeNode(change) {
        override fun toString(): String {
            val filePath = change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path ?: "Unknown"
            return File(filePath).name
        }
    }
}
