package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.logEntries
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.actions.uniqueRepo
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.common.JujutsuIcons

/**
 * Toolbar/context-menu "Rebase" action that reads its target from the event's log selection
 * (GitHub #78, jj-idea-crt0). Registered under `Jujutsu.RebaseChangeToolbar` so it can be added
 * by ID from both the log toolbar and the log table's right-click menu
 * ([in.kkkev.jjidea.ui.log.JujutsuLogContextMenuActions.createActionGroup]'s `liveSelection` path)
 * - the same registered instance in both places is what lets IntelliJ show its keyboard shortcut
 * hint next to the context-menu entry, which a fresh per-menu-build action never could.
 *
 * [selectedEntries] prefers the multi-select-aware [in.kkkev.jjidea.actions.logEntries] and falls
 * back to the single [in.kkkev.jjidea.actions.logEntry]. [update]/[actionPerformed] reproduce the
 * old context-menu factory's exact repo/mutability logic: [uniqueRepo] is computed from *all*
 * selected entries (not just mutable ones) - a mutable/immutable mix spanning two repos still
 * correctly disables - while only the mutable subset is actually passed to
 * [in.kkkev.jjidea.ui.rebase.RebaseDialog] via [performRebase].
 */
class RebaseChangeAction : DumbAwareAction(
    JujutsuBundle.message("log.action.rebase"),
    JujutsuBundle.message("log.action.rebase.tooltip"),
    JujutsuIcons.Rebase
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun selectedEntries(e: AnActionEvent): List<LogEntry> =
        e.logEntries.ifEmpty { listOfNotNull(e.logEntry) }

    /** The repo to rebase in, or `null` if disabled - mirrors the old context-menu factory's logic. */
    private fun rebaseRepo(entries: List<LogEntry>): JujutsuRepository? {
        val mutableEntries = entries.filter { !it.immutable }
        return entries.uniqueRepo?.takeIf { mutableEntries.isNotEmpty() }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = rebaseRepo(selectedEntries(e)) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val entries = selectedEntries(e)
        val repo = rebaseRepo(entries) ?: return
        performRebase(project, repo, entries.filter { !it.immutable })
    }
}
