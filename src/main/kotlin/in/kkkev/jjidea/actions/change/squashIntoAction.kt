package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.ChangeService
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.squash.SquashIntoDialog
import `in`.kkkev.jjidea.ui.squash.SquashIntoSpec
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

private val squashIntoLog = Logger.getInstance("in.kkkev.jjidea.actions.change.squashIntoAction")

/**
 * Squash Into action. Loads changes on a background thread, opens a dialog to pick a
 * destination and configure file selection, description, and options, then executes
 * `jj squash --from <SRC>... --into <DEST>`.
 *
 * All sources must be mutable and from the same repository.
 */
fun squashIntoAction(
    project: Project,
    repo: JujutsuRepository?,
    sources: List<LogEntry>,
    allEntries: List<LogEntry> = emptyList()
) = nullAndDumbAwareAction(repo, "log.action.squash.into", JujutsuIcons.Squash) {
    runInBackground {
        val changes = ChangeService.loadChanges(sources)
        runLater {
            val dialog = SquashIntoDialog(project, target, sources, changes, allEntries)
            if (!dialog.showAndGet()) return@runLater
            val spec = dialog.result ?: return@runLater
            executeSquashInto(project, target, sources, spec)
        }
    }
}

/**
 * Returns source entries valid for Squash Into: all must be mutable.
 */
fun squashIntoSources(entries: List<LogEntry>): List<LogEntry> {
    if (entries.isEmpty()) return emptyList()
    if (entries.any { it.immutable }) return emptyList()
    return entries
}

internal fun executeSquashInto(
    project: Project,
    repo: JujutsuRepository,
    sources: List<LogEntry>,
    spec: SquashIntoSpec
) {
    val workingCopyIsSource = sources.any { it.isWorkingCopy }
    val editDestinationAfter = workingCopyIsSource && !spec.keepEmptied
    repo.commandExecutor
        .createCommand {
            val result = squashInto(
                spec.sources,
                spec.destination,
                spec.filePaths,
                spec.description,
                spec.keepEmptied
            )
            if (!result.isSuccess) return@createCommand result
            if (editDestinationAfter) edit(spec.destination) else result
        }
        .onSuccess {
            val selectId: Revision = if (spec.keepEmptied) sources.first().id else spec.destination
            repo.invalidate(select = selectId, vfsChanged = true)
            squashIntoLog.info("Squashed ${spec.sources} into ${spec.destination}")
        }
        .onFailure { tellUser(project, "log.action.squash.into.error") }
        .executeAsync()
}
