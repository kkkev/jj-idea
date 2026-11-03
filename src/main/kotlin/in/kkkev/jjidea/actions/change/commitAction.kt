package `in`.kkkev.jjidea.actions.change

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.actions.saveDescriptionToHistory
import `in`.kkkev.jjidea.diffedit.DiffEditTool
import `in`.kkkev.jjidea.jj.ChangeService
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.jj.runRecoverableInBackground
import `in`.kkkev.jjidea.jj.tellUser
import `in`.kkkev.jjidea.ui.commit.CommitDialog
import `in`.kkkev.jjidea.ui.commit.CommitSpec
import `in`.kkkev.jjidea.ui.common.HunkSelection
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.relativeTo

private val commitLog = Logger.getInstance("in.kkkev.jjidea.actions.change.commitAction")

/**
 * Commit action (GitHub #157). Loads changes on a background thread, opens a dialog to configure
 * file selection, hunk selection and the description, then executes `jj commit`.
 *
 * - If no file has partial hunk selection: uses the file-level `jj commit` path (passes file paths).
 * - If any file has partial hunk selection: builds a staging tree with the pre-computed
 *   committed content and drives `jj commit --tool` via the diff-editor protocol.
 *
 * Ticked files stay in [entry] (which must be the working copy - `jj commit` has no `-r`), and
 * everything else moves to a new working-copy change. After committing, selects [entry].
 *
 * The dialog starts with [preSelectedFiles] ticked, or with every file ticked when null or empty.
 */
fun commitAction(
    project: Project,
    entry: LogEntry?,
    preSelectedFiles: Set<FilePath>? = null
) = nullAndDumbAwareAction(
    entry,
    "log.action.commit",
    AllIcons.Actions.Commit
) {
    fun openDialog() {
        target.repo.runRecoverableInBackground(retry = ::openDialog) {
            val changes = ChangeService.loadChanges(target)

            runLater {
                val selected = preSelectedFiles?.takeIf { it.isNotEmpty() } ?: changes.map { it.filePath }.toSet()
                val dialog = CommitDialog(project, target, changes, preSelectedFiles = selected)
                if (!dialog.showAndGet()) return@runLater

                val spec = dialog.result ?: return@runLater
                executeCommit(project, target, spec)
            }
        }
    }
    openDialog()
}

internal fun executeCommit(project: Project, target: LogEntry, spec: CommitSpec) {
    val hunkSelection = spec.hunkSelection

    if (hunkSelection == null) {
        // Fast path: file-level commit (no partial hunk selection).
        executeCommitFilePaths(project, target, spec)
    } else {
        // Hunk-level path: build staging tree, run diff-editor-driven commit.
        executeCommitInteractive(project, target, spec, hunkSelection)
    }
}

/** File-level commit. */
private fun executeCommitFilePaths(project: Project, target: LogEntry, spec: CommitSpec) {
    runInBackground {
        val result = target.repo.commandExecutor.commit(
            spec.filePaths,
            spec.description
        )

        runLater {
            if (result !is CommandExecutor.CommandResult.Success) {
                result.tellUser(target.repo, "log.action.commit.error")
                return@runLater
            }
            onCommitSuccess(project, target, spec)
        }
    }
}

/** Hunk-level commit via jj's diff-editor protocol. */
private fun executeCommitInteractive(
    project: Project,
    target: LogEntry,
    spec: CommitSpec,
    hunkSelection: HunkSelection
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
                val result = target.repo.commandExecutor.show(fp, target.id)
                if (result is CommandExecutor.CommandResult.Success) {
                    perFileContent[relPath] = result.stdout
                } else {
                    commitLog.warn("Could not fetch after-content for $relPath; file may be missing from the commit")
                }
            }
        }

        val result = DiffEditTool.withStagingTree(perFileContent, hunkSelection.deletedPaths) { configArgs, tool ->
            target.repo.commandExecutor.commitInteractive(
                description = spec.description,
                configArgs = configArgs,
                tool = tool
            )
        }

        runLater {
            if (result !is CommandExecutor.CommandResult.Success) {
                result.tellUser(target.repo, "log.action.commit.error")
                return@runLater
            }
            onCommitSuccess(project, target, spec)
        }
    }
}

private fun onCommitSuccess(project: Project, target: LogEntry, spec: CommitSpec) {
    project.saveDescriptionToHistory(spec.description)
    // The committed side keeps target.id; the remaining changes are in a new working copy.
    target.repo.invalidate(select = target.id, vfsChanged = true)
    commitLog.info("Committed ${target.id}")
}
