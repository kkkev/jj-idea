package `in`.kkkev.jjidea.vcs.actions

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.invalidate

abstract class BookmarkNameDialog(private val repo: JujutsuRepository, private val actionType: String) :
    DialogWrapper(repo.project) {
    val log = Logger.getInstance(javaClass)
    val nameField = JBTextField()

    val bookmark get() = Bookmark(nameField.text)

    // Cache failed bookmarks and errors, so continuous validation picks them up
    val failedNames = mutableMapOf<Bookmark, String>()

    init {
        title = JujutsuBundle.message("dialog.bookmark.$actionType.input.title")
        init()
    }

    override fun createCenterPanel() = panel {
        row(JujutsuBundle.message("dialog.bookmark.$actionType.input.message")) {
            cell(nameField).focused().columns(COLUMNS_MEDIUM)
        }
    }

    override fun continuousValidation() = true

    // Called when user clicks OK — return null if valid, error string if not
    override fun doValidate() = when {
        bookmark.isRemote -> "dialog.bookmark.$actionType.error.not.remote"

        else -> failedNames[bookmark]
    }?.let {
        ValidationInfo(JujutsuBundle.message(it, bookmark), nameField)
    }

    override fun doOKAction() = repo.commandExecutor
        .createCommand { execute(this) }
        .onSuccess {
            repo.invalidate()
            onSuccess()
            super.doOKAction()
        }.onFailure {
            failedNames[bookmark] = when (exitCode) {
                1 -> "dialog.bookmark.$actionType.error.already.exists"
                2 -> "dialog.bookmark.$actionType.error.incorrect.format"
                else -> "dialog.bookmark.$actionType.error.unknown"
            }
        }
        .executeAsync()

    abstract fun execute(executor: CommandExecutor): CommandExecutor.CommandResult
    open fun onSuccess() {}
}
