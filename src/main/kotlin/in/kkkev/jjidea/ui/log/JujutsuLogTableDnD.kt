package `in`.kkkev.jjidea.ui.log

import com.intellij.ide.dnd.DnDAction
import com.intellij.ide.dnd.DnDDragStartBean
import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDSupport
import com.intellij.ide.dnd.SmoothAutoScroller
import com.intellij.openapi.Disposable
import com.intellij.ui.awt.RelativeRectangle
import `in`.kkkev.jjidea.ui.dnd.DragContext
import `in`.kkkev.jjidea.ui.dnd.DragPayload
import `in`.kkkev.jjidea.ui.dnd.DropOperation
import `in`.kkkev.jjidea.ui.dnd.DropPerformers
import `in`.kkkev.jjidea.ui.dnd.DropTarget
import `in`.kkkev.jjidea.ui.dnd.DropZone
import `in`.kkkev.jjidea.ui.dnd.DropZones
import `in`.kkkev.jjidea.ui.dnd.ZoneHysteresis
import `in`.kkkev.jjidea.ui.dnd.resolveDropOperation
import java.awt.Point
import java.awt.Rectangle

/**
 * Installs drag-and-drop on the log table (jj-idea-6jvh): a commit row (or the current selection,
 * if the drag started on a selected row) becomes a [DragPayload.Commit] source; every row is a
 * y-aware drop target resolved through [dropTargetAt]. The gesture vocabulary itself -
 * payload/target types, zone geometry, guards, dispatch - lives surface-agnostically under
 * `ui/dnd/`, per that package's doc; this file is only the log table's hit-tests and its
 * `DnDSupport` wiring, mirroring `JujutsuLogTableRenderers.installRenderers()`.
 *
 * Built directly on `DnDSupport.createBuilder`, not `RowsDnDSupport` - see
 * `docs/design/jj-idea-6oeg-drag-and-drop-graph-ops.md` section 5 for why the platform's own
 * row-drag helper (list-reorder `EditableModel` semantics, an unconditional `TransferHandler`)
 * doesn't fit a DAG-derived row order or this table's existing hand-rolled mouse handling
 * (`JujutsuLogTable.kt:190-357`).
 *
 * [DropPerformers.forLogTable] is `null` until a gesture bead (jj-idea-8fxs, ...) supplies one;
 * with no performer, every drop is rejected with an empty reason and no indicator ever paints -
 * dragging is initiable (so the reject cursor and [SmoothAutoScroller] wiring are exercisable) but
 * nothing is ever droppable, so no gesture looks available before it actually is.
 */
internal fun JujutsuLogTable.installDragAndDrop(parent: Disposable) {
    val hysteresis = ZoneHysteresis()
    var dragContext: DragContext? = null
    val performer = DropPerformers.forLogTable(project)

    DnDSupport.createBuilder(this)
        .setBeanProvider { info ->
            val row = rowAtPoint(info.point).takeIf { it >= 0 } ?: return@setBeanProvider null
            val pressedEntry = logModel.getEntry(convertRowIndexToModel(row)) ?: return@setBeanProvider null
            val entries = if (isRowSelected(row)) selectedEntries else listOf(pressedEntry)
            if (entries.isEmpty()) return@setBeanProvider null
            val payload = DragPayload.Commit(entries)
            hysteresis.reset()
            dragContext = DragContext.forDrag(logModel.getFilteredEntries(), payload)
            DnDDragStartBean(payload)
        }
        .setTargetChecker { event ->
            event.hideHighlighter()
            val resolved = performer?.let { resolveLive(event, hysteresis, dragContext) }
            if (resolved == null) {
                event.setDropPossible(false, "")
            } else {
                highlight(event, resolved.row, resolved.zone)
                event.setDropPossible(true, resolved.operation.label)
            }
            false
        }
        .setDropHandlerWithResult { event ->
            val performerFn = performer ?: return@setDropHandlerWithResult false
            val resolved = resolveLive(event, hysteresis, dragContext) ?: return@setDropHandlerWithResult false
            performerFn(resolved.operation)
        }
        .setDropEndedCallback {
            hysteresis.reset()
            dragContext = null
        }
        .setDisposableParent(parent)
        .install()

    SmoothAutoScroller.installDropTargetAsNecessary(this)
}

/** A fully-resolved, allowed drop: which row/zone it landed in (for painting) and what it would do. */
private class ResolvedDrop(val row: Int, val zone: DropZone, val operation: DropOperation)

