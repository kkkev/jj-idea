package `in`.kkkev.jjidea.ui.describe

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.ui.components.DescriptionEditor
import javax.swing.JComponent

/**
 * Dialog for editing a single change's description via a real [DescriptionEditor] - spellcheck,
 * length inspections, and message history - replacing the old
 * `Messages.showMultilineInputDialog` (GitHub #46, jj-idea-n3w1). Backs
 * [`in.kkkev.jjidea.actions.requestDescription`], which keeps the previous
 * `Project.requestDescription(...): Description?` call shape so its four callers are unchanged.
 */
internal class DescribeDialog(
    project: Project,
    title: String,
    private val label: String,
    initial: Description
) : DialogWrapper(project) {
    private val editor = DescriptionEditor(project, placeholder = label)

    var result: Description? = null
        private set

    init {
        this.title = title
        editor.text = initial
        Disposer.register(disposable, editor)
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label(label)
                .also { it.component.labelFor = editor.focusTarget }
                .resizableColumn()
                .align(AlignX.FILL)
            cell(editor.commitMessage.createToolbar(true))
        }
        row {
            cell(editor.component).align(Align.FILL)
        }.resizableRow()
    }

    override fun getPreferredFocusedComponent() = editor.focusTarget

    override fun getDimensionServiceKey() = "in.kkkev.jjidea.ui.describe.DescribeDialog"

    override fun doOKAction() {
        result = editor.text
        super.doOKAction()
    }
}
