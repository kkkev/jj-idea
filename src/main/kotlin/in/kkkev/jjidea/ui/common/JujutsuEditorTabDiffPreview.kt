package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.vcs.changes.ChangeViewDiffRequestProcessor.Wrapper
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.changes.ui.DefaultChangesTreeDiffPreviewHandler
import com.intellij.openapi.vcs.changes.ui.TreeHandlerEditorDiffPreview
import com.intellij.openapi.wm.ToolWindowManager

class JujutsuEditorTabDiffPreview(
    tree: ChangesTree,
    private val contextLabel: () -> String? = { null }
) : TreeHandlerEditorDiffPreview(tree, DefaultChangesTreeDiffPreviewHandler) {
    override fun getEditorTabName(wrapper: Wrapper?): String {
        val fileName = wrapper?.filePath?.name ?: return "Diff"
        val ctx = contextLabel() ?: return fileName
        return "$ctx: $fileName"
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
