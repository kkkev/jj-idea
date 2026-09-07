package `in`.kkkev.jjidea.ui.log.bookmarks

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.DefaultTreeExpander
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.IssueNavigationConfiguration
import com.intellij.ui.*
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.BackgroundActionGroup
import `in`.kkkev.jjidea.actions.bookmark.*
import `in`.kkkev.jjidea.actions.tag.deleteTagAction
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.remoteEntriesFor
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.ui.common.JujutsuColors
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.common.RepositoryIcons
import `in`.kkkev.jjidea.ui.components.*
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URI
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * The bookmarks panel (jj-idea-b2ae / GitHub #48): a tree of every repo's bookmarks and tags,
 * grouped hierarchically on `/` in the name, hosted in a splitter to the left of the log table
 * (see [in.kkkev.jjidea.ui.common.CommitTablePanel.installLeftComponent]).
 *
 * Modelled on git4idea's Branches dashboard: plain selection is inert (no filtering/navigation as
 * a side effect of clicking a row); [filterLogToBookmarkAction] and [navigateLogToBookmarkAction]
 * are explicit context-menu entries instead. The tree itself is a plain [Tree] +
 * [DefaultTreeModel] with [TreeSpeedSearch] rather than the platform's `FilteringTree` — this
 * codebase has no existing `FilteringTree`/`SimpleTree` usage to extend, and a plain tree gets the
 * same type-ahead search with far less machinery.
 */
