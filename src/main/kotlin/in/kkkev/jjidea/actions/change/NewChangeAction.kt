package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.logEntries
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.common.JujutsuIcons

/**
 * The repository and parent revisions a "new change" should be created against, resolved
 * unambiguously from the log selection - or `null` if there's nothing usable to act on.
 */
data class NewChangeTarget(val repo: JujutsuRepository, val parents: List<Revision>)

/**
 * Resolves the target repo and parent revisions for a "new change" action purely from the
 * current log selection, without ever guessing across multiple repositories:
 * - Empty selection -> `null` (nothing to act on).
 * - Selected entries spanning more than one repo -> `null` (ambiguous).
 * - Otherwise -> that repo, with the selected entries' ids as parents.
 *
 * The log keeps the working copy (`@`) selected by default, so "new change from the selection"
 * already covers the common "new change on the working copy" case with no separate fallback.
 */
fun resolveNewChangeTarget(selectedEntries: List<LogEntry>): NewChangeTarget? =
    selectedEntries.takeIf { it.isNotEmpty() }
        ?.let { entries ->
            entries.map { it.repo }.toSet().singleOrNull()
                ?.let { repo -> NewChangeTarget(repo, entries.map { entry -> entry.id }) }
        }

/**
 * Quick "New Change" action that skips the description dialog, creating an unnamed change
 * directly from the log selection - the common case, since jj (like git) expects descriptions
 * to be added later.
 *
 * Bound to a default keyboard shortcut, but only enabled while the Jujutsu log is the focused
 * context (i.e. [in.kkkev.jjidea.actions.logEntries] is non-empty), since the shortcut collides
 * with the platform's GotoFile/NewScratchFile action elsewhere. In the log,
 * [in.kkkev.jjidea.vcs.JujutsuActionPromoter] makes this action win that collision - mirroring
 * how [in.kkkev.jjidea.actions.filechange.ShowDiffAction] handles its own Ctrl+D collision.
 */
class NewChangeAction : DumbAwareAction(
    JujutsuBundle.message("action.newchange"),
    JujutsuBundle.message("action.newchange.description"),
    JujutsuIcons.NewChange
) {
    override fun update(e: AnActionEvent) {
        val target = resolveNewChangeTarget(e.logEntries)
        e.presentation.isEnabled = target != null
        e.presentation.text = when (e.logEntries.size) {
            0, 1 -> JujutsuBundle.message("action.newchange.from.singular")
            else -> JujutsuBundle.message("action.newchange.from.plural")
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = resolveNewChangeTarget(e.logEntries) ?: return

        target.repo.commandExecutor.createCommand {
            new(description = Description.EMPTY, parentRevisions = target.parents)
        }.onSuccess {
            // The new change becomes the working copy - select it
            target.repo.invalidate(select = WorkingCopy, vfsChanged = true)
        }.onFailure { tellUser(project, "log.action.new.error") }
            .executeAsync()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
