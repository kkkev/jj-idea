package `in`.kkkev.jjidea.ui.components

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.jj.JujutsuRepository
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A one-field prompt for a revset, in place of `Messages.showInputDialog` so the field highlights and
 * completes like the log's revset inputs (jj-idea-ehwm.7). Enter in the field accepts.
 */
class RevsetInputDialog(
    project: Project,
    repos: List<JujutsuRepository>,
    dialogTitle: String,
    private val message: String,
    initial: String
) : DialogWrapper(project) {
    private val field = RevsetTextField(project, { repos }, initialText = initial).apply {
        sizeToColumns(FIELD_COLUMNS)
        onEnter = { doOKAction() }
    }

    /** The typed revset, trimmed. */
    val revset get() = field.text.trim()

    init {
        title = dialogTitle
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
        add(JBLabel(message), BorderLayout.NORTH)
        add(field, BorderLayout.CENTER)
    }

    override fun getPreferredFocusedComponent(): JComponent = field.focusTarget

    companion object {
        private const val FIELD_COLUMNS = 50

        /** Shows the dialog; the trimmed revset on OK, or null if cancelled. */
        fun prompt(
            project: Project,
            repos: List<JujutsuRepository>,
            title: String,
            message: String,
            initial: String
        ): String? = RevsetInputDialog(project, repos, title, message, initial)
            .takeIf { it.showAndGet() }
            ?.revset
    }
}
