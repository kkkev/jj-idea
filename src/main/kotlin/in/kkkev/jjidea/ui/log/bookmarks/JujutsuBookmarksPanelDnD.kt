package `in`.kkkev.jjidea.ui.log.bookmarks

import com.intellij.ide.dnd.DnDAction
import com.intellij.ide.dnd.DnDDragStartBean
import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDImage
import com.intellij.ide.dnd.DnDSupport
import com.intellij.ide.dnd.SmoothAutoScroller
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.awt.RelativeRectangle
import com.intellij.ui.render.RenderingUtil
import `in`.kkkev.jjidea.preview.PreviewEntitlement
import `in`.kkkev.jjidea.preview.PreviewFeature
import `in`.kkkev.jjidea.ui.dnd.DragContext
import `in`.kkkev.jjidea.ui.dnd.DragContextHolder
import `in`.kkkev.jjidea.ui.dnd.DragPayload
import `in`.kkkev.jjidea.ui.dnd.DropOperation
import `in`.kkkev.jjidea.ui.dnd.DropPerformers
import `in`.kkkev.jjidea.ui.dnd.DropTarget
import `in`.kkkev.jjidea.ui.dnd.RejectOverlay
import `in`.kkkev.jjidea.ui.dnd.chipDragImage
import `in`.kkkev.jjidea.ui.dnd.resolveDropOperation
import java.awt.Point
import java.awt.Rectangle
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Installs drag-and-drop on the bookmarks panel's tree (jj-idea-0rdm, batch 4): a bookmark/tag
 * leaf becomes a [DragPayload.BookmarkRef]/[DragPayload.TagRef] source, and every such leaf is
 * also a [DropTarget.RefChip]/[DropTarget.TagChip] target - so a commit dragged from the log table
 * onto a panel node moves that bookmark/tag, and a bookmark/tag dragged from the panel onto a log
 * row does the reverse. Mirrors [in.kkkev.jjidea.ui.log.installDragAndDrop]'s shape (bean
 * provider / target checker / drop handler / cleanup), but over a [javax.swing.JTree] instead of a
 * table, and with no gap/zone geometry - a tree node has no "insert before/after" concept, so
 * every hit resolves to the whole node ([DropTarget.RefChip]/[DropTarget.TagChip] are always
 * "onto", never a [DropTarget.Gap]).
 *
 * Payload/target identity is carried directly as `repo` + `ChangeId` ([DragPayload.BookmarkRef],
 * [DropTarget.RefChip], etc.), not a hydrated [in.kkkev.jjidea.jj.LogEntry] - a panel node's change
 * may fall outside the currently-loaded log window (the case jj-idea-3xab exists for), and neither
 * a drag payload nor a drop target has ever needed more than identity (see those types' own docs).
 * A node whose [in.kkkev.jjidea.jj.RefItem.id] is null - a deleted or pending-delete bookmark
 * (`CliLogService.bookmarkListTemplate`) - has no target commit and neither drags nor accepts
 * drops.
 *
 * Guarded behind [PreviewFeature.DRAG_AND_DROP] (jj-idea-vpvz), exactly like the other two install
 * sites - no `DnDSupport` is registered at all unless the feature is enabled.
 */
internal fun JujutsuBookmarksPanel.installDragAndDrop(parent: Disposable) {
    if (!PreviewEntitlement.getInstance().isEnabled(PreviewFeature.DRAG_AND_DROP)) return

    val dragContextHolder = DragContextHolder()
    val performer = DropPerformers.forLogTable(project)
    val rejectOverlay = RejectOverlay()
    Disposer.register(parent) { rejectOverlay.dispose() }

    val cleanUp = {
        dragContextHolder.reset()
        rejectOverlay.hide()
    }

    DnDSupport.createBuilder(tree)
        .setBeanProvider { info ->
            val payload = dragPayloadAt(info.point) ?: return@setBeanProvider null
            DnDDragStartBean(payload)
        }
        .setImageProvider { info -> dragPayloadAt(info.point)?.let { dragImage(it) } }
        .setTargetChecker { event ->
            event.hideHighlighter()
            when (val resolution = resolveLive(event, dragContextHolder)) {
                is PanelDropResolution.Allowed ->
                    if (performer.supports(resolution.operation)) {
                        rejectOverlay.hide()
                        highlight(event, resolution.bounds)
                        event.setDropPossible(true, resolution.operation.label)
                    } else {
                        rejectOverlay.hide()
                        event.setDropPossible(false, "")
                    }
                is PanelDropResolution.Rejected -> {
                    if (resolution.reason.isNotEmpty()) {
                        rejectOverlay.show(tree, resolution.bounds)
                    } else {
                        rejectOverlay.hide()
                    }
                    event.setDropPossible(false, resolution.reason)
                }
                null -> {
                    rejectOverlay.hide()
                    event.setDropPossible(false, "")
                }
            }
            false
        }
        .setDropHandlerWithResult { event ->
            val resolution = resolveLive(event, dragContextHolder) as? PanelDropResolution.Allowed
                ?: return@setDropHandlerWithResult false
            if (!performer.supports(resolution.operation)) return@setDropHandlerWithResult false
            performer.perform(resolution.operation)
        }
        .setDropEndedCallback { cleanUp() }
        .setCleanUpOnLeaveCallback { cleanUp() }
        .setDisposableParent(parent)
        .install()

    SmoothAutoScroller.installDropTargetAsNecessary(tree)
}

/**
 * The outcome of resolving a live drop over the bookmarks panel, always carrying the node
 * [bounds] it landed on (so a rejection or an allowed-drop outline can still be painted at the
 * right spot). Kept local to this file rather than sharing
 * [in.kkkev.jjidea.ui.log.JujutsuLogTableDnD]'s `DropResolution` - that type's `row`/`zone` fields
 * are table-row concepts this tree-based surface has no equivalent for.
 */
private sealed interface PanelDropResolution {
    data class Allowed(val bounds: Rectangle, val operation: DropOperation) : PanelDropResolution
    data class Rejected(val bounds: Rectangle, val reason: String) : PanelDropResolution
}

/**
 * Re-run the full hit-test -> guard -> dispatch pipeline for the drop currently under [event], or
 * `null` if there is none at all (off any node, or no payload/drag in progress). Called from both
 * the target checker (every mouse-move) and the drop handler (once, on release), mirroring
 * [in.kkkev.jjidea.ui.log.JujutsuLogTableDnD]'s `resolveLive`.
 *
 * [dragContextHolder] builds its [DragContext] from [event]'s own payload rather than one primed
 * only by this panel's bean provider - so a commit dragged from the log table onto this panel
 * resolves here exactly like a drag that started on the panel itself, and [allEntries] supplies
 * the same entry set the log table's own guard state would be built from.
 */
private fun JujutsuBookmarksPanel.resolveLive(
    event: DnDEvent,
    dragContextHolder: DragContextHolder
): PanelDropResolution? {
    val payload = event.attachedObject as? DragPayload ?: return null
    val point = event.relativePoint.getPoint(tree)
    val path = tree.getPathForLocation(point.x, point.y) ?: return null
    val bounds = tree.getPathBounds(path) ?: return null
    val target = targetAt(path) ?: return null
    val context = dragContextHolder.forPayload(payload, allEntries)
    val copy = event.action == DnDAction.COPY

    context.rejectionReason(target, copy)?.let { return PanelDropResolution.Rejected(bounds, it) }
    val operation = resolveDropOperation(payload, target, copy) ?: return PanelDropResolution.Rejected(bounds, "")
    return PanelDropResolution.Allowed(bounds, operation)
}

/**
 * The drag payload starting at [point] (tree-relative): a bookmark or tag leaf under the pointer,
 * identified directly by `repo` + `ChangeId` since every [BookmarkNode] leaf already carries its
 * own [in.kkkev.jjidea.jj.JujutsuRepository] - no lookup needed, unlike the log table's chip
 * hit-test which resolves a click point to a laid-out cell first. `null` for every other node kind
 * (categories/prefixes/working-copy/repo groups have no bookmark identity to drag) and for a leaf
 * whose [in.kkkev.jjidea.jj.RefItem.id] is null (a deleted/pending-delete bookmark).
 *
 * `internal` (not `private`) so a platform test can exercise it directly.
 */
internal fun JujutsuBookmarksPanel.dragPayloadAt(point: Point): DragPayload? {
    val path = tree.getPathForLocation(point.x, point.y) ?: return null
    return when (val node = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? BookmarkNode) {
        is BookmarkNode.Local ->
            node.item.id?.let { DragPayload.BookmarkRef(node.repo, it, node.item.bookmark, node.item.targets.toSet()) }
        is BookmarkNode.Remote ->
            node.item.id?.let { DragPayload.BookmarkRef(node.repo, it, node.item.bookmark, node.item.targets.toSet()) }
        is BookmarkNode.Tag ->
            node.item.id?.let { DragPayload.TagRef(node.repo, it, node.item.tag, node.item.targets.toSet()) }
        else -> null
    }
}

/** As [dragPayloadAt], but resolving [path] to a [DropTarget] instead of a [DragPayload]. */
private fun targetAt(path: javax.swing.tree.TreePath): DropTarget? =
    when (val node = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? BookmarkNode) {
        is BookmarkNode.Local -> node.item.id?.let { DropTarget.RefChip(node.repo, it, node.item.bookmark) }
        is BookmarkNode.Remote -> node.item.id?.let { DropTarget.RefChip(node.repo, it, node.item.bookmark) }
        is BookmarkNode.Tag -> node.item.id?.let { DropTarget.TagChip(node.repo, it, node.item.tag) }
        else -> null
    }

/**
 * A tree-relative [DropTarget] hit-test at [point], for a platform test to exercise without
 * driving a real `DnDEvent` - mirrors
 * [in.kkkev.jjidea.ui.log.JujutsuLogTableDnD.dropTargetAt]'s test seam.
 */
internal fun JujutsuBookmarksPanel.dropTargetAt(point: Point): DropTarget? {
    val path = tree.getPathForLocation(point.x, point.y) ?: return null
    return targetAt(path)
}

/** Outline the whole node at [bounds] for an allowed drop - a tree node has no zones to distinguish. */
private fun JujutsuBookmarksPanel.highlight(event: DnDEvent, bounds: Rectangle) {
    event.setHighlighting(RelativeRectangle(tree, bounds), DnDEvent.DropTargetHighlightingType.RECTANGLE)
}

/**
 * A small "chip" image following the cursor for the duration of the drag - delegates to
 * [chipDragImage], shared with every other surface a bookmark/tag chip can be dragged from
 * (batch 4: the log table, this panel, and the commit details panel).
 */
internal fun JujutsuBookmarksPanel.dragImage(payload: DragPayload): DnDImage? =
    chipDragImage(RenderingUtil.getForeground(tree), RenderingUtil.getBackground(tree), tree.font, payload)
