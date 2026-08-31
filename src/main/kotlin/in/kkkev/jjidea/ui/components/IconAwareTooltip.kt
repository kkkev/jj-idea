package `in`.kkkev.jjidea.ui.components

import com.intellij.ide.IdeTooltip
import com.intellij.ide.IdeTooltipManager
import com.intellij.ide.TooltipEvent
import com.intellij.openapi.project.Project
import com.intellij.ui.ScreenUtil
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.Point
import java.awt.Window
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.JViewport
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.ChangeListener

/**
 * Bounds [pane] to [maxWidth] so its HTML reflows (e.g. bookmark chips wrap across lines)
 * instead of laying out as one oversized line. If the resulting preferred height still
 * exceeds [maxHeight], wraps it in a vertically scrollable pane instead of letting the
 * tooltip get clipped by the screen (jj-idea-szn8).
 */
internal fun tooltipComponent(pane: JComponent, maxWidth: Int, maxHeight: Int): JComponent {
    pane.setSize(maxWidth, Int.MAX_VALUE)
    val pref = pane.preferredSize
    val boundedWidth = minOf(pref.width, maxWidth)
    pane.preferredSize = Dimension(boundedWidth, pref.height)

    if (pref.height <= maxHeight) return pane

    return JBScrollPane(pane).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        preferredSize = Dimension(boundedWidth, maxHeight)
    }
}

/**
 * Builds the tip component for [html]: an [IconAwareHtmlPane] (so chip `<img src='chip:…'>`
 * elements resolve instead of painting as broken images) bounded/scroll-wrapped to fit on
 * [owner]'s screen.
 */
internal fun iconAwareTooltipComponent(project: Project, html: String, owner: JComponent): JComponent {
    val pane = IconAwareHtmlPane(project)
    pane.foreground = UIUtil.getToolTipForeground()
    pane.text = html

    val screen = ScreenUtil.getScreenRectangle(owner)
    val maxWidth = minOf(JBUI.scale(500), screen.width - JBUI.scale(40))
    val maxHeight = screen.height - JBUI.scale(40)

    return tooltipComponent(pane, maxWidth, maxHeight)
}

/**
 * Seam over the three [IdeTooltipManager] entry points [installIconAwareTooltip] needs, so tests
 * can exercise its dismissal policy without a running IDE. [PlatformTooltipHost] is the real
 * implementation; tests supply a fake.
 */
internal interface TooltipHost {
    fun install(owner: JComponent, tooltip: IdeTooltip)

    /** Mirrors [IdeTooltipManager.hideCurrent]: returns whether the hide was accepted. */
    fun hideOnMouseMove(e: MouseEvent): Boolean

    /** Force-hides unconditionally, but only if [owner] actually owns the current tooltip. */
    fun hideNow(owner: JComponent)

    /**
     * Shows [tooltip] synchronously - see [PlatformTooltipHost.showNow] for why this, not the
     * mouse-driven path, is what actually shows tooltips inside a modal dialog.
     */
    fun showNow(tooltip: IdeTooltip)
}

private object PlatformTooltipHost : TooltipHost {
    override fun install(owner: JComponent, tooltip: IdeTooltip) {
        IdeTooltipManager.getInstance().setCustomTooltip(owner, tooltip)
    }

    override fun hideOnMouseMove(e: MouseEvent): Boolean = IdeTooltipManager.getInstance().hideCurrent(e)

    override fun hideNow(owner: JComponent) {
        if (IdeTooltipManager.getInstance().isProcessing(owner)) {
            IdeTooltipManager.getInstance().hideCurrentNow(false)
        }
    }

    /**
     * [IdeTooltipManager]'s own mouse-driven show path always queues the show through a coroutine
     * that dispatches under `ModalityState.nonModal()`, which never runs while a modal dialog is
     * open - so inside a modal `DialogWrapper` (Rebase/Squash Into…/Duplicate Onto…/New Change),
     * `beforeShow()` is never even called, no matter how long the pointer holds still (jj-idea-2md7).
     * `show(tooltip, now = true)` calls `doShowNow()` directly instead, with no coroutine or
     * modality involved - safe here since [Timer]'s callback already runs on the EDT.
     */
    override fun showNow(tooltip: IdeTooltip) {
        IdeTooltipManager.getInstance().show(tooltip, true)
    }
}

/**
 * Whether [IdeTooltipManager] should be allowed to auto-hide the tooltip for [event]. Extracted
 * as a top-level function (rather than left inline in the `canAutohideOn` override) so tests can
 * exercise it directly - `canAutohideOn` itself is `protected` on [IdeTooltip], unreachable from
 * outside its class hierarchy.
 *
 * Mirrors [com.intellij.ui.TooltipWithClickableLinks]: spare the tooltip while the pointer is
 * inside the balloon (or judged by [IdeTooltipManager] to be moving towards it), so
 * hyperlinks/selectable text inside it are reachable instead of the tooltip dismissing itself the
 * moment the pointer heads that way.
 */
internal fun tooltipShouldAutohide(event: TooltipEvent): Boolean = !event.isIsEventInsideBalloon

