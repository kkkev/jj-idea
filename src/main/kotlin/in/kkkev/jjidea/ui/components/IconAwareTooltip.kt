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
import java.awt.event.HierarchyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.JComponent
import javax.swing.JViewport
import javax.swing.ScrollPaneConstants
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
}

/**
 * Whether [IdeTooltipManager] should be allowed to auto-hide the tooltip for [event]. Extracted
 * as a top-level function (rather than left inline in the `canAutohideOn` override) so tests can
 * exercise it directly - `canAutohideOn` itself is `protected` on [IdeTooltip], unreachable from
 * outside its class hierarchy.
 *
 * Mirrors [com.intellij.ui.TooltipWithClickableLinks]: spare the tooltip while the pointer is
 * inside the balloon (or judged by [IdeTooltipManager] to be moving towards it - see
 * [com.intellij.ui.BalloonImpl.isMovingForward]), so hyperlinks/selectable text inside it are
 * reachable instead of the tooltip dismissing itself the moment the pointer heads that way.
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

    val tooltip = object : IdeTooltip(owner, Point(0, 0), null), SuppressibleTooltip {
        override val isSuppressedUntilMouseMove: Boolean get() = suppressedUntilMouseMove

        override fun beforeShow(): Boolean {
            if (suppressedUntilMouseMove) return false

            val mousePos = owner.mousePosition ?: return false
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

    var lastCellKey: Any? = null
    owner.addMouseMotionListener(
        object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                suppressedUntilMouseMove = false
                val newCellKey = cellKeyAt(e.point)
                if (newCellKey != lastCellKey && host.hideOnMouseMove(e)) {
                    lastCellKey = newCellKey
                }
            }
        }
    )

    installHideOnScroll(owner, host) { suppressedUntilMouseMove = true }

    // The identity check (currentTooltip === tooltip) in IdeTooltipManager keeps the tooltip
    // stable during mouse movement within the same cell.
    host.install(owner, tooltip)
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
