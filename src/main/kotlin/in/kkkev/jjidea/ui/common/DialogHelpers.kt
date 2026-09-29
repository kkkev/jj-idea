package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.components.IconAwareHtmlPane
import `in`.kkkev.jjidea.ui.components.append
import `in`.kkkev.jjidea.ui.components.appendDescriptionAndEmptyIndicator
import `in`.kkkev.jjidea.ui.components.htmlString
import `in`.kkkev.jjidea.ui.log.appendDecorations
import `in`.kkkev.jjidea.ui.log.appendStatusIndicators
import java.awt.Component
import java.awt.Dimension
import javax.swing.*

fun createVerticalPanel(vararg children: Component) = JPanel().apply {
    this.layout = BoxLayout(this, BoxLayout.Y_AXIS)
    this.alignmentX = JPanel.LEFT_ALIGNMENT
    this.border = JBUI.Borders.empty(0, 8)
    children.forEach(this::add)
}

/** A bold, left-aligned section header, as used above each block of a picker dialog. */
fun createSectionLabel(text: String): JLabel {
    val label = JLabel(text)
    label.font = label.font.deriveFont(java.awt.Font.BOLD)
    label.alignmentX = JLabel.LEFT_ALIGNMENT
    return label
}

/** Source lists longer than this scroll instead of growing (jj-idea-1uz0, GitHub #125). */
internal const val MAX_VISIBLE_SOURCE_ROWS = 5

/**
 * Read-only list of [sourceEntries] for the top of a picker dialog. Lists of up to
 * [MAX_VISIBLE_SOURCE_ROWS] are returned as the bare pane; longer ones are wrapped in a scroll
 * pane capped at that many rows, so a big selection can't squeeze the picker below it to zero
 * height (the pane sits in a `BoxLayout` at `BorderLayout.NORTH`, which otherwise grows unbounded).
 */
fun createSourcePanel(project: Project, sourceEntries: List<LogEntry>): JComponent {
    val pane = IconAwareHtmlPane(project).apply {
        alignmentX = JPanel.LEFT_ALIGNMENT
        text = htmlString {
            append(sourceEntries, separator = "\n") { entry ->
                appendStatusIndicators(entry)
                append(entry.id)
                append(" ")
                appendDescriptionAndEmptyIndicator(entry)
                append(" ")
                appendDecorations(entry)
            }
        }
    }
    return if (sourceEntries.size <= MAX_VISIBLE_SOURCE_ROWS) {
        pane
    } else {
        // Rows can contain icons, so derive the row height from the rendered pane, not font metrics.
        val fullHeight = pane.preferredSize.height
        val cappedHeight = fullHeight / sourceEntries.size * MAX_VISIBLE_SOURCE_ROWS
        JBScrollPane(pane).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            alignmentX = JPanel.LEFT_ALIGNMENT
            // BoxLayout stretches children to maximumSize, so cap both.
            preferredSize = Dimension(preferredSize.width, cappedHeight)
            maximumSize = Dimension(Int.MAX_VALUE, cappedHeight)
        }
    }
}