/**
 * Test seam: exposes [installIconAwareTooltip]'s "suppressed until the next mouse move" state
 * (set after the owner's viewport scrolls) without needing access to [IdeTooltip]'s protected
 * `beforeShow()`.
 */
internal interface SuppressibleTooltip {
    val isSuppressedUntilMouseMove: Boolean
}

/**
 * Test seam: exposes [installIconAwareTooltip]'s last-known-good mouse point (the fallback used
 * when [java.awt.Component.getMousePosition] is unavailable - jj-idea-2md7 follow-up) without
 * needing to invoke [IdeTooltip]'s protected `beforeShow()`, which requires a running platform
 * [com.intellij.openapi.application.Application] to build the real tip component.
 */
internal interface MouseTrackingTooltip {
    val lastMousePoint: Point?
}

/**
 * Renders [owner]'s tooltips through [IconAwareHtmlPane] so chip `<img src='chip:…'>` elements
 * (bookmark/tag chips, author/date "unbreakable" runs) resolve instead of painting as broken
 * images — a plain Swing tooltip pane doesn't know the `chip:` URL scheme (jj-idea-fmrj).
 *
 * [cellKeyAt] identifies the hovered cell/row for a given point; when it changes, the current
 * tooltip is asked (not forced) to hide, via [TooltipHost.hideOnMouseMove], so [IdeTooltipManager]
 * re-invokes `beforeShow()` with fresh content for the new cell - unless the pointer is heading
 * towards the tooltip itself (see below), in which case the ask is declined and retried on the
 * next move. [htmlAt] supplies the tooltip HTML for a point, or null/blank to show no tooltip
 * there.
 *
 * Two usability fixes for jj-idea-wp12 (GitHub #51 point 3):
 * - The tooltip is a "hint" (see [IdeTooltip.setHint]) with `canAutohideOn` overridden to spare it
 *   while the pointer is inside the balloon, or [IdeTooltipManager] judges it to be moving towards
 *   it - the same recipe [com.intellij.ui.TooltipWithClickableLinks] uses. Without this, moving
 *   the pointer towards the tooltip's hyperlinks or selectable text dismisses it first.
 * - The tooltip is force-hidden (and suppressed until the next mouse move) when the owner's
 *   enclosing [JViewport] scrolls, so it stops blocking the view and going stale while scrolling
 *   the log.
 */
internal fun installIconAwareTooltip(
    owner: JComponent,
    project: Project,
    cellKeyAt: (Point) -> Any?,
    htmlAt: (Point) -> String?,
    host: TooltipHost = PlatformTooltipHost
): IdeTooltip {
    var suppressedUntilMouseMove = false

    // [Component.getMousePosition] can spuriously return null inside a JDialog on macOS even while
    // genuinely hovering (jj-idea-2md7); this is the fallback for when that happens.
    var trackedMousePoint: Point? = null

    val tooltip = object : IdeTooltip(owner, Point(0, 0), null), SuppressibleTooltip, MouseTrackingTooltip {
        override val isSuppressedUntilMouseMove: Boolean get() = suppressedUntilMouseMove
        override val lastMousePoint: Point? get() = trackedMousePoint

        override fun beforeShow(): Boolean {
            if (suppressedUntilMouseMove) return false

            val mousePos = owner.mousePosition ?: trackedMousePoint ?: return false
            val html = htmlAt(mousePos)
            if (html.isNullOrBlank()) return false

            point = mousePos
            tipComponent = Wrapper(iconAwareTooltipComponent(project, html, owner))
            return true
        }

        override fun canBeDismissedOnTimeout() = false

        override fun canAutohideOn(event: TooltipEvent) = tooltipShouldAutohide(event)
    }
    tooltip.setHint(true)

    // Drives the show ourselves - see PlatformTooltipHost.showNow for why IdeTooltipManager's own
    // mouse-driven path can't. One-shot: restarted on each new cell, left alone while the pointer
    // jitters within the same cell, and stopped on exit/scroll/window-deactivation below.
    val hoverTimer = Timer(tooltip.showDelay) { host.showNow(tooltip) }.apply { isRepeats = false }

    var lastCellKey: Any? = null
    owner.addMouseMotionListener(
        object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                suppressedUntilMouseMove = false
                trackedMousePoint = e.point
                val newCellKey = cellKeyAt(e.point)
                if (newCellKey != lastCellKey) {
                    hoverTimer.restart()
                    if (host.hideOnMouseMove(e)) {
                        lastCellKey = newCellKey
                    }
                }
            }
        }
    )
    // Drop the fallback point on exit, so it can't paper over the pointer having genuinely left.
    owner.addMouseListener(
        object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                trackedMousePoint = null
                hoverTimer.stop()
            }
        }
    )

    installHideOnScroll(owner, host) {
        suppressedUntilMouseMove = true
        hoverTimer.stop()
    }
    installHideOnWindowDeactivation(owner, host) { hoverTimer.stop() }

    // The identity check (currentTooltip === tooltip) in IdeTooltipManager keeps the tooltip
    // stable during mouse movement within the same cell.
    host.install(owner, tooltip)
    return tooltip
}

