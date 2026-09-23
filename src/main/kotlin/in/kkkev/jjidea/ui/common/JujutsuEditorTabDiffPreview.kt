package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeViewDiffRequestProcessor.Wrapper
import com.intellij.openapi.vcs.changes.ui.DefaultChangesTreeDiffPreviewHandler
import com.intellij.openapi.vcs.changes.ui.TreeHandlerEditorDiffPreview
import com.intellij.openapi.wm.ToolWindowManager
import `in`.kkkev.jjidea.actions.change.resolveConflicts
import `in`.kkkev.jjidea.vcs.filePath
import java.awt.event.MouseEvent

class JujutsuEditorTabDiffPreview(
    private val jjTree: JujutsuChangesTree,
    // Only the Working Copy tool window's tree opts in: double-clicking a conflicted row there
    // resolves that one file instead of opening the (read-only, for a conflict) diff preview -
    // GitHub #66's "pick which file to start with" without a modal queue. Off by default: the
    // commit-details pane and compare-changes panel show historical/read-only trees where
    // resolving isn't meaningful (see resolveConflictsAvailability's NEEDS_EDIT state).
    private val resolveConflictsOnDoubleClick: Boolean = false,
    private val contextLabel: () -> String? = { null }
) : TreeHandlerEditorDiffPreview(jjTree, DefaultChangesTreeDiffPreviewHandler) {
    override fun getEditorTabName(wrapper: Wrapper?): String {
        val fileName = wrapper?.filePath?.name ?: return "Diff"
        val ctx = contextLabel() ?: return fileName
        return "$ctx: $fileName"
    }

    override fun handleDoubleClick(e: MouseEvent): Boolean {
        val conflictedChange = conflictForDoubleClick(resolveConflictsOnDoubleClick, jjTree.selectedChanges)
        val conflictedFile = conflictedChange?.filePath?.virtualFile
        if (conflictedFile != null) {
            resolveConflicts(project, listOf(conflictedFile))
            return true
        }
        return super.handleDoubleClick(e)
    }

    // The platform routes handleSingleClick() off a plain tree selection listener, so it also
    // fires for the selection restore inside ChangesTree.updateTreeModel — i.e. every time a
    // save or log refresh rebuilds the tree. Raising the preview tab then yanks the editor
    // away from the file the user is working in (GitHub #67). Content stays in sync either
    // way: TreeHandlerChangesTreeTracker refreshes the open preview on model changes.
    override fun handleSingleClick() {
        val editorActive = ToolWindowManager.getInstance(project).isEditorComponentActive
        if (shouldRaisePreview(isPreviewOpen(), tree.isModelUpdateInProgress, editorActive)) {
            openPreview(false)
        }
    }
}

internal fun shouldRaisePreview(previewOpen: Boolean, modelUpdateInProgress: Boolean, editorActive: Boolean) =
    previewOpen && !modelUpdateInProgress && !editorActive

/**
 * The single conflicted [Change] a conflict-resolving double-click should act on, or `null` when
 * the double click should fall through to the ordinary diff-preview behaviour (GitHub #66). Only
 * a single conflicted selection qualifies - a multi-selection double-click stays a
 * no-op-into-diff-preview rather than silently picking one file out of several to resolve.
 */
internal fun conflictForDoubleClick(resolveConflictsOnDoubleClick: Boolean, selectedChanges: List<Change>): Change? {
    if (!resolveConflictsOnDoubleClick) return null
    val change = selectedChanges.singleOrNull() ?: return null
    return change.takeIf { it.fileStatus == FileStatus.MERGED_WITH_CONFLICTS }
}
