package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.ChangeService
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.squash.SquashIntoDialog
import `in`.kkkev.jjidea.ui.squash.SquashMode
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

/**
 * Squash action. Loads changes and mutable parent entries on a background thread, opens a dialog
 * to configure file selection, description, and options, then executes `jj squash`.
 *
 * The entry must be mutable and have at least one parent. Merge commits are supported:
 * the dialog presents all mutable parents as candidate destinations and the user picks one.
 *
 * Delegates to [executeSquashInto] using the mutable parents as candidate destinations.
 * When squashing the working copy without "keep emptied", the working copy moves to the
 * destination (handled in [executeSquashInto]).
 */
fun squashAction(project: Project, entry: LogEntry?) =
    nullAndDumbAwareAction(entry, "log.action.squash.into.parent", JujutsuIcons.Squash) {
        runInBackground {
            val changes = ChangeService.loadChanges(target)
            val candidateParents = target.repo.logService
                .getLogBasic(Expression("parents(${target.id.short})"))
                .getOrNull().orEmpty()
                .filter { !it.immutable }

            runLater {
                val dialog = SquashIntoDialog(
                    project,
                    target.repo,
                    SquashMode.PickDestination(listOf(target), candidateParents),
                    changes
                )
                if (!dialog.showAndGet()) return@runLater
                val spec = dialog.result ?: return@runLater
                executeSquashInto(project, target.repo, listOf(target), spec)
            }
        }
    }

/**
 * Determine whether a squash action should be enabled for the given entry.
 * Requires: single selection, mutable, at least one parent.
 * Parent mutability is checked lazily when the dialog opens (avoids dependence on visible log window).
 */
fun squashableEntry(entry: LogEntry?): LogEntry? {
    val e = entry?.takeIf { !it.immutable } ?: return null
    if (e.parentIds.isEmpty()) return null
    return e
}
