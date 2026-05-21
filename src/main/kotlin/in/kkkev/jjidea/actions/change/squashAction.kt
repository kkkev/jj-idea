package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.ChangeService
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.squash.SquashIntoDialog
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

/**
 * Squash action. Loads changes on a background thread, opens a dialog to configure
 * file selection, description, and options, then executes `jj squash`.
 *
 * The entry must be mutable, have exactly one parent, and that parent must also be mutable.
 *
 * Delegates to [executeSquashInto] using the parent as the sole candidate destination.
 * When squashing the working copy without "keep emptied", the working copy moves to the
 * destination (handled in [executeSquashInto]).
 */
fun squashAction(project: Project, entry: LogEntry?, allEntries: List<LogEntry>) =
    nullAndDumbAwareAction(entry, "log.action.squash.into.parent", JujutsuIcons.Squash) {
        val candidateParents = allEntries.filter { it.id in target.parentIds.toSet() && !it.immutable }

        runInBackground {
            val changes = ChangeService.loadChanges(target)

            runLater {
                val dialog = SquashIntoDialog(
                    project,
                    target.repo,
                    listOf(target),
                    changes,
                    candidateDestinations = candidateParents
                )
                if (!dialog.showAndGet()) return@runLater
                val spec = dialog.result ?: return@runLater
                executeSquashInto(project, target.repo, listOf(target), spec)
            }
        }
    }

/**
 * Determine whether a squash action should be enabled for the given entry.
 * Requires: single selection, mutable, exactly one parent, and that parent must be mutable.
 */
fun squashableEntry(entry: LogEntry?, allEntries: List<LogEntry>): LogEntry? {
    val e = entry?.takeIf { !it.immutable } ?: return null
    // Disable for merge commits (multiple parents) and root commits (no parents)
    if (e.parentIds.size != 1) return null
    // Disable if parent is immutable
    val parent = allEntries.firstOrNull { it.id == e.parentIds.first() }
    if (parent?.immutable == true) return null
    return e
}
