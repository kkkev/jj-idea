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
import `in`.kkkev.jjidea.jj.message
import `in`.kkkev.jjidea.jj.refNameSyntaxError

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
                .validationOnInput { validationMessage()?.let { msg -> error(msg) } }
        }
    }

    override fun continuousValidation() = true

    private fun validationMessage(): String? {
        val name = nameField.text.trim()
        return if (name.isEmpty()) {
            JujutsuBundle.message("dialog.tag.set.error.empty")
        } else {
            refNameSyntaxError(name)?.message()
        }
    }

    override fun doValidate() = validationMessage()?.let { ValidationInfo(it, nameField) }

    override fun doOKAction() {
        onName(Tag(nameField.text.trim()))
        super.doOKAction()
    }
}
