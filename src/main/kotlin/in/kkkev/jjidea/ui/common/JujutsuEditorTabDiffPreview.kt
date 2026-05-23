package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.vcs.changes.ChangeViewDiffRequestProcessor.Wrapper
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.changes.ui.DefaultChangesTreeDiffPreviewHandler
import com.intellij.openapi.vcs.changes.ui.TreeHandlerEditorDiffPreview

class JujutsuEditorTabDiffPreview(
    tree: ChangesTree,
    private val contextLabel: () -> String? = { null }
) : TreeHandlerEditorDiffPreview(tree, DefaultChangesTreeDiffPreviewHandler) {
    override fun getEditorTabName(wrapper: Wrapper?): String {
        val fileName = wrapper?.filePath?.name ?: return "Diff"
        val ctx = contextLabel() ?: return fileName
        return "$ctx: $fileName"
    }

    override fun handleSingleClick() {
        if (isPreviewOpen()) openPreview(false)
    }
}
