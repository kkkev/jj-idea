package `in`.kkkev.jjidea.actions.change

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import `in`.kkkev.jjidea.actions.NullAwareAction
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.jj.LogEntry
import java.awt.datatransfer.StringSelection

abstract class CopyAction(messageKey: String, private val what: String) : NullAwareAction<LogEntry>(
    messageKey,
    AllIcons.Actions.Copy
) {
    private val log = Logger.getInstance(javaClass)
    override fun extractTarget(e: AnActionEvent) = e.logEntry

    abstract fun extractText(entry: LogEntry): String

    override fun actionPerformed(target: LogEntry) {
        val text = extractText(target)
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        log.info("Copied $what to clipboard: $text")
    }
}

class CopyChangeIdAction : CopyAction("log.action.copy.changeid", "change ID") {
    override fun extractText(entry: LogEntry) = entry.id.toString()
}

/**
 * Keymap-assignable "Copy Commit ID"; see [CopyChangeIdAction]. Copies the full id.
 */
class CopyCommitIdAction : CopyAction("log.action.copy.commitid", "commit ID") {
    override fun extractText(entry: LogEntry) = entry.commitId.full
}

class CopyDescriptionAction : CopyAction("log.action.copy.description", "description") {
    override fun extractText(entry: LogEntry) = entry.description.actual
}
