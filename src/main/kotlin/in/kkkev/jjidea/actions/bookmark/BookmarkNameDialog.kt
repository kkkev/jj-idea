package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.BookmarkName
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.createCommand
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.jj.message
import `in`.kkkev.jjidea.jj.refNameSyntaxError

/**
 * Maps a failed `jj bookmark create|rename` to a message key (jj-idea-27b4, jj-idea-v6mt). Exit 2 is
 * clap's own parse/usage error, e.g. `Failed to parse bookmark name` for `a&b`. jj exits 1 for nearly
 * every runtime error, so "already exists" is read from stderr, never from the exit code alone.
 */
internal fun classifyBookmarkFailure(actionType: String, exitCode: Int, stderr: String) = when {
    exitCode == 2 -> "dialog.bookmark.$actionType.error.incorrect.format"
    stderr.contains("already exists", ignoreCase = true) -> "dialog.bookmark.$actionType.error.already.exists"
    else -> "dialog.bookmark.$actionType.error.unknown"
}

abstract class BookmarkNameDialog(private val repo: JujutsuRepository, private val actionType: String) :
    DialogWrapper(repo.project) {
    val log = Logger.getInstance(javaClass)
    val nameField = JBTextField()

    val bookmark get() = BookmarkName(nameField.text)

    // Cache failed bookmarks and errors, so continuous validation picks them up
    val failedNames = mutableMapOf<BookmarkName, String>()

    init {
        title = JujutsuBundle.message("dialog.bookmark.$actionType.input.title")
        init()
    }

    override fun createCenterPanel() = panel {
        row(JujutsuBundle.message("dialog.bookmark.$actionType.input.message")) {
            cell(nameField).focused().columns(COLUMNS_MEDIUM)
                .validationOnInput { validationMessage()?.let { msg -> error(msg) } }
        }
    }

    override fun continuousValidation() = true

    // Called when user clicks OK — return null if valid, error string if not
    private fun validationMessage(): String? {
        val text = nameField.text
        return when {
            // Don't flag an untouched, empty field; OK is blocked in doOKAction.
            text.isEmpty() -> null
            bookmark.isRemote -> JujutsuBundle.message("dialog.bookmark.$actionType.error.not.remote", bookmark)
            else -> refNameSyntaxError(text)?.message()
                ?: failedNames[bookmark]?.let { JujutsuBundle.message(it, bookmark) }
        }
    }

    // Also runs when OK is clicked; validationOnInput on the field gives the per-keystroke feedback.
    override fun doValidate() = validationMessage()?.let { ValidationInfo(it, nameField) }

    override fun doOKAction() {
        if (nameField.text.isEmpty() || bookmark.isRemote || refNameSyntaxError(nameField.text) != null) return
        runCommand()
    }

    private fun runCommand() = repo.createCommand { execute(this) }
        .onSuccess {
            invalidate()
            onSuccess()
            super.doOKAction()
        }.onFailure {
            // jj exits 1 for essentially every runtime error, not just a name conflict (verified:
            // e.g. `jj bookmark create x -r nonexistent_rev` also exits 1) - jj-idea-27b4. Reading
            // stderr for the actual wording ("Bookmark already exists: <name>", verified against jj
            // 0.44) instead of trusting exitCode alone stops misattributing every other exit-1
            // failure as a name conflict. Exit 2 is reliably clap's own parse/usage error.
            // Note: a bare `exitCode` here would resolve to DialogWrapper.getExitCode(), not jj's.
            val key = classifyBookmarkFailure(actionType, result.exitCode, stderr)
            failedNames[bookmark] = key
            if (key.endsWith(".unknown")) {
                log.warn("Failed to $actionType bookmark '${bookmark.name}': $stderr")
            }
        }
        .executeAsync()

    abstract fun execute(executor: CommandExecutor): CommandExecutor.CommandResult
    open fun onSuccess() {}
}
