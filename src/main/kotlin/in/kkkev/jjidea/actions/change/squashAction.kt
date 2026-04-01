package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.ChangeService
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.squash.SquashDialog
import `in`.kkkev.jjidea.ui.squash.SquashSpec
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

private val squashLog = Logger.getInstance("in.kkkev.jjidea.actions.change.squashAction")

/**
 * Squash action. Loads changes on a background thread, opens a dialog to configure
 * file selection, description, and options, then executes `jj squash`.
 *
 * The entry must be mutable, have exactly one parent, and that parent must also be mutable.
 *
 * When squashing the working copy without "keep emptied", jj creates a new empty working copy
 * on top of the parent. We follow up with `jj edit @-` to move the working copy to the parent,
 * which automatically abandons the empty child.
 */
fun squashAction(project: Project, entry: LogEntry?, allEntries: List<LogEntry>) =
    nullAndDumbAwareAction(entry, "log.action.squash.into.parent", JujutsuIcons.Squash) {
        val parentEntry = allEntries.firstOrNull { it.id == target.parentIds.firstOrNull() }

        runInBackground {
            val changes = ChangeService.loadChanges(target)

            runLater {
                val dialog = SquashDialog(project, target, parentEntry, changes)
                if (!dialog.showAndGet()) return@runLater

                val spec = dialog.result ?: return@runLater
                executeSquash(project, target, parentEntry, spec, "log.action.squash.into.parent.error")
            }
        }
    }

/**
 * Execute the squash operation. Shared between the log-level squash action
 * and the file-level squash actions.
 */
internal fun executeSquash(
    project: Project,
    entry: LogEntry,
    parentEntry: LogEntry?,
    spec: SquashSpec,
    errorKey: String
) {
    val editParentAfterSquash = entry.isWorkingCopy && !spec.keepEmptied
    entry.repo.commandExecutor
        .createCommand {
            val result = squash(spec.revision, spec.filePaths, spec.description, spec.keepEmptied)
            if (!result.isSuccess) return@createCommand result
            // When squashing @, jj creates a new empty @ on the parent.
            // Edit into the parent so the user lands on the combined change.
            if (editParentAfterSquash) edit(WorkingCopy.parent) else result
        }
        .onSuccess {
            val selectId = when {
                spec.keepEmptied -> entry.id
                else -> parentEntry?.id ?: WorkingCopy
            }
            entry.repo.invalidate(select = selectId, vfsChanged = true)
            squashLog.info("Squashed ${entry.id} into parent")
        }
        .onFailure { tellUser(project, errorKey) }
        .executeAsync()
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
