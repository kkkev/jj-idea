package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.logEntries
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.actions.requestDescription
import `in`.kkkev.jjidea.actions.saveDescriptionToHistory
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.createCommand
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.common.JujutsuIcons

/**
 * The change id shown in the Describe prompt: deliberately the *short* id, matching every
 * other id the UI displays. `ChangeId.toString()` yields the full id - right for `jj` CLI
 * arguments (GitHub #76), wrong for display (GitHub #76 regression, jj-idea-is97).
 */
internal fun describePromptId(target: LogEntry) = target.id.short

/**
 * Toolbar/context-menu "Describe" action that reads its target from the event's log selection
 * (GitHub #78, jj-idea-crt0). Registered under `Jujutsu.DescribeChangeToolbar` so it can be added
 * by ID from both the log toolbar and the log table's right-click menu
 * ([in.kkkev.jjidea.ui.log.JujutsuLogContextMenuActions.createActionGroup])
 * - the same registered instance in both places is what lets IntelliJ show its keyboard shortcut
 * hint next to the context-menu entry, which a fresh per-menu-build action never could.
 *
 * [target] gates on a single selection: [logEntry]
 * alone stays populated during a multi-row selection, so a >1-entry selection must be treated as
 * "no target" explicitly, or this would wrongly enable where the factory disabled.
 */
class DescribeChangeAction : DumbAwareAction(
    JujutsuBundle.message("log.action.describe"),
    JujutsuBundle.message("log.action.describe.tooltip"),
    JujutsuIcons.Describe
) {
    private val log = Logger.getInstance(javaClass)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun target(e: AnActionEvent): LogEntry? =
        if (e.logEntries.size > 1) null else e.logEntry?.takeUnless { it.immutable }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = target(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = target(e) ?: return

        target.repo.createCommand {
            log(target.id, "description")
        }.onSuccess { currentDescription ->
            project.requestDescription(
                "dialog.describe.input",
                Description(currentDescription.removeSuffix("\n")),
                describePromptId(target)
            )?.let { newDescription ->
                // If that was null, the user cancelled
                createCommand { describe(newDescription, target.id) }
                    .onSuccess {
                        invalidate()
                        project.saveDescriptionToHistory(newDescription)

                        log.info("Updated description of ${target.id}")
                    }.onFailure { tellUser("log.action.describe.error") }
                    .executeAsync()
            }
        }.executeAsync()
    }
}