/**
 * Re-run the full hit-test -> guard -> dispatch pipeline for the drop currently under [event], or
 * `null` if there is none (off any row, no payload, or the drop is disallowed/undefined for this
 * payload/target pair). Called from both the target checker (every mouse-move) and the drop
 * handler (once, on release) so the two can never disagree about what a drop would do.
 */
private fun JujutsuLogTable.resolveLive(
    event: DnDEvent,
    hysteresis: ZoneHysteresis,
    dragContext: DragContext?
): ResolvedDrop? {
    val payload = event.attachedObject as? DragPayload ?: return null
    val context = dragContext ?: return null
    val point = event.relativePoint.getPoint(this)
    val (row, target) = dropTargetAt(point, hysteresis) ?: return null
    val copy = event.action == DnDAction.COPY
    if (context.rejectionReason(target, copy) != null) return null
    val operation = resolveDropOperation(payload, target, copy) ?: return null
    val zone = if (target is DropTarget.Gap) target.edge else DropZone.ONTO
    return ResolvedDrop(row, zone, operation)
}

/**
 * Hit-test [point] (table-relative) to a `(row, DropTarget)` pair, applying [hysteresis] against
 * the row's zone geometry. Deliberately does not go through [JujutsuLogTable.clickTargetAt] - that
 * rebuilds a [LaidOutCell] on every call, which chip-payload hit-testing (jj-idea-ibth, -vdwh) can
 * afford to pay only once a chip drag is actually in flight; a plain commit-row drag never needs
 * it.
 *
 * `internal` (not `private`) so [JujutsuLogTableDnDTest] can exercise the zone geometry against a
 * live, laid-out table directly, without needing to drive a real `DnDEvent`.
 */
internal fun JujutsuLogTable.dropTargetAt(point: Point, hysteresis: ZoneHysteresis): Pair<Int, DropTarget>? {
    val row = rowAtPoint(point).takeIf { it >= 0 } ?: return null
    val entry = logModel.getEntry(convertRowIndexToModel(row)) ?: return null
    val rowRect = getCellRect(row, 0, true)
    val dy = point.y - rowRect.y
    val band = DropZones.bandFor(rowHeight)
    val zone = hysteresis.update(row, dy, rowHeight, band)
    val target = when (zone) {
        DropZone.ONTO -> DropTarget.CommitRow(entry)
        DropZone.INSERT_BEFORE, DropZone.INSERT_AFTER -> DropTarget.Gap(entry, zone)
    }
    return row to target
}

/**
 * Paint the drop indicator for [zone] at [row]: an outline around the whole row for
 * [DropZone.ONTO], or around just that edge's own band ([zoneHighlightRect]) for insert - the
 * same light-outline style throughout (never a filled block), so the insert bands read as a
 * lightweight zone marker rather than a solid highlight competing visually with the row content.
 */
private fun JujutsuLogTable.highlight(event: DnDEvent, row: Int, zone: DropZone) {
    val rowRect = getCellRect(row, 0, true)
    rowRect.width = width
    event.setHighlighting(
        RelativeRectangle(this, zoneHighlightRect(rowRect, zone)),
        DnDEvent.DropTargetHighlightingType.RECTANGLE
    )
}

/**
 * The rectangle to highlight for [zone] within [rowRect]: the whole row for [DropZone.ONTO], or
 * just that edge's own band ([DropZones.bandFor]) for insert.
 *
 * Deliberately **not** `RowsDnDSupport`'s thin line at the shared row boundary - manual testing
 * found that indistinguishable from the same line meaning "after the row above" vs "before the
 * row below" at a glance, which is exactly the ambiguity design section 2 warns matters here
 * (unlike `RowsDnDSupport`'s own list-reorder use, where the two readings are always the same
 * operation, ours are two different rebase destinations at a fork or merge). Outlining the actual
 * zone band - a slice of the row itself, not a line floating between two rows - makes which row's
 * edge is targeted visible from the geometry alone, with no need to read the tooltip.
 */
internal fun zoneHighlightRect(rowRect: Rectangle, zone: DropZone): Rectangle = when (zone) {
    DropZone.ONTO -> rowRect
    DropZone.INSERT_BEFORE -> Rectangle(rowRect.x, rowRect.y, rowRect.width, DropZones.bandFor(rowRect.height))
    DropZone.INSERT_AFTER -> {
        val band = DropZones.bandFor(rowRect.height)
        Rectangle(rowRect.x, rowRect.y + rowRect.height - band, rowRect.width, band)
    }
}
