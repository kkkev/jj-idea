package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.logEntries
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.common.JujutsuIcons

/**
 * Toolbar/context-menu "Describe" action that reads its target from the event's log selection
 * (GitHub #78, jj-idea-crt0). Registered under `Jujutsu.DescribeChangeToolbar` so it can be added
 * by ID from both the log toolbar and the log table's right-click menu
 * ([in.kkkev.jjidea.ui.log.JujutsuLogContextMenuActions.createActionGroup]'s `liveSelection` path)
 * - the same registered instance in both places is what lets IntelliJ show its keyboard shortcut
 * hint next to the context-menu entry, which a fresh per-menu-build action never could.
 *
 * [target] mirrors the old context-menu factory's `entries.singleOrNull()` gating: [logEntry]
 * alone stays populated during a multi-row selection, so a >1-entry selection must be treated as
 * "no target" explicitly, or this would wrongly enable where the factory disabled.
 */
class DescribeChangeAction : DumbAwareAction(
    JujutsuBundle.message("log.action.describe"),
    JujutsuBundle.message("log.action.describe.tooltip"),
    JujutsuIcons.Describe
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun target(e: AnActionEvent): LogEntry? =
        if (e.logEntries.size > 1) null else e.logEntry?.takeUnless { it.immutable }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = target(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = target(e) ?: return
        performDescribe(project, target)
    }
}
