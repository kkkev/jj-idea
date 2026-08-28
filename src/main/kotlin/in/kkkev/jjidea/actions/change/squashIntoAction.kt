package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.diffedit.DiffEditTool
import `in`.kkkev.jjidea.jj.ChangeService
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.common.HunkSelection
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.squash.SquashIntoDialog
import `in`.kkkev.jjidea.ui.squash.SquashIntoSpec
import `in`.kkkev.jjidea.ui.squash.SquashMode
import `in`.kkkev.jjidea.ui.squash.loadSquashFileData
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.relativeTo

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
    sources: List<LogEntry>
) = nullAndDumbAwareAction(repo, "log.action.squash.into", JujutsuIcons.Squash) {
    runInBackground {
        val changes = ChangeService.loadChanges(sources)
        runLater {
            val dialog = SquashIntoDialog(project, target, SquashMode.PickDestination(sources), changes)
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
    val hunkSelection = spec.hunkSelection
    if (hunkSelection == null) {
        executeSquashIntoFilePaths(project, repo, sources, spec)
    } else {
        executeSquashIntoInteractive(project, repo, sources, spec, hunkSelection)
    }
}

/** File-level squash — unchanged from pre-hunk-level implementation. */
private fun executeSquashIntoFilePaths(
    project: Project,
    repo: JujutsuRepository,
    sources: List<LogEntry>,
    spec: SquashIntoSpec
) {
    val workingCopyIsSource = sources.any { it.isWorkingCopy }
    val deleteAndMove = spec.deleteEmptyAndMoveWorkingCopy
    val editDestinationAfter = workingCopyIsSource && deleteAndMove
    repo.commandExecutor
        .createCommand {
            val result = squashInto(
                spec.sources,
                spec.destination,
                spec.filePaths,
                spec.description,
                keepEmptied = !deleteAndMove
            )
            if (!result.isSuccess) return@createCommand result
            if (editDestinationAfter) edit(spec.destination) else result
        }
        .onSuccess {
            val selectId: Revision = if (deleteAndMove) spec.destination else sources.first().id
            repo.invalidate(select = selectId, vfsChanged = true)
            squashIntoLog.info("Squashed ${spec.sources} into ${spec.destination}")
        }
        .onFailure { tellUser(project, "log.action.squash.into.error") }
        .executeAsync()
}

/**
 * Hunk-level squash via jj's diff-editor protocol — the squash analog of
 * [executeSplitInteractive]. Single-source only: [SquashIntoDialog] only ever produces a non-null
 * [SquashIntoSpec.hunkSelection] when exactly one source is selected (see [HunkSelection]'s KDoc).
 */
private fun executeSquashIntoInteractive(
    project: Project,
    repo: JujutsuRepository,
    sources: List<LogEntry>,
    spec: SquashIntoSpec,
    hunkSelection: HunkSelection
) {
    val source = sources.single()
    val workingCopyIsSource = source.isWorkingCopy
    val deleteAndMove = spec.deleteEmptyAndMoveWorkingCopy
    val editDestinationAfter = workingCopyIsSource && deleteAndMove

    runInBackground {
        val perFileContent = hunkSelection.buildPerFileContent().toMutableMap()

        // The dialog's preview cache is populated lazily (only for files the user clicked to
        // preview). A ticked-but-never-previewed file has null content here, which would leave
        // the destination unaffected for that file instead of receiving its full change - fetch
        // it now, before building the staging tree, exactly like executeSplitInteractive does.
        val root = repo.directory
        val missingRelPaths = perFileContent.filterValues { it == null }.keys
        if (missingRelPaths.isNotEmpty()) {
            val changes = ChangeService.loadChanges(listOf(source))
            for (change in changes) {
                val relPath = change.filePath.relativeTo(root)
                if (relPath !in missingRelPaths) continue
                val data = loadSquashFileData(change)
                if (data != null) {
                    perFileContent[relPath] = data.after
                } else {
                    squashIntoLog.warn("Could not fetch after-content for $relPath; file will not be squashed")
                }
            }
        }

        val result = DiffEditTool.withStagingTree(perFileContent, hunkSelection.deletedPaths) { configArgs, tool ->
            val squashResult = repo.commandExecutor.squashIntoInteractive(
                source = source.id,
                destination = spec.destination,
                description = spec.description,
                keepEmptied = !deleteAndMove,
                configArgs = configArgs,
                tool = tool
            )
            if (squashResult.isSuccess && editDestinationAfter) {
                repo.commandExecutor.edit(spec.destination)
            } else {
                squashResult
            }
        }

        runLater {
            if (!result.isSuccess) {
                result.tellUser(project, "log.action.squash.into.error")
                return@runLater
            }
            val selectId: Revision = if (deleteAndMove) spec.destination else source.id
            repo.invalidate(select = selectId, vfsChanged = true)
            squashIntoLog.info("Squashed hunks from ${source.id} into ${spec.destination}")
        }
    }
}
