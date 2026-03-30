package `in`.kkkev.jjidea.setup

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import `in`.kkkev.jjidea.JujutsuBundle
import javax.swing.JComponent

/**
 * Dialog for configuring jj user.name and user.email settings.
 *
 * Pre-populates fields from git global config if available.
 *
 * **Important**: Initial values should be loaded off EDT before constructing this dialog.
 * Use [JjUserConfigChecker.getGitConfig] to load git config on a background thread.
 */
class JjUserConfigDialog(
    project: Project,
    initialName: String? = null,
    initialEmail: String? = null
) : DialogWrapper(project) {
    /**
     * Result of the config dialog - the values the user entered.
     */
    data class UserConfig(val name: String, val email: String)

    var result: UserConfig? = null
        private set

    private val nameField = JBTextField(initialName ?: "")
    private val emailField = JBTextField(initialEmail ?: "")

    init {
        title = JujutsuBundle.message("dialog.userconfig.title")
        setOKButtonText(JujutsuBundle.message("dialog.userconfig.button"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(JujutsuBundle.message("dialog.userconfig.name.label")) {
            cell(nameField).focused().columns(COLUMNS_MEDIUM)
        }
        row(JujutsuBundle.message("dialog.userconfig.email.label")) {
            cell(emailField).columns(COLUMNS_MEDIUM)
        }
        row {
            comment(JujutsuBundle.message("dialog.userconfig.comment"))
        }
    }

    override fun getPreferredFocusedComponent() = nameField

    override fun doValidate(): ValidationInfo? = when {
        nameField.text.isBlank() ->
            ValidationInfo(JujutsuBundle.message("dialog.userconfig.error.name.empty"), nameField)
        emailField.text.isBlank() ->
            ValidationInfo(JujutsuBundle.message("dialog.userconfig.error.email.empty"), emailField)
        else -> null
    }

    override fun doOKAction() {
        result = UserConfig(
            name = nameField.text.trim(),
            email = emailField.text.trim()
        )
        super.doOKAction()
    }
}
