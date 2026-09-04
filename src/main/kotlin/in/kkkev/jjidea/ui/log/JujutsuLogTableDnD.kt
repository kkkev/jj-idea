package `in`.kkkev.jjidea.ui.log

import com.intellij.ide.dnd.DnDAction
import com.intellij.ide.dnd.DnDDragStartBean
import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDSupport
import com.intellij.ide.dnd.SmoothAutoScroller
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColorUtil
import com.intellij.ui.awt.RelativeRectangle
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.preview.PreviewEntitlement
import `in`.kkkev.jjidea.preview.PreviewFeature
import `in`.kkkev.jjidea.ui.dnd.DragContext
import `in`.kkkev.jjidea.ui.dnd.DragPayload
import `in`.kkkev.jjidea.ui.dnd.DropOperation
import `in`.kkkev.jjidea.ui.dnd.DropPerformer
import `in`.kkkev.jjidea.ui.dnd.DropPerformers
import `in`.kkkev.jjidea.ui.dnd.DropTarget
import `in`.kkkev.jjidea.ui.dnd.DropZone
import `in`.kkkev.jjidea.ui.dnd.DropZones
import `in`.kkkev.jjidea.ui.dnd.ZoneHysteresis
import `in`.kkkev.jjidea.ui.dnd.resolveDropOperation
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities

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
    var dragContext: DragContext? = null
    val performer = DropPerformers.forLogTable(project)
    val rejectOverlay = RejectOverlay()
    Disposer.register(parent) { rejectOverlay.dispose() }

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
            when (val resolution = resolveLive(event, hysteresis, dragContext)) {
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
            val resolution = resolveLive(event, hysteresis, dragContext) as? DropResolution.Allowed
                ?: return@setDropHandlerWithResult false
            if (!performer.supports(resolution.operation)) return@setDropHandlerWithResult false
            performer.perform(resolution.operation)
        }
        .setDropEndedCallback {
            hysteresis.reset()
            dragContext = null
            rejectOverlay.hide()
        }
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
 */
private fun JujutsuLogTable.resolveLive(
    event: DnDEvent,
    hysteresis: ZoneHysteresis,
    dragContext: DragContext?
): DropResolution? {
    val payload = event.attachedObject as? DragPayload ?: return null
    val context = dragContext ?: return null
    val point = event.relativePoint.getPoint(this)
    val (row, target) = dropTargetAt(point, hysteresis) ?: return null
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
 * A "blocked" indicator painted entirely outside `DnDEvent`'s own highlighter mechanism - see
 * [show]'s doc for why. Lives for the duration of one `installDragAndDrop` call; [hide] is
 * idempotent and cheap to call on every mouse-move tick where nothing should be shown.
 */
private class RejectOverlay {
    private var panel: JComponent? = null

    /**
     * Paint a filled, error-colored rectangle at [rect] (table-relative) on top of [table] - a
     * real guard rejection (cross-repo, cycle, immutable) needs its own reliable indicator,
     * because the platform's native reject cursor turned out not to be one on its own
     * (jj-idea-ymuu): in-app drag-over ticks are OS-coalesced, so a fast or even a deliberately
     * slow drag across a rejected row could show nothing at all.
     *
     * This can't reuse [DnDEvent.setHighlighting] (as [highlight] does for allowed drops) the way
     * a first attempt at this fix did: `DnDManagerImpl.updateCurrentEvent` unconditionally calls
     * `hideCurrentHighlighter()` on every tick where the point differs and `isDropPossible()` is
     * false (no `Highlighters.isVisibleExcept` guard, unlike the `isDropPossible() == true`
     * branch) - the only thing re-queued afterward is a delayed, registry-gated
     * (`ide.dnd.textHints`) `ERROR_TEXT` balloon, never the `RECTANGLE`/`FILLED_RECTANGLE` we just
     * painted. So any highlighter set while `dropPossible` is false gets wiped by the platform
     * itself on the very next tick, which is exactly the "flashes once, then never again" seen in
     * manual testing. Painting our own component directly into the same layered pane
     * (`Highlighters`' own components use) sidesteps that bookkeeping entirely.
     */
    fun show(table: JujutsuLogTable, rect: Rectangle) {
        val layeredPane = SwingUtilities.getRootPane(table)?.layeredPane ?: return
        val current = panel ?: RejectPanel().also {
            panel = it
            layeredPane.add(it, JLayeredPane.DRAG_LAYER)
        }
        if (current.parent !== layeredPane) {
            current.parent?.remove(current)
            layeredPane.add(current, JLayeredPane.DRAG_LAYER)
        }
        val topLeft = SwingUtilities.convertPoint(table, rect.location, layeredPane)
        current.setBounds(topLeft.x, topLeft.y, rect.width, rect.height)
        current.isVisible = true
    }

    fun hide() {
        panel?.isVisible = false
    }

    /** Remove the overlay component from its layered pane for good - called when the table is disposed. */
    fun dispose() {
        panel?.let { it.parent?.remove(it) }
        panel = null
    }

    private class RejectPanel : JComponent() {
        init {
            isOpaque = false
        }

        // Translucent fill (not the opaque errorBackgroundColor() itself) - a full-strength fill
        // completely hid the row's own text underneath it, which defeats the point of an
        // indicator that's supposed to name what's being rejected (jj-idea-ymuu follow-up).
        override fun paintComponent(g: Graphics) {
            g.color = ColorUtil.withAlpha(JBUI.CurrentTheme.Validator.errorBackgroundColor(), 0.55)
            g.fillRect(0, 0, width, height)
            g.color = JBUI.CurrentTheme.Validator.errorBorderColor()
            g.drawRect(0, 0, width - 1, height - 1)
        }
    }
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
