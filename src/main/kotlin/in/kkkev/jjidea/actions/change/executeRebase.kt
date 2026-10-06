package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.diagnostic.Logger
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.createCommand
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.rebase.RebaseSpec

private val rebaseLog = Logger.getInstance("in.kkkev.jjidea.actions.change.executeRebase")

/**
 * Runs `jj rebase` for [spec] with undo tracking and an undo balloon on success. Shared by
 * [RebaseChangeAction] (dialog path), [in.kkkev.jjidea.ui.dnd.DropPerformers] (drag-and-drop path,
 * jj-idea-8fxs), and [in.kkkev.jjidea.actions.change.MoveChangeAction] (Move Up/Down,
 * jj-idea-owje) - one wiring path for all of them means the dialog action also gains the undo
 * balloon it didn't have before, which is accepted rather than adding an opt-in flag to avoid
 * that. [undoLabelKey] lets Move Up/Down show "Move" rather than "Rebase" in the undo balloon,
 * even though the underlying command - and its error message - are still a plain rebase.
 */
internal fun executeRebase(repo: JujutsuRepository, spec: RebaseSpec, undoLabelKey: String = "log.action.rebase.undo") {
    repo.createCommand { rebase(spec.revisions, spec.destinations, spec.sourceMode, spec.destinationMode) }
        .onSuccess {
            invalidate(select = spec.revisions.first(), vfsChanged = true)
            rebaseLog.info("Rebased ${spec.revisions} onto ${spec.destinations}")
        }
        .onFailure { tellUser("log.action.rebase.error") }
        .addUndoTracking(undoLabelKey)
        .executeAsync()
}
