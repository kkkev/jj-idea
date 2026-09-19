package `in`.kkkev.jjidea.ui.log

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
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.preview.PreviewEntitlement
import `in`.kkkev.jjidea.preview.PreviewFeature
import `in`.kkkev.jjidea.ui.components.FragmentRecordingCanvas
import `in`.kkkev.jjidea.ui.components.TextCanvasPanel
import `in`.kkkev.jjidea.ui.components.append
import `in`.kkkev.jjidea.ui.components.appendSummary
import `in`.kkkev.jjidea.ui.dnd.DragContext
import `in`.kkkev.jjidea.ui.dnd.DragContextHolder
import `in`.kkkev.jjidea.ui.dnd.DragPayload
import `in`.kkkev.jjidea.ui.dnd.DropOperation
import `in`.kkkev.jjidea.ui.dnd.DropPerformer
import `in`.kkkev.jjidea.ui.dnd.DropPerformers
import `in`.kkkev.jjidea.ui.dnd.DropTarget
import `in`.kkkev.jjidea.ui.dnd.DropZone
import `in`.kkkev.jjidea.ui.dnd.DropZones
import `in`.kkkev.jjidea.ui.dnd.RejectOverlay
import `in`.kkkev.jjidea.ui.dnd.ZoneHysteresis
import `in`.kkkev.jjidea.ui.dnd.bookmarkTargets
import `in`.kkkev.jjidea.ui.dnd.chipDragImage
import `in`.kkkev.jjidea.ui.dnd.resolveDropOperation
import `in`.kkkev.jjidea.ui.dnd.tagTargets
import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage

/**
 * Installs drag-and-drop on the log table (jj-idea-6jvh): a commit row (or the current selection,
 * if the drag started on a selected row) becomes a [DragPayload.Commit] source, and a bookmark/tag
 * chip becomes a [DragPayload.BookmarkRef]/[DragPayload.TagRef] source (jj-idea-ibth, -vdwh), both
 * resolved through [dragPayloadAt]; every row is a y-aware drop target resolved through
 * [dropTargetAt]. The gesture vocabulary itself - payload/target types, zone geometry, guards,
 * dispatch - lives surface-agnostically under `ui/dnd/`, per that package's doc; this file is only
 * the log table's hit-tests and its `DnDSupport` wiring, mirroring
 * `JujutsuLogTableRenderers.installRenderers()`.
 *
 * Built directly on `DnDSupport.createBuilder`, not `RowsDnDSupport` - see
 * `docs/design/jj-idea-6oeg-drag-and-drop-graph-ops.md` section 5 for why the platform's own
 * row-drag helper (list-reorder `EditableModel` semantics, an unconditional `TransferHandler`)
 * doesn't fit a DAG-derived row order or this table's existing hand-rolled mouse handling
 * (`JujutsuLogTable.kt:190-357`).
 *
 * [DropPerformers.forLogTable] supplies a [DropPerformer] whose [DropPerformer.supports] gates the
 * target checker: an operation it doesn't handle (e.g. [DropOperation.Duplicate] before
 * jj-idea-p6nb lands) is rejected with no indicator, same as one [resolveDropOperation] never
 * resolves at all - so no gesture ever looks available before it actually is.
 *
 * A drop a [DragContext] guard actively rejects (cross-repo, cycle, immutable) gets its own
 * [RejectOverlay] indicator, not just the platform's native reject cursor - see
 * [RejectOverlay.show]'s doc for why the cursor (and even `DnDEvent.setHighlighting`) alone isn't
 * reliable feedback (jj-idea-ymuu).
 *
 * Guarded behind [PreviewFeature.DRAG_AND_DROP] (jj-idea-vpvz): installs nothing at all - no
 * `DnDSupport`, no drag ever initiates - unless the feature is enabled. Guarded here, at install
 * time, not per-drop, so a disabled preview feature costs nothing at drag time and can't
 * half-work; a toggle change needs the table re-created (e.g. an IDE restart) to take effect.
 */
