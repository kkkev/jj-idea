package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeService
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.split.SplitDialog
import `in`.kkkev.jjidea.ui.split.SplitSpec
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

private val splitLog = Logger.getInstance("in.kkkev.jjidea.actions.change.splitAction")

/**
 * Parse the child (remaining) change ID from `jj split` stderr.
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
 * dual file trees, descriptions, and options, then executes `jj split`.
 *
 * If the child description differs from the original, chains a `jj describe` for the child.
 * The entry must be mutable. After splitting, selects the original change ID.
 */
fun splitAction(
    project: Project,
    entry: LogEntry?,
    allEntries: List<LogEntry> = emptyList()
) = nullAndDumbAwareAction(entry, "log.action.split", JujutsuIcons.Split) {
    runInBackground {
        val changes = ChangeService.loadChanges(target)

        runLater {
            val dialog = SplitDialog(project, target, changes, allEntries)
            if (!dialog.showAndGet()) return@runLater

            val spec = dialog.result ?: return@runLater
            executeSplit(project, target, spec)
        }
    }
}

internal fun executeSplit(project: Project, target: LogEntry, spec: SplitSpec) {
    runInBackground {
        val result = target.repo.commandExecutor.split(
            spec.revision,
            spec.filePaths,
            spec.description,
            spec.parallel
        )

        runLater {
            if (!result.isSuccess) {
                result.tellUser(project, "log.action.split.error")
                return@runLater
            }

            val childDesc = spec.childDescription
            if (childDesc != null) {
                val childId = parseRemainingChangeId(result.stderr)
                if (childId != null) {
                    target.repo.commandExecutor
                        .createCommand { describe(childDesc, childId) }
                        .onSuccess {
                            target.repo.invalidate(select = target.id, vfsChanged = true)
                            splitLog.info("Split ${target.id} and described child $childId")
                        }
                        .onFailure { tellUser(project, "log.action.split.error") }
                        .executeAsync()
                } else {
                    splitLog.warn(
                        "Could not parse child change ID from split output: ${result.stderr}"
                    )
                    target.repo.invalidate(select = target.id, vfsChanged = true)
                }
            } else {
                target.repo.invalidate(select = target.id, vfsChanged = true)
                splitLog.info("Split ${target.id}")
            }
        }
    }
}
