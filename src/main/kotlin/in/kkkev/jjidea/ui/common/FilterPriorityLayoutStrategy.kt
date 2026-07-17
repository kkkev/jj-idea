package `in`.kkkev.jjidea.ui.common

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy
import com.intellij.util.ui.JBInsets
import `in`.kkkev.jjidea.ui.log.JujutsuFilterComponent
import java.awt.Dimension
import java.awt.Rectangle
import kotlin.math.max

/**
 * A horizontal toolbar layout that hides items behind the standard "»" overflow popup under width
 * pressure, like [ToolbarLayoutStrategy.AUTOLAYOUT_STRATEGY] — but chooses *which* items to hide by
 * priority ([JujutsuFilterComponent]s without an applied value are hidden first) rather than
 * strictly by trailing position. Whatever stays visible keeps its original left-to-right order:
 * nothing moves as filters are applied or cleared, only visibility changes.
 *
 * Items are atomic (shown at full preferred size or hidden entirely) — there is no partial shrink,
 * matching how [ToolbarLayoutStrategy.AUTOLAYOUT_STRATEGY] itself behaves. The overflow popup is
 * the platform's own mechanism: [ActionToolbarImpl][com.intellij.openapi.actionSystem.impl.ActionToolbarImpl]
 * shows it for any child whose bounds this strategy reports as [Int.MAX_VALUE].
 */
internal object FilterPriorityLayoutStrategy : ToolbarLayoutStrategy {
    private val expandIcon = AllIcons.Ide.Link

    override fun calculateBounds(toolbar: ActionToolbar): List<Rectangle> = layOut(toolbar, toolbar.component.width)

    override fun calcPreferredSize(toolbar: ActionToolbar): Dimension {
        val container = toolbar.component
        if (container.componentCount == 0) return Dimension()
        val size = layOut(toolbar, Int.MAX_VALUE).reduce { acc, r -> acc.union(r) }.size
        JBInsets.addTo(size, container.insets)
        return size
    }

    override fun calcMinimumSize(toolbar: ActionToolbar): Dimension {
        val container = toolbar.component
        if (container.componentCount == 0) return Dimension()
        val size = Dimension(expandIcon.iconWidth, expandIcon.iconHeight)
        JBInsets.addTo(size, container.insets)
        return size
    }

    private fun layOut(toolbar: ActionToolbar, widthToFit: Int): List<Rectangle> {
        val container = toolbar.component
        val n = container.componentCount
        val insets = container.insets
        val available = if (widthToFit == Int.MAX_VALUE) Int.MAX_VALUE else widthToFit - insets.left - insets.right
        val reserveForChevron = if (toolbar.isReservePlaceAutoPopupIcon) expandIcon.iconWidth else 0

        val preferred = (0 until n).map { container.getComponent(it).preferredSize }
        val totalWidth = preferred.sumOf { it.width }

        val included: Set<Int> = when {
            available == Int.MAX_VALUE || totalWidth <= available -> (0 until n).toSet()
            else -> {
                // Not everything fits: pick which items to keep, prioritizing active filters,
                // but preserve their original relative order among the chosen set below.
                val budget = available - reserveForChevron
                val priorityOrder = (0 until n).sortedBy { i ->
                    if ((container.getComponent(i) as? JujutsuFilterComponent)?.isActive == true) 0 else 1
                }
                var used = 0
                val chosen = mutableSetOf<Int>()
                for (i in priorityOrder) {
                    val width = preferred[i].width
                    if (used + width <= budget) {
                        used += width
                        chosen += i
                    }
                }
                chosen
            }
        }

        val maxHeight = preferred.maxOfOrNull { it.height } ?: 0
        var x = insets.left
        return (0 until n).map { i ->
            val size = preferred[i]
            if (i in included) {
                val y = insets.top + max(0, (maxHeight - size.height) / 2)
                Rectangle(x, y, size.width, size.height).also { x += size.width }
            } else {
                Rectangle(Int.MAX_VALUE, Int.MAX_VALUE, size.width, size.height)
            }
        }
    }
}
