package `in`.kkkev.jjidea.actions.tag

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Tag

class TagNameDialog(
    private val repo: JujutsuRepository,
    private val onName: (Tag) -> Unit
) : DialogWrapper(repo.project) {
    private val nameField = JBTextField()

    init {
        title = JujutsuBundle.message("dialog.tag.set.input.title")
        init()
    }

    override fun createCenterPanel() = panel {
        row(JujutsuBundle.message("dialog.tag.set.input.message")) {
            cell(nameField).focused().columns(COLUMNS_MEDIUM)
        }
    }

    override fun continuousValidation() = true

    override fun doValidate() = nameField.text.takeIf { it.isBlank() }
        ?.let { ValidationInfo(JujutsuBundle.message("dialog.tag.set.error.empty"), nameField) }

    override fun doOKAction() {
        onName(Tag(nameField.text.trim()))
        super.doOKAction()
    }
}
