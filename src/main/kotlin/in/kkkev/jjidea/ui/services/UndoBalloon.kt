package `in`.kkkev.jjidea.ui.services

import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.CommandExecutor.Command.WithRepo
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.OperationId
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.createCommand
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.util.runLater

/**
 * Attaches an undo balloon to a [WithRepo]: when the wrapped action succeeds with
 * a [CommandExecutor.CommandResult.Success.Reversible] result, records it in [JujutsuUndoService]
 * and shows a balloon with an inline Undo link, via [notify] (defaults to
 * [JujutsuNotifications.notifyUndoable]) - the injected-notifier seam used elsewhere (e.g.
 * `loadWorkingCopies`) so tests can assert without the platform.
 *
 * Wraps [WithRepo.action] rather than consuming `onSuccess`/`onSuccessResult`:
 * those are `copy`-based single slots, and every mutating action already uses `onSuccess` for its
 * own `repo.invalidate(...)` - consuming it here would silently clobber the caller's callback.
 */
fun WithRepo.withUndoBalloon(
    labelKey: String,
    notify: (JujutsuRepository, OperationId, String) -> Unit = JujutsuNotifications::notifyUndoable
): WithRepo {
    val originalAction = action
    return copy(
        action = {
            originalAction().also { result ->
                (result as? CommandExecutor.CommandResult.Success.Reversible)?.let { reversible ->
                    val label = JujutsuBundle.message(labelKey)
                    JujutsuUndoService.getInstance(repo.project).record(repo, reversible.operation, label)
                    runLater { notify(repo, reversible.operation, label) }
                }
            }
        }
    )
}

/**
 * Runs `jj op revert` on [operation] and, on success, refreshes [repo] and clears its
 * [JujutsuUndoService] record (only if it still points at this same operation - see
 * [JujutsuUndoService.clearIfCurrent]). Shared by the balloon's inline Undo link and
 * [in.kkkev.jjidea.actions.undo.UndoLastOperationAction] so both go through one path.
 */
fun performUndo(project: Project, repo: JujutsuRepository, operation: OperationId) {
    repo.createCommand { opRevert(operation) }
        .onSuccess {
            invalidate(select = WorkingCopy, vfsChanged = true)
            JujutsuUndoService.getInstance(project).clearIfCurrent(this, operation)
        }
        .onFailure { tellUser("notification.undo.error") }
        .executeAsync()
}
