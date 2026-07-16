package `in`.kkkev.jjidea.actions.change

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.invalidate

/**
 * The log-selected entry that "Edit" can act on, or `null` if editing isn't applicable:
 * the working copy is already `@`, and immutable changes can't become the working copy.
 */
fun editableEntry(entry: LogEntry?): LogEntry? = entry?.takeIf { !it.isWorkingCopy && !it.immutable }

/**
 * Toolbar/context-menu "Edit" action that reads its target from the event's log selection.
 * Moves the working copy to the selected commit via `jj edit <change-id>`.
 *
 * Reads the current selection at action time via [in.kkkev.jjidea.actions.logEntry] (rather
 * than capturing a fixed [LogEntry] at construction), so it can be registered once and
 * referenced by ID from both the log toolbar and the context menu.
 */
class EditChangeAction : DumbAwareAction(
    JujutsuBundle.message("log.action.edit"),
    JujutsuBundle.message("log.action.edit.tooltip"),
    AllIcons.Actions.Edit
) {
    private val log = Logger.getInstance(javaClass)

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = editableEntry(e.logEntry) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = editableEntry(e.logEntry) ?: return
        val jujutsuRoot = target.repo
        val id = target.id

        jujutsuRoot.commandExecutor
            .createCommand { edit(id) }
            .onSuccess {
                // The edited change becomes the working copy - select it
                jujutsuRoot.invalidate(select = id, vfsChanged = true)
                log.info("Edited change $id")
            }.onFailure { tellUser(project, "log.action.edit.error") }
            .executeAsync()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