internal fun JujutsuLogTable.installDragAndDrop(parent: Disposable) {
    if (!PreviewEntitlement.getInstance().isEnabled(PreviewFeature.DRAG_AND_DROP)) return

    val hysteresis = ZoneHysteresis()
    val dragContextHolder = DragContextHolder()
    val performer = DropPerformers.forLogTable(project)
    val rejectOverlay = RejectOverlay()
    Disposer.register(parent) { rejectOverlay.dispose() }

    // Shared by two different platform hooks, not just one: setDropEndedCallback maps to
    // DnDSource.dragDropEnd, which only fires on the component that *started* the drag - for a
    // drag that started in a different component's payload source (e.g.
    // in.kkkev.jjidea.ui.common.installFilesDragSource since jj-idea-yvry), this table is never
    // that source, so a RejectOverlay shown while hovering an invalid row would never be hidden -
    // reported as the red background surviving a failed cross-component drop. setCleanUpOnLeaveCallback
    // maps to DnDTarget.cleanUpOnLeave(), which DnDManagerImpl calls on *this table specifically*
    // whenever it was the last-processed target: when the drag leaves the table for another
    // component, and - via dragDropEnd's own explicit target.cleanUpOnLeave() call - whenever the
    // drag ends at all, regardless of which component sourced it. Wiring both keeps the overlay
    // reliably hidden either way; calling this twice on an end-of-drag over this table is harmless
    // (rejectOverlay.hide() is idempotent).
    val cleanUp = {
        hysteresis.reset()
        dragContextHolder.reset()
        rejectOverlay.hide()
    }

    DnDSupport.createBuilder(this)
        .setBeanProvider { info ->
            val payload = dragPayloadAt(info.point) ?: return@setBeanProvider null
            hysteresis.reset()
            DnDDragStartBean(payload)
        }
        .setImageProvider { info -> dragPayloadAt(info.point)?.let { dragImage(it) } }
        .setTargetChecker { event ->
            event.hideHighlighter()
            when (val resolution = resolveLive(event, hysteresis, dragContextHolder)) {
                is DropResolution.Allowed ->
                    if (performer.supports(resolution.operation)) {
                        rejectOverlay.hide()
                        highlight(event, resolution.row, resolution.zone)
                        event.setDropPossible(true, resolution.operation.label)
                    } else {
                        // Unwired operation (e.g. Duplicate before jj-idea-p6nb) - reject with no
                        // indicator, same as a guarded/undefined drop, so it never looks available.
                        rejectOverlay.hide()
                        event.setDropPossible(false, "")
                    }
                is DropResolution.Rejected -> {
                    // Only a guard's real reason (non-blank) is worth flagging - self-drop and an
                    // undefined dispatch cell both return "" deliberately and stay silent.
                    if (resolution.reason.isNotEmpty()) {
                        rejectOverlay.show(this, zoneHighlightRect(rowRect(resolution.row), resolution.zone))
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
            val resolution = resolveLive(event, hysteresis, dragContextHolder) as? DropResolution.Allowed
                ?: return@setDropHandlerWithResult false
            if (!performer.supports(resolution.operation)) return@setDropHandlerWithResult false
            performer.perform(resolution.operation)
        }
        .setDropEndedCallback { cleanUp() }
        .setCleanUpOnLeaveCallback { cleanUp() }
        .setDisposableParent(parent)
        .install()

    SmoothAutoScroller.installDropTargetAsNecessary(this)
}

/**
 * The outcome of resolving a live drop, always carrying the `(row, zone)` it landed in (so a
 * rejection can still be painted at the right spot, not just an allowed drop). [Rejected.reason]
 * is [DragContext.rejectionReason]'s message - e.g. "Cannot drop across repositories", "That would
 * create a cycle", "&lt;id&gt; is immutable" - or `""` for the deliberately-silent self-drop case
 * and an undefined [resolveDropOperation] dispatch cell.
 */
internal sealed interface DropResolution {
    data class Allowed(val row: Int, val zone: DropZone, val operation: DropOperation) : DropResolution
    data class Rejected(val row: Int, val zone: DropZone, val reason: String) : DropResolution
}

/**
 * Re-run the full hit-test -> guard -> dispatch pipeline for the drop currently under [event], or
 * `null` if there is none at all (off any row, or no payload/drag in progress). Called from both
 * the target checker (every mouse-move) and the drop handler (once, on release) so the two can
 * never disagree about what a drop would do.
 *
 * [dragContextHolder] builds its [DragContext] from [event]'s own payload rather than one primed
 * only by this table's bean provider (which never fires for a drag that started in a different
 * component's payload source, e.g. [in.kkkev.jjidea.ui.common.installFilesDragSource] since
 * jj-idea-yvry) - so a cross-component drag resolves here exactly like one that started on this
 * table, and [DragContextHolder]'s memoisation still keeps the guard-state build to once per
 * gesture rather than once per mouse-move.
 */
private fun JujutsuLogTable.resolveLive(
    event: DnDEvent,
    hysteresis: ZoneHysteresis,
    dragContextHolder: DragContextHolder
): DropResolution? {
    val payload = event.attachedObject as? DragPayload ?: return null
    val context = dragContextHolder.forPayload(payload) { logModel.getFilteredEntries() }
    val point = event.relativePoint.getPoint(this)
    val (row, target) = dropTargetAt(point, hysteresis, payload) ?: return null
    val copy = event.action == DnDAction.COPY
    return resolveDrop(payload, target, row, copy, context)
}

/**
 * The pure part of [resolveLive] - given a resolved `(row, target)` pair, decides whether the drop
 * is [DropResolution.Allowed] or [DropResolution.Rejected] with [DragContext.rejectionReason]'s
 * reason (or `""` for the deliberately-silent self-drop/undefined-cell cases). Split out from
 * [resolveLive] so it's testable without a live `DnDEvent`/`JujutsuLogTable`.
 */
internal fun resolveDrop(
    payload: DragPayload,
    target: DropTarget,
    row: Int,
    copy: Boolean,
    context: DragContext
): DropResolution {
    val zone = if (target is DropTarget.Gap) target.edge else DropZone.ONTO
    context.rejectionReason(target, copy)?.let { return DropResolution.Rejected(row, zone, it) }
    val operation = resolveDropOperation(payload, target, copy) ?: return DropResolution.Rejected(row, zone, "")
    return DropResolution.Allowed(row, zone, operation)
}

/**
 * The drag payload starting at [point] (table-relative): a bookmark/tag chip under the pointer
 * (jj-idea-ibth, -vdwh) via [JujutsuLogTable.clickTargetAt], or - falling through, same as before
 * those beads existed - the pressed row's selection as a [DragPayload.Commit]. Reusing
 * `clickTargetAt` here (rather than a second chip hit-test) is the same reasoning
 * [in.kkkev.jjidea.ui.log.JujutsuLogTable]'s hover-cue code already relies on: a mouse-driven pick
 * only ever needs one `LaidOutCell` build per event.
 */
internal fun JujutsuLogTable.dragPayloadAt(point: Point): DragPayload? {
    when (val click = clickTargetAt(point)) {
        is BookmarkClick -> return DragPayload.BookmarkRef(
            click.repo,
            click.entry.id,
            click.bookmark,
            click.repo.bookmarkTargets(click.bookmark, click.entry.id)
        )
        is TagClick -> return DragPayload.TagRef(
            click.repo,
            click.entry.id,
            click.tag,
            click.repo.tagTargets(click.tag, click.entry.id)
        )
        else -> Unit
    }
    val row = rowAtPoint(point).takeIf { it >= 0 } ?: return null
    val pressedEntry = logModel.getEntry(convertRowIndexToModel(row)) ?: return null
    val entries = if (isRowSelected(row)) selectedEntries else listOf(pressedEntry)
    if (entries.isEmpty()) return null
    return DragPayload.Commit(entries)
}

/**
 * Hit-test [point] (table-relative) to a `(row, DropTarget)` pair for a drag carrying [payload],
 * applying [hysteresis] against the row's zone geometry.
 *
 * A [DragPayload.Commit] drag checks for a bookmark **or tag** chip under [point] first (via
 * [JujutsuLogTable.clickTargetAt]) - the commit->chip cell moves that bookmark/tag (jj-idea-ibth,
 * batch 4). A [DragPayload.BookmarkRef] drag checks only for a bookmark chip - the chip->chip cell
 * is the push gesture (jj-idea-vdwh); a bookmark has no defined operation onto a tag chip
 * (`resolveDropOperation`'s `RefChip` payload / `TagChip` target cell is empty). This costs one
 * [LaidOutCell] rebuild per mouse-move, the same the row's own hover-cue lookup already pays
 * (`JujutsuLogTable.kt`'s `mouseMoved`), so it doesn't add a new order of work - see this
 * function's scale note in the batch-2 design plan. A [DragPayload.TagRef] never resolves to a
 * chip target (only [DropTarget.CommitRow] pairs with a tag, per `resolveDropOperation`), so it
 * skips the chip hit-test entirely.
 *
 * Once a payload is known to be a bookmark/tag chip and the point isn't over a *different* chip,
 * the whole row resolves to [DropTarget.CommitRow] regardless of edge-band position - a chip drag
 * has no gap-based operation, and letting the edge bands still flip [DropZone] would flicker the
 * drop indicator for no operational reason.
 *
 * `internal` (not `private`) so [JujutsuLogTableDnDTest] can exercise the zone geometry against a
 * live, laid-out table directly, without needing to drive a real `DnDEvent`.
 */
internal fun JujutsuLogTable.dropTargetAt(
    point: Point,
    hysteresis: ZoneHysteresis,
    payload: DragPayload
): Pair<Int, DropTarget>? {
    val row = rowAtPoint(point).takeIf { it >= 0 } ?: return null
    val entry = logModel.getEntry(convertRowIndexToModel(row)) ?: return null

    if (payload is DragPayload.Commit) {
        when (val click = clickTargetAt(point)) {
            is BookmarkClick -> return row to DropTarget.RefChip(click.repo, click.entry.id, click.bookmark)
            is TagClick -> return row to DropTarget.TagChip(click.repo, click.entry.id, click.tag)
            else -> Unit
        }
    }
    if (payload is DragPayload.BookmarkRef) {
        (clickTargetAt(point) as? BookmarkClick)?.let { chip ->
            return row to DropTarget.RefChip(chip.repo, chip.entry.id, chip.bookmark)
        }
    }
    if (payload is DragPayload.BookmarkRef || payload is DragPayload.TagRef) {
        return row to DropTarget.CommitRow(entry)
    }

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
 * A small "chip" image following the cursor for the duration of the drag - the same treatment
 * `ChangesTreeDnDSupport` gives a file drag in the Project view (`DnDAwareTree.getDragImage`,
 * which this mirrors, since that helper is `Tree`-only and can't be reused directly on a
 * [JujutsuLogTable]). Without this, only the OS cursor itself indicates a drag is happening -
 * reported as missing feedback compared to the Project view's file drag.
 *
 * The [DragPayload.BookmarkRef]/[DragPayload.TagRef] cases delegate to [chipDragImage], shared
 * with every other surface a bookmark/tag chip can be dragged from (batch 4); only the
 * [DragPayload.Commit] case is log-table-specific, since a commit only ever drags from a row here.
 * Built from a [FragmentRecordingCanvas] rendered through [TextCanvasPanel], the same
 * icon+styled-text vocabulary the log table's own rows and [MoveBookmarkDialog]'s list use - a
 * single commit's id gets the usual bold-unique-prefix/grey-remainder treatment
 * (`TextCanvas.append(ChangeId)`) rather than a plain unstyled short id.
 *
 * Returns `null` for a payload kind with no natural single-line label yet ([DragPayload.Files],
 * [DragPayload.WorkingCopyRef]) - the platform falls back to no image (cursor only) rather than
 * this throwing or guessing at a label.
 *
 * `internal` (not `private`) so [JujutsuLogTableDnDTest] can exercise it directly.
 */
internal fun JujutsuLogTable.dragImage(payload: DragPayload): DnDImage? {
    if (payload is DragPayload.BookmarkRef || payload is DragPayload.TagRef) {
        return chipDragImage(RenderingUtil.getForeground(this), RenderingUtil.getBackground(this), font, payload)
    }
    if (payload is DragPayload.WorkingCopyRef || payload is DragPayload.Files) return null

    val canvas = FragmentRecordingCanvas()
    canvas.foreground(RenderingUtil.getForeground(this)) {
        val entries = (payload as DragPayload.Commit).entries
        if (entries.size == 1) {
            val entry = entries.single()
            append(entry.id)
            append(" ")
            appendSummary(entry.description)
        } else {
            append("${entries.size} commits")
        }
    }

    val panel = TextCanvasPanel().apply {
        isOpaque = true
        background = RenderingUtil.getBackground(this@dragImage)
        font = this@dragImage.font
        border = JBUI.Borders.empty(2, 4)
    }
    panel.renderFrom(canvas)
    panel.size = panel.preferredSize
    // renderFrom only adds child components - nothing has positioned them within the panel's
    // bounds yet, unlike a real ListCellRenderer (whose containing JList validates it as part of
    // the platform's own rendering pass before painting).
    panel.doLayout()

    val image = UIUtil.createImage(panel, panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
    val g2 = image.graphics as Graphics2D
    g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f)
    panel.paint(g2)
    g2.dispose()

    // Places the whole label up-and-left of the cursor (cursor sits at its bottom-right corner),
    // the same offset ChangesTreeDnDSupport.createDragImage uses.
    return DnDImage(image, Point(-image.getWidth(null), -image.getHeight(null)))
}

/**
 * Paint the drop indicator for [zone] at [row]: an outline around the whole row for
 * [DropZone.ONTO], or around just that edge's own band ([zoneHighlightRect]) for insert - the
 * same light-outline style throughout (never a filled block), so the insert bands read as a
 * lightweight zone marker rather than a solid highlight competing visually with the row content.
 */
private fun JujutsuLogTable.highlight(event: DnDEvent, row: Int, zone: DropZone) {
    event.setHighlighting(
        RelativeRectangle(this, zoneHighlightRect(rowRect(row), zone)),
        DnDEvent.DropTargetHighlightingType.RECTANGLE
    )
}

/** The full-width rectangle for [row], as passed to [zoneHighlightRect] for both the allowed and rejected indicators. */
private fun JujutsuLogTable.rowRect(row: Int): Rectangle {
    val rowRect = getCellRect(row, 0, true)
    rowRect.width = width
    return rowRect
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
