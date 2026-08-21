package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.diffedit.DiffEditTool
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeService
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.split.SplitDialog
import `in`.kkkev.jjidea.ui.split.SplitSpec
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.relativeTo

private val splitLog = Logger.getInstance("in.kkkev.jjidea.actions.change.splitAction")

/**
 * Parse the remaining side's change ID from `jj split` stderr - only meaningful in the default
 * (no `-B`) mode, where the remaining side is a genuinely new commit whose ID isn't known until
 * `jj split` returns. In `insertBefore` (newParent) mode, the remaining side keeps the source's
 * own change ID, which is already known - see [onSplitSuccess].
 *
 * Example output:
 * ```
 * Rebased 1 descendant commits
 * Selected changes : ppvwmllp bbaf2a0a first part
 * Remaining changes: ulwnpxxq 3fae5248 test commit
 * ```
 *
 * The "Remaining changes" line has the child's change ID.
 */
internal fun parseRemainingChangeId(stderr: String): ChangeId? {
    val regex = Regex("""Remaining changes: (\S+)""")
    val match = regex.find(stderr) ?: return null
    val idStr = match.groupValues[1]
    return ChangeId(idStr, idStr)
}

/**
 * Split action. Loads changes on a background thread, opens a dialog to configure
 * file selection, hunk selection, descriptions, and options, then executes `jj split`.
 *
 * - If no file has partial hunk selection: uses the proven file-level `jj split` path
 *   (passes file paths; same as before the redesign).
 * - If any file has partial hunk selection: builds a staging tree with the pre-computed
 *   first-commit content and drives `jj split --tool` via the diff-editor protocol.
 *
 * If the child description differs from the original, chains a `jj describe` for the child.
 * After splitting, selects the original change ID.
 */
fun splitAction(
    project: Project,
    entry: LogEntry?
) = nullAndDumbAwareAction(entry, "log.action.split", JujutsuIcons.Split) {
    runInBackground {
        val changes = ChangeService.loadChanges(target)

        runLater {
            val dialog = SplitDialog(project, target, changes)
            if (!dialog.showAndGet()) return@runLater

            val spec = dialog.result ?: return@runLater
            executeSplit(project, target, spec)
        }
    }
}

internal fun executeSplit(project: Project, target: LogEntry, spec: SplitSpec) {
    val hunkSelection = spec.hunkSelection

    if (hunkSelection == null) {
        // Fast path: file-level split (no partial hunk selection).
        executeSplitFilePaths(project, target, spec)
    } else {
        // Hunk-level path: build staging tree, run diff-editor-driven split.
        executeSplitInteractive(project, target, spec, hunkSelection)
    }
}

/** File-level split — unchanged from pre-hunk-level implementation. */
private fun executeSplitFilePaths(project: Project, target: LogEntry, spec: SplitSpec) {
    runInBackground {
        val result = target.repo.commandExecutor.split(
            spec.revision,
            spec.filePaths,
            spec.selectedDescription,
            spec.parallel,
            spec.insertBefore
        )

        runLater {
            if (!result.isSuccess) {
                result.tellUser(project, "log.action.split.error")
                return@runLater
            }
            onSplitSuccess(project, target, spec, result.stderr)
        }
    }
}

/** Hunk-level split via jj's diff-editor protocol. */
private fun executeSplitInteractive(
    project: Project,
    target: LogEntry,
    spec: SplitSpec,
    hunkSelection: `in`.kkkev.jjidea.ui.split.SplitHunkSelection
) {
    runInBackground {
        val perFileContent = hunkSelection.buildPerFileContent().toMutableMap()

        // fileDataCache in the dialog is populated lazily (only when the user clicks a file
        // to preview it). Fully-included files the user never clicked have null content in
        // perFileContent, which mirrorTree would interpret as "excluded" (restored to base).
        // Fetch after-content for those files now, before building the staging tree.
        val root = target.repo.directory
        for (fp in spec.filePaths) {
            val relPath = fp.relativeTo(root)
            if (perFileContent[relPath] == null) {
                val result = target.repo.commandExecutor.show(fp, spec.revision)
                if (result.isSuccess) {
                    perFileContent[relPath] = result.stdout
                } else {
                    splitLog.warn("Could not fetch after-content for $relPath; file may be missing from first commit")
                }
            }
        }

        val stagingDir = DiffEditTool.buildStagingTree(perFileContent)
        try {
            val configArgs = DiffEditTool.diffEditConfigArgs(DiffEditTool.TOOL_NAME, stagingDir)

            val result = target.repo.commandExecutor.splitInteractive(
                revision = spec.revision,
                description = spec.selectedDescription,
                parallel = spec.parallel,
                configArgs = configArgs,
                tool = DiffEditTool.TOOL_NAME,
                insertBefore = spec.insertBefore
            )

            runLater {
                if (!result.isSuccess) {
                    result.tellUser(project, "log.action.split.error")
                    return@runLater
                }
                onSplitSuccess(project, target, spec, result.stderr)
            }
        } finally {
            stagingDir.toFile().deleteRecursively()
            splitLog.debug("Cleaned up staging dir $stagingDir")
        }
    }
}

private fun onSplitSuccess(project: Project, target: LogEntry, spec: SplitSpec, stderr: String) {
    val remainingDesc = spec.remainingDescription
    if (remainingDesc == null) {
        target.repo.invalidate(select = target.id, vfsChanged = true)
        splitLog.info("Split ${target.id}")
        return
    }

    // newParent (-B) mode: the remaining side never moves off target.id (verified against real
    // jj - see jj-idea-tkog), so its ID is already known and doesn't need parsing from stderr.
    // Default mode: the remaining side is a genuinely new commit whose ID only stderr reveals.
    val remainingId = if (spec.insertBefore != null) target.id else parseRemainingChangeId(stderr)

    if (remainingId != null) {
        target.repo.commandExecutor
            .createCommand { describe(remainingDesc, remainingId) }
            .onSuccess {
                target.repo.invalidate(select = target.id, vfsChanged = true)
                splitLog.info("Split ${target.id} and described remaining $remainingId")
            }
            .onFailure { tellUser(project, "log.action.split.error") }
            .executeAsync()
    } else {
        splitLog.warn("Could not parse remaining change ID from split output: $stderr")
        target.repo.invalidate(select = target.id, vfsChanged = true)
    }
}
