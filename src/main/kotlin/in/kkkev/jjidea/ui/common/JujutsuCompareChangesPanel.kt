package `in`.kkkev.jjidea.ui.common

import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.impl.ChangesBrowserToolWindow
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Changes-tree-backed pane for the multi-file compare actions (Compare with Working Copy, Show
 * Diff in New Tab): unlike the bare [com.intellij.diff.chains.SimpleDiffRequestChain] those used
 * to open, this keeps a persistent list of the differing files alongside the diff, with the
 * current file highlighted -- mirroring git4idea's "Compare with Branch".
 *
 * No [in.kkkev.jjidea.actions.JujutsuDataKeys.LOG_ENTRY] is injected: a compare-between-two-revisions
 * view has no single owning entry, so entry-scoped actions correctly stay disabled here.
 */
class JujutsuCompareChangesPanel(project: Project, contextLabel: () -> String?) :
    JPanel(BorderLayout()), Disposable, UiDataProvider {
    internal val changesTree = JujutsuChangesTree(project)
    internal val diffPreview = JujutsuEditorTabDiffPreview(changesTree, contextLabel)

    init {
        Disposer.register(this, diffPreview)

        val toolbar = changesTreeToolbar(changesTree, ActionPlaces.CHANGES_VIEW_TOOLBAR)
        add(toolbar.component, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(changesTree), BorderLayout.CENTER)

        changesTree.installHandlers()
    }

    override fun uiDataSnapshot(sink: DataSink) {
        sink[DiffDataKeys.EDITOR_TAB_DIFF_PREVIEW] = diffPreview
    }

    /**
     * Display [changes] in the tree, expand it fully and select the first file. Does not open the
     * diff itself -- see [showCompareChangesTab], the sole production caller, which opens it via
     * [diffPreview] once the selection above has landed.
     */
    fun setChanges(changes: List<Change>) {
        changesTree.setChangesToDisplay(changes)
        changesTree.invokeAfterRefresh {
            changesTree.treeExpander.expandAll()
            changes.firstOrNull()?.let { changesTree.selectFile(ChangesUtil.getFilePath(it)) }
        }
    }

    override fun dispose() {
        // Cleanup if needed
    }

    companion object {
        /**
         * Build a [JujutsuCompareChangesPanel] for [changes] and show it as a tab in the platform's
         * VcsChanges tool window, replacing any prior compare tab. No-op guard for empty [changes]
         * is the caller's responsibility (they show an info notification instead).
         */
        fun showCompareChangesTab(
            project: Project,
            changes: List<Change>,
            tabTitle: String,
            contextLabel: () -> String?
        ) {
            val panel = JujutsuCompareChangesPanel(project, contextLabel)
            panel.setChanges(changes)
            // Preserves the "diff shows immediately" behavior of the bare diff chain this pane
            // replaces -- runs after setChanges's own invokeAfterRefresh callback (above) has
            // landed the initial selection, since callbacks queued for the same refresh fire in
            // registration order.
            panel.changesTree.invokeAfterRefresh { panel.diffPreview.performDiffAction() }

            val content = ContentFactory.getInstance().createContent(panel, tabTitle, false)
            content.preferredFocusableComponent = panel.changesTree
            content.setDisposer(panel)
            ChangesBrowserToolWindow.showTab(project, content)
        }
    }
}