private const val ICON_AWARE_TOOLTIP_KEY = "jjidea.iconAwareTooltip"

/** The [IdeTooltip] [installIconAwareTableTooltip] installed on this component, if any - a test
 * seam so platform tests can assert the tooltip wiring is present without touching
 * [IdeTooltipManager]. */
internal fun JComponent.iconAwareTooltip(): IdeTooltip? = getClientProperty(ICON_AWARE_TOOLTIP_KEY) as? IdeTooltip

/**
 * The HTML [table]'s cell renderer produced (via its Swing `toolTipText`) for the cell at [point],
 * or null if there's no cell there. This is the renderer-level tooltip content that a plain Swing
 * `JTable` would show verbatim (and, for HTML containing `icon:`/`unbreakable:` `<img>` markup,
 * render as a broken image - jj-idea-2md7) - [installIconAwareTableTooltip] re-renders it through
 * [IconAwareHtmlPane] instead.
 */
internal fun tableCellTooltipHtml(table: JTable, point: Point): String? {
    val row = table.rowAtPoint(point)
    val col = table.columnAtPoint(point)
    if (row < 0 || col < 0) return null
    return (table.prepareRenderer(table.getCellRenderer(row, col), row, col) as? JComponent)?.toolTipText
}

/**
 * [installIconAwareTooltip] for a [JTable] whose cell renderers set an HTML `toolTipText`
 * (typically [in.kkkev.jjidea.ui.log.JujutsuGraphAndDescriptionRenderer]) that may contain chip
 * `<img>` markup - so bookmark/tag chips and status icons in row tooltips render correctly instead
 * of as broken images (jj-idea-2md7), matching the log table and revision picker fixes for the
 * same root cause (jj-idea-fmrj).
 *
 * [isEnabled] is consulted on every show, not just at install time, so a live settings toggle (the
 * log table's `showLogHoverTooltip`, jj-idea-tknb) applies without needing to reinstall.
 */
internal fun installIconAwareTableTooltip(
    table: JTable,
    project: Project,
    isEnabled: () -> Boolean = { true },
    host: TooltipHost = PlatformTooltipHost
): IdeTooltip {
    val tooltip = installIconAwareTooltip(
        owner = table,
        project = project,
        cellKeyAt = { table.rowAtPoint(it) to table.columnAtPoint(it) },
        htmlAt = { point -> if (isEnabled()) tableCellTooltipHtml(table, point) else null },
        host = host
    )
    table.putClientProperty(ICON_AWARE_TOOLTIP_KEY, tooltip)
    return tooltip
}

/**
 * Hides [owner]'s current tooltip and invokes [onHidden] whenever [owner]'s enclosing
 * [JViewport] scrolls. [owner] is typically not parented to its scroll pane's viewport yet at
 * install time (the caller wraps it afterwards, e.g. `ScrollPaneFactory.createScrollPane`), so
 * the viewport is (re-)resolved via a [java.awt.event.HierarchyListener] rather than up front.
 */
private fun installHideOnScroll(owner: JComponent, host: TooltipHost, onHidden: () -> Unit) {
    var boundViewport: JViewport? = null
    val onScroll = ChangeListener {
        host.hideNow(owner)
        onHidden()
    }

    fun rebind() {
        val viewport = owner.parent as? JViewport
        if (viewport === boundViewport) return
        boundViewport?.removeChangeListener(onScroll)
        boundViewport = viewport
        viewport?.addChangeListener(onScroll)
    }

    owner.addHierarchyListener { e ->
        if (e.changeFlags and HierarchyEvent.PARENT_CHANGED.toLong() != 0L) rebind()
    }
    rebind()
}

/**
 * Force-hides [owner]'s tooltip when its ancestor [Window] loses focus - e.g. when a dialog opens
 * on top of it (jj-idea-2md7 follow-up). [IdeTooltipManager]'s own "moving towards the balloon"
 * dismissal heuristic is pure on-screen vector math with no notion of window boundaries, so it can
 * judge the pointer's path into a brand-new window as "moving towards" a stale tooltip and refuse
 * to release it - and since [IdeTooltipManager] is an app-wide singleton, that stuck tooltip then
 * blocks every other component's tooltip in the IDE. Window focus loss is an unambiguous exit
 * signal, so this force-hides via [TooltipHost.hideNow] rather than relying on that heuristic.
 */
private fun installHideOnWindowDeactivation(owner: JComponent, host: TooltipHost, onHidden: () -> Unit) {
    var boundWindow: Window? = null
    val onLostFocus = object : WindowAdapter() {
        override fun windowLostFocus(e: WindowEvent) {
            host.hideNow(owner)
            onHidden()
        }
    }

    fun rebind() {
        val window = SwingUtilities.getWindowAncestor(owner)
        if (window === boundWindow) return
        boundWindow?.removeWindowFocusListener(onLostFocus)
        boundWindow = window
        window?.addWindowFocusListener(onLostFocus)
    }

    owner.addHierarchyListener { e ->
        if (e.changeFlags and HierarchyEvent.PARENT_CHANGED.toLong() != 0L) rebind()
    }
    rebind()
}
