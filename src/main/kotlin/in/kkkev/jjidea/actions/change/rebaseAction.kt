package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.createUndoTrackedCommand
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.rebase.RebaseDialog
import `in`.kkkev.jjidea.ui.rebase.RebaseSpec
import `in`.kkkev.jjidea.ui.services.withUndoBalloon

private val rebaseLog = Logger.getInstance("in.kkkev.jjidea.actions.change.rebaseAction")

/**
 * Rebase action. Opens a dialog to configure source mode, destination, and placement,
 * then executes `jj rebase` with the chosen parameters.
 */
fun rebaseAction(
    project: Project,
    repo: JujutsuRepository?,
    entries: List<LogEntry>
) =
    nullAndDumbAwareAction(repo, "log.action.rebase", JujutsuIcons.Rebase) {
        performRebase(project, target, entries)
    }

/**
 * Shared implementation behind [rebaseAction] (context-menu factory, fixed target/entries) and
 * [RebaseChangeAction] (toolbar, reads its target dynamically from the log selection) - both
 * open the same rebase dialog and run the same `jj rebase`.
 */
internal fun performRebase(project: Project, repo: JujutsuRepository, entries: List<LogEntry>) {
    val dialog = RebaseDialog(project, repo, entries)
    if (!dialog.showAndGet()) return

    val spec = dialog.result ?: return
    executeRebase(project, repo, spec)
}

/**
 * Runs `jj rebase` for [spec] with undo tracking and an undo balloon on success. Shared by
 * [performRebase] (dialog path) and [in.kkkev.jjidea.ui.dnd.DropPerformers] (drag-and-drop path,
 * jj-idea-8fxs) - one wiring path for both means the dialog action also gains the undo balloon it
 * didn't have before, which is accepted rather than adding an opt-in flag to avoid that.
 */
internal fun executeRebase(project: Project, repo: JujutsuRepository, spec: RebaseSpec) {
    repo.createUndoTrackedCommand { rebase(spec.revisions, spec.destinations, spec.sourceMode, spec.destinationMode) }
        .onSuccess {
            repo.invalidate(select = spec.revisions.first(), vfsChanged = true)
            rebaseLog.info("Rebased ${spec.revisions} onto ${spec.destinations}")
        }
        .onFailure { tellUser(project, "log.action.rebase.error") }
        .withUndoBalloon(project, repo, "log.action.rebase.undo")
        .executeAsync()
}
