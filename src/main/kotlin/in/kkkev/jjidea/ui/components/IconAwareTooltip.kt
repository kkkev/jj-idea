package `in`.kkkev.jjidea.ui.components

import com.intellij.ide.IdeTooltip
import com.intellij.ide.IdeTooltipManager
import com.intellij.openapi.project.Project
import com.intellij.ui.ScreenUtil
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.Point
import java.awt.event.MouseMotionAdapter
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants

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
 * Renders [owner]'s tooltips through [IconAwareHtmlPane] so chip `<img src='chip:…'>` elements
 * (bookmark/tag chips, author/date "unbreakable" runs) resolve instead of painting as broken
 * images — a plain Swing tooltip pane doesn't know the `chip:` URL scheme (jj-idea-fmrj).
 *
 * [cellKeyAt] identifies the hovered cell/row for a given point; when it changes, the current
 * tooltip is force-hidden so [IdeTooltipManager] re-invokes `beforeShow()` with fresh content.
 * [htmlAt] supplies the tooltip HTML for a point, or null/blank to show no tooltip there.
 */
internal fun installIconAwareTooltip(
    owner: JComponent,
    project: Project,
    cellKeyAt: (Point) -> Any?,
    htmlAt: (Point) -> String?
) {
    val tooltip = object : IdeTooltip(owner, Point(0, 0), null) {
        override fun beforeShow(): Boolean {
            val mousePos = owner.mousePosition ?: return false
            val html = htmlAt(mousePos)
            if (html.isNullOrBlank()) return false

            point = mousePos
            tipComponent = Wrapper(iconAwareTooltipComponent(project, html, owner))
            return true
        }

        override fun canBeDismissedOnTimeout() = false
    }

    var lastCellKey: Any? = null
    owner.addMouseMotionListener(
        object : MouseMotionAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                val newCellKey = cellKeyAt(e.point)
                if (newCellKey != lastCellKey) {
                    lastCellKey = newCellKey
                    IdeTooltipManager.getInstance().hideCurrentNow(false)
                }
            }
        }
    )

    // The identity check (currentTooltip === tooltip) in IdeTooltipManager keeps the tooltip
    // stable during mouse movement within the same cell; hideCurrentNow() above forces
    // re-evaluation on cell changes.
    IdeTooltipManager.getInstance().setCustomTooltip(owner, tooltip)
}
