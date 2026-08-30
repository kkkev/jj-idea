package `in`.kkkev.jjidea.actions.change

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.filechange.buildChanges
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.ContentLocator
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.ui.common.JujutsuCompareChangesPanel.Companion.showCompareChangesTab
import `in`.kkkev.jjidea.ui.components.Filter
import `in`.kkkev.jjidea.ui.components.RevisionSelectorPopup
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

/**
 * Compare a whole changeset with an arbitrary user-picked revision.
 *
 * Generalizes [compareWithWorkingCopyAction] by letting the "other" side be chosen via
 * [RevisionSelectorPopup] rather than being fixed to the working copy. Available on any single
 * selection, including the working copy row itself (unlike "Compare with Working Copy", `@` vs an
 * arbitrary commit is meaningful).
 *
 * The selected commit is always the LEFT/base side and the picked revision the RIGHT side -
 * matching "Compare with Working Copy" (which sits right above this in the menu) and
 * [in.kkkev.jjidea.actions.filechange.CompareBeforeWithBranchAction]'s convention.
 */
fun compareWithRevisionAction(project: Project, logEntry: LogEntry?) =
    nullAndDumbAwareAction(logEntry, "log.action.compare.with.revision", AllIcons.Actions.Diff) {
        val repo = target.repo
        RevisionSelectorPopup.show(
            "log.action.compare.with.revision.popup.title",
            repo,
            Filter(true, true)
        ) { chosen ->
            showRevisionComparison(project, repo, target.id, chosen)
        }
    }

/**
 * Compare the parent of a changeset (see [LogEntry.parentContentLocator]) with an arbitrary
 * user-picked revision. Disabled for root changes (no parents) by the caller.
 */
fun compareBeforeWithRevisionAction(project: Project, logEntry: LogEntry?) =
    nullAndDumbAwareAction(logEntry, "log.action.compare.before.with.revision", AllIcons.Actions.Diff) {
        val repo = target.repo
        RevisionSelectorPopup.show(
            "log.action.compare.before.with.revision.popup.title",
            repo,
            Filter(true, true)
        ) { chosen ->
            showRevisionComparison(project, repo, target.parentContentLocator, chosen)
        }
    }

/**
 * Resolve [chosen] to a [in.kkkev.jjidea.jj.ChangeId] and open a Changes-pane tab comparing [base]
 * (left) with it (right), or show a "no differences" notification if they're identical. Shared by
 * [compareWithRevisionAction] and [compareBeforeWithRevisionAction].
 */
private fun showRevisionComparison(project: Project, repo: JujutsuRepository, base: ContentLocator, chosen: Revision) {
    runInBackground {
        // Locate change id in case revision is a bookmark or other expression
        val chosenChangeId = runCatching { repo.getLogEntry(chosen).id }.getOrNull()
        if (chosenChangeId == null) {
            runLater {
                Messages.showErrorDialog(
                    repo.project,
                    JujutsuBundle.message("action.compare.branch.resolve.error.message", chosen.toString()),
                    JujutsuBundle.message("action.compare.branch.resolve.error.title")
                )
            }
            return@runInBackground
        }

        val baseLabel = base.title
        val chosenLabel = chosen.toString()
        val changes = repo.logService.getFileChangesBetween(base, chosenChangeId).getOrNull().orEmpty()
        if (changes.isEmpty()) {
            runLater {
                JujutsuNotifications.notify(
                    project,
                    JujutsuBundle.message("log.action.compare.with.revision.empty.title"),
                    JujutsuBundle.message("log.action.compare.with.revision.empty.message", baseLabel, chosenLabel),
                    NotificationType.INFORMATION
                )
            }
        } else {
            // buildChanges runs jj commands synchronously (show/log), so it must stay off the
            // EDT — only the resulting UI-facing showCompareChangesTab call is dispatched via runLater.
            val diffChanges = buildChanges(project, changes, emptyList())
            runLater {
                showCompareChangesTab(
                    project,
                    diffChanges,
                    JujutsuBundle.message("diff.tab.compare.revisions", baseLabel, chosenLabel)
                ) { baseLabel }
            }
        }
    }
}
