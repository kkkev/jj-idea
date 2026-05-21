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
 * The entry must be mutable and have at least one mutable parent. Merge commits are supported:
 * the dialog presents all mutable parents as candidate destinations and the user picks one.
 *
 * Delegates to [executeSquashInto] using the mutable parents as candidate destinations.
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
 * Requires: single selection, mutable, at least one parent, and at least one parent must be mutable.
 */
fun squashableEntry(entry: LogEntry?, allEntries: List<LogEntry>): LogEntry? {
    val e = entry?.takeIf { !it.immutable } ?: return null
    // Disable for root commits (no parents)
    if (e.parentIds.isEmpty()) return null
    // Disable if all parents are immutable or none are visible in the log
    val hasAnyMutableParent = allEntries.any { it.id in e.parentIds.toSet() && !it.immutable }
    if (!hasAnyMutableParent) return null
    return e
}
