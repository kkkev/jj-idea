package `in`.kkkev.jjidea.actions.filechange

import com.intellij.diff.DiffManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.changes
import `in`.kkkev.jjidea.actions.files
import `in`.kkkev.jjidea.actions.repoForFile
import `in`.kkkev.jjidea.jj.diffRequest
import `in`.kkkev.jjidea.jj.fileAt
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.fileAtVersion
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.jujutsuRepositoryFor
import `in`.kkkev.jjidea.vcs.possibleLogEntryFor

/**
 * Compare file(s) from the parent of a revision with the revision itself.
 *
 * This action is for a change from historical log context or working copy.
 *
 * Visibility:
 * - Hidden when the entry has no parents
 *
 * Enabled:
 * - When a change is selected
 */
class ShowDiffAction :
    DumbAwareAction(
        JujutsuBundle.message("action.show.diff"),
        JujutsuBundle.message("action.show.diff.description"),
        AllIcons.Actions.Diff
    ) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        runInBackground {
            val changes = e.changes
            val requests = if (changes.isNotEmpty()) {
                changes.map { change ->
                    val repo = project.jujutsuRepositoryFor(change.filePath)
                    diffRequest(
                        change.filePath.name,
                        repo.createDiffSideFor(change.before),
                        repo.createDiffSideFor(change.after)
                    )
                }
            } else {
                val files = e.files
                if (files.isNotEmpty()) {
                    val filesByLogEntry = files.groupBy { file ->
                        project.possibleLogEntryFor(file) ?: project.jujutsuRepositoryFor(file).workingCopy
                    }
                    val changesByLogEntry = filesByLogEntry.keys.associateWith { entry ->
                        entry.repo.logService.getFileChanges(entry).getOrNull()
                            ?.filter { it.after != null }
                            ?.associateBy { it.after!!.filePath }
                            ?: emptyMap()
                    }
                    filesByLogEntry.flatMap { (logEntry, groupFiles) ->
                        val changesByPath = changesByLogEntry[logEntry] ?: emptyMap()
                        groupFiles.map { file ->
                            val change = changesByPath[file.filePath]
                            val before = change?.before ?: file.filePath.fileAt(logEntry.parentContentLocator)
                            val after = change?.after ?: file.fileAtVersion
                            val repo = logEntry.repo
                            diffRequest(file.name, repo.createDiffSideFor(before), repo.createDiffSideFor(after))
                        }
                    }
                } else {
                    emptyList()
                }
            }

            if (requests.isNotEmpty()) {
                val diffManager = DiffManager.getInstance()
                runLater { requests.forEach { diffManager.showDiff(project, it) } }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        // Show in changes context OR when in a jj project with a file selected
        e.presentation.isEnabledAndVisible = e.changes.isNotEmpty() || e.repoForFile != null
    }
}