class JujutsuBookmarksPanel(
    private val project: Project,
    /**
     * Per-node expansion overrides, keyed by the `/`-joined path of [BookmarkNode.displayName]s
     * from the tree root (see [LogWindowConfig.bookmarkNodeExpanded]). Only entries that differ
     * from a node's own default (see [BookmarkNode.Category.defaultExpanded]) are stored. Mutated
     * in place by user expand/collapse gestures; the caller owns saving it back to settings via
     * [onExpansionChanged].
     */
    private val expansionState: MutableMap<String, Boolean> = mutableMapOf(),
    private val onExpansionChanged: () -> Unit = {}
) : JPanel(BorderLayout()), Disposable {
    private val root = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(root)
    val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = BookmarkNodeRenderer(project)
    }

    // Coalesces a burst of reference/working-copy/closest-bookmark invalidations (e.g. a bookmark
    // create followed by the resulting log refresh) into a single tree rebuild, the same pattern
    // as UnifiedWorkingCopyPanel's reloadQueue (jj-idea-f21f).
    private val rebuildQueue = MergingUpdateQueue("bookmarksPanelRebuild", 200, true, null, this)

    /** Test seam for the rebuild fan-out scale guard: how many times [rebuild] actually ran. */
    var rebuildCount = 0
        private set

    /**
     * Guards [recordExpansion] while [rebuild] is programmatically replaying [expansionState] onto
     * the tree, so those replayed expand/collapse events aren't mistaken for user gestures and
     * written back to themselves.
     */
    private var applyingExpansionState = false

    init {
        add(createToolbar().component, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
        TreeSpeedSearch.installOn(tree, true) { path ->
            ((path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? BookmarkNode)?.displayName.orEmpty()
        }
        installPopupHandler()
        installLinkHandler()
        tree.addTreeExpansionListener(
            object : TreeExpansionListener {
                override fun treeExpanded(event: TreeExpansionEvent) = recordExpansion(event.path, true)
                override fun treeCollapsed(event: TreeExpansionEvent) = recordExpansion(event.path, false)
            }
        )
        // Registered against this panel (not project.stateModel) so closing a log tab
        // unregisters these listeners along with everything else Disposer tears down for it,
        // rather than leaking them for the state model's project-wide lifetime.
        with(project.stateModel) {
            references.connect(this@JujutsuBookmarksPanel) { queueRebuild() }
            workingCopies.connect(this@JujutsuBookmarksPanel) { queueRebuild() }
            closestBookmarks.connect(this@JujutsuBookmarksPanel) { queueRebuild() }
        }
        rebuild()
    }

    /**
     * Expand All / Collapse All (jj-idea-a7a7, GitHub #48), wiring the previously-unused
     * `bookmarks.panel.expandall`/`collapseall` bundle keys to real actions rather than adding new
     * ones. Modelled on [in.kkkev.jjidea.ui.common.changesTreeToolbar]'s
     * `CommonActionsManager`-based pair, but with this panel's own labels since
     * `CommonActionsManager`'s come with fixed platform text.
     */
    private fun createToolbar(): ActionToolbar {
        val treeExpander = DefaultTreeExpander(tree)
        val group = DefaultActionGroup(
            object : AnAction(JujutsuBundle.message("bookmarks.panel.expandall"), null, AllIcons.Actions.Expandall) {
                override fun actionPerformed(e: AnActionEvent) = treeExpander.expandAll()
            },
            object : AnAction(JujutsuBundle.message("bookmarks.panel.collapseall"), null, AllIcons.Actions.Collapseall) {
                override fun actionPerformed(e: AnActionEvent) = treeExpander.collapseAll()
            }
        )
        return ActionManager.getInstance()
            .createActionToolbar("Jujutsu.BookmarksPanel.Toolbar", group, true)
            .apply { targetComponent = tree }
    }

    private fun queueRebuild() {
        rebuildQueue.queue(Update.create("rebuild") { rebuild() })
    }

    /** Queues a rebuild the same way a state-model invalidation would (test seam). */
    fun scheduleRebuild() = queueRebuild()

    /** Forces the rebuild queue to process pending updates synchronously (test seam). */
    fun flushRebuildQueue() = rebuildQueue.flush()

    private fun rebuild() {
        rebuildCount++
        val nodes = buildBookmarkTree(
            project.stateModel.references.value,
            project.stateModel.workingCopies.value.values.associateBy { it.repo },
            project.stateModel.closestBookmarks.value
        )
        root.removeAllChildren()
        nodes.forEach { addChildren(root, it) }
        // A DefaultTreeModel structure-changed event (fired by reload()) resets Swing's own
        // per-path expansion tracking, so every rebuild must explicitly re-apply expansionState
        // instead of the old unconditional TreeUtil.expandAll (jj-idea-a7a7).
        treeModel.reload()
        applyingExpansionState = true
        try {
            for (i in 0 until root.childCount) {
                applyExpansionState(root.getChildAt(i) as DefaultMutableTreeNode, "")
            }
        } finally {
            applyingExpansionState = false
        }
    }

    /**
     * Recursively expands or collapses [treeNode] per [expansionState] (falling back to the
     * node's own default), continuing into its children only if it ends up expanded — a collapsed
     * node's descendants are left untouched rather than force-expanded, since [javax.swing.JTree]
     * expanding a path also makes its ancestors visible.
     */
    private fun applyExpansionState(treeNode: DefaultMutableTreeNode, parentPath: String) {
        val node = treeNode.userObject as? BookmarkNode ?: return
        if (treeNode.isLeaf) return
        val path = node.expansionPathKey(parentPath)
        val expanded = expansionState[path] ?: node.defaultExpanded()
        val treePath = TreePath(treeNode.path)
        if (expanded) {
            tree.expandPath(treePath)
            for (i in 0 until treeNode.childCount) {
                applyExpansionState(treeNode.getChildAt(i) as DefaultMutableTreeNode, path)
            }
        } else {
            tree.collapsePath(treePath)
        }
    }

    private fun recordExpansion(treePath: TreePath, expanded: Boolean) {
        if (applyingExpansionState) return
        val node = (treePath.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? BookmarkNode ?: return
        val path = pathKeyFor(treePath) ?: return
        if (expanded == node.defaultExpanded()) {
            expansionState.remove(path)
        } else {
            expansionState[path] = expanded
        }
        onExpansionChanged()
    }

    private fun pathKeyFor(treePath: TreePath): String? {
        val nodes = treePath.path.drop(1) // drop the invisible root
        val names = nodes.map { (it as? DefaultMutableTreeNode)?.userObject as? BookmarkNode ?: return null }
        return names.joinToString("/") { it.displayName }
    }

    private fun addChildren(parent: DefaultMutableTreeNode, node: BookmarkNode) {
        val treeNode = DefaultMutableTreeNode(node)
        parent.add(treeNode)
        childrenOf(node).forEach { addChildren(treeNode, it) }
    }

    private fun childrenOf(node: BookmarkNode): List<BookmarkNode> = when (node) {
        is BookmarkNode.RepoGroup -> node.children
        is BookmarkNode.Category -> node.children
        is BookmarkNode.Prefix -> node.children
        is BookmarkNode.WorkingCopy, is BookmarkNode.Local, is BookmarkNode.Remote, is BookmarkNode.Tag -> emptyList()
    }

    private fun installPopupHandler() {
        tree.addMouseListener(
            object : PopupHandler() {
                override fun invokePopup(comp: Component, x: Int, y: Int) {
                    val path = tree.getClosestPathForLocation(x, y) ?: return
                    tree.selectionPath = path
                    val node = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? BookmarkNode
                        ?: return
                    val group = actionGroupFor(node) ?: return
                    val popupMenu = ActionManager.getInstance().createActionPopupMenu("Jujutsu.BookmarksPanel", group)
                    popupMenu.setTargetComponent(tree)
                    popupMenu.component.show(comp, x, y)
                }
            }
        )
    }

    /**
     * A bookmark/tag name can itself contain an issue-tracker reference (e.g. a bookmark named
     * `JIRA-123-fix-thing`) — [BookmarkNodeRenderer] already linkifies it the same way the log
     * table does (via `appendBookmarkChip`/`appendTagChip`'s shared [appendLinkified]), so this
     * makes that link actually clickable: re-invoke the cell's own renderer at the click point
     * (the standard [SimpleColoredComponent.getFragmentTagAt] hit-test pattern) and open it in a
     * browser, mirroring [in.kkkev.jjidea.ui.log.LogClickTarget]'s `IssueLinkClick` handling for
     * the log table's own description/decoration links.
     */
    private fun installLinkHandler() {
        val handler = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                linkTargetAt(e.x, e.y)?.let { BrowserUtil.browse(it) }
            }

            override fun mouseMoved(e: MouseEvent) {
                tree.cursor = if (linkTargetAt(e.x, e.y) != null) {
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                } else {
                    Cursor.getDefaultCursor()
                }
            }
        }
        tree.addMouseListener(handler)
        tree.addMouseMotionListener(handler)
    }

    private fun linkTargetAt(x: Int, y: Int): URI? {
        val path = tree.getPathForLocation(x, y) ?: return null
        val bounds = tree.getPathBounds(path) ?: return null
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
        val row = tree.getRowForPath(path)
        val renderer = tree.cellRenderer.getTreeCellRendererComponent(
            tree,
            node,
            tree.isRowSelected(row),
            tree.isExpanded(path),
            treeModel.isLeaf(node),
            row,
            false
        ) as? SimpleColoredComponent ?: return null
        return renderer.getFragmentTagAt(x - bounds.x) as? URI
    }

    private fun actionGroupFor(node: BookmarkNode): ActionGroup? = when (node) {
        is BookmarkNode.Local -> {
            val allBookmarks = project.stateModel.references.value[node.repo]?.bookmarks.orEmpty().map { it.bookmark }
            BackgroundActionGroup(
                *buildList {
                    addAll(
                        localBookmarkActions(
                            node.repo,
                            node.item.bookmark,
                            includeMoveToChange = !node.onWorkingCopy,
                            remoteBookmarks = allBookmarks.remoteEntriesFor(node.item.bookmark.localName)
                        )
                    )
                    add(Separator.create())
                    add(filterLogToBookmarkAction(node.repo, node.item.bookmark.name.name))
                    add(navigateLogToBookmarkAction(node.repo, node.item.id))
                }.toTypedArray()
            )
        }

        is BookmarkNode.Remote -> BackgroundActionGroup(
            *buildList {
                addAll(remoteBookmarkActions(node.repo, node.item.bookmark))
                add(Separator.create())
                add(filterLogToBookmarkAction(node.repo, node.item.bookmark.name.name))
                add(navigateLogToBookmarkAction(node.repo, node.item.id))
            }.toTypedArray()
        )

        is BookmarkNode.Tag -> BackgroundActionGroup(
            deleteTagAction(node.repo, node.item.tag),
            Separator.create(),
            navigateLogToBookmarkAction(node.repo, node.item.id)
        )

        is BookmarkNode.WorkingCopy -> {
            val wcEntry = project.stateModel.workingCopies.value.values.firstOrNull { it.repo == node.repo }
            val closest = project.stateModel.closestBookmarks.value[node.repo]
            BackgroundActionGroup(createBookmarkAction(wcEntry), advanceClosestBookmarkAction(node.repo, closest))
        }

        is BookmarkNode.RepoGroup, is BookmarkNode.Category, is BookmarkNode.Prefix -> null
    }

    override fun dispose() = Unit

    private class BookmarkNodeRenderer(private val project: Project) : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ) {
            val node = (value as? DefaultMutableTreeNode)?.userObject as? BookmarkNode ?: return
            // A fresh IssueLinkifier per render, matching every other rendering surface
            // (JujutsuLogTable, JujutsuCommitDetailsPanel, WorkingCopyControlsPanel) - the
            // IssueNavigationConfiguration service lookup is cheap and this keeps a live-edited
            // issue-navigation pattern in Settings picked up without this panel needing its own
            // invalidation path.
            val linkifier = IssueLinkifier(IssueNavigationConfiguration.getInstance(project))
            val canvas = FragmentRecordingCanvas(linkifier = linkifier)
            when (node) {
                is BookmarkNode.WorkingCopy -> {
                    // Bookmark-coloured label (it's showing bookmark name(s)/distance, same as the
                    // toolbar widget), plus a bold "@" marker in the log's own working-copy colour -
                    // the same glyph/colour LogEntryText.appendDecorations appends after a
                    // working-copy row's bookmarks/tags.
                    canvas.colored(JujutsuColors.BOOKMARK) {
                        append(icon(JujutsuIcons::Bookmark))
                        smaller {
                            bold {
                                appendLinkified(node.displayName)
                            }
                        }
                    }
                    canvas.append(" ")
                    canvas.colored(JujutsuColors.WORKING_COPY) { bold { append(WorkingCopy.REF) } }
                }

                is BookmarkNode.RepoGroup -> {
                    icon = RepositoryIcons[node.repo]
                    canvas.append(node.displayName)
                }

                is BookmarkNode.WithRefKind -> {
                    icon = AllIcons.Nodes.Folder
                    canvas.colored(node.refKind.color) {
                        smaller {
                            append(node.displayName)
                        }
                    }
                    // A collapsed category/prefix hides its leaves' own ↑n↓m divergence chips, so
                    // roll them up onto the folder itself while collapsed (jj-idea-a7a7, GitHub
                    // #48 Finding 4) - otherwise collapsing would make unsynced/diverged bookmarks
                    // *less* visible, the opposite of the issue's second ask.
                    if (!expanded && node.rollup.isNotable) {
                        canvas.append(" ")
                        canvas.colored(JujutsuColors.DIVERGENT) {
                            smaller {
                                val divergence = buildString {
                                    if (node.rollup.aheadCount > 0) append("↑${node.rollup.aheadCount}")
                                    if (node.rollup.behindCount > 0) append("↓${node.rollup.behindCount}")
                                }
                                append(divergence.ifEmpty { "•" })
                            }
                        }
                    }
                }

                is BookmarkNode.Local -> {
                    canvas.smaller {
                        bold(node.onWorkingCopy) { appendBookmarkChip(node.item.bookmark, node.displayName) }
                    }
                }

                is BookmarkNode.Remote -> {
                    canvas.appendBookmarkChip(node.item.bookmark, node.displayName)
                }

                is BookmarkNode.Tag -> {
                    canvas.appendTagChip(node.item.tag, node.displayName)
                }
            }
            render(canvas)
        }

        /**
         * Replays [canvas]'s recorded fragments onto this renderer: a single leading icon plus a
         * run of styled text. Reuses the same jj-domain rendering vocabulary
         * ([in.kkkev.jjidea.ui.components.LogEntryText]'s `appendBookmarkChip`/`appendTagChip`) and
         * icon-recolouring ([IconResolver]) the log table itself renders bookmark/tag chips with —
         * the same adaptation [in.kkkev.jjidea.ui.components.TextListCellRenderer] does for list
         * cells — so this tree never re-derives icon precedence, colours, strikethrough, or
         * ahead/behind indicators independently.
         *
         * A text fragment's [FragmentRecordingCanvas.Fragment.linkTarget] (set by [appendLinkified]
         * when a bookmark/tag name embeds an issue-tracker reference) is passed through as this
         * fragment's tag, so [JujutsuBookmarksPanel.linkTargetAt]'s `getFragmentTagAt` hit-test can
         * find it and open it on click.
         */
        private fun render(canvas: FragmentRecordingCanvas) {
            for (fragment in canvas.fragments) {
                when (fragment) {
                    is FragmentRecordingCanvas.Fragment.Icon ->
                        icon = IconResolver.resolveIcon(fragment.icon.qualified)

                    is FragmentRecordingCanvas.Fragment.Text -> {
                        val target = fragment.linkTarget
                        if (target != null) {
                            append(fragment.text, fragment.style, target)
                        } else {
                            append(fragment.text, fragment.style)
                        }
                    }
                }
            }
        }
    }
}
