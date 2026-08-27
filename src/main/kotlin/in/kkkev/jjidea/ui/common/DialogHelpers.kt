package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.components.IconAwareHtmlPane
import `in`.kkkev.jjidea.ui.components.append
import `in`.kkkev.jjidea.ui.components.appendDescriptionAndEmptyIndicator
import `in`.kkkev.jjidea.ui.components.htmlString
import `in`.kkkev.jjidea.ui.log.appendDecorations
import `in`.kkkev.jjidea.ui.log.appendStatusIndicators
import java.awt.Component
import javax.swing.BoxLayout
import javax.swing.JPanel

fun createVerticalPanel(vararg children: Component) = JPanel().apply {
    this.layout = BoxLayout(this, BoxLayout.Y_AXIS)
    this.alignmentX = JPanel.LEFT_ALIGNMENT
    this.border = JBUI.Borders.empty(0, 8)
    children.forEach(this::add)
}

fun createSourcePanel(project: Project, sourceEntries: List<LogEntry>) = IconAwareHtmlPane(project).apply {
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
