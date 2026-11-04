package `in`.kkkev.jjidea.jj

import `in`.kkkev.jjidea.jj.cli.OP_LOG_TEMPLATE
import `in`.kkkev.jjidea.jj.cli.UndoRedoPlan
import `in`.kkkev.jjidea.jj.cli.parseOpLog
import `in`.kkkev.jjidea.jj.cli.resolveRedo
import `in`.kkkev.jjidea.jj.cli.resolveUndo

/**
 * How many operations to read when working out the next undo/redo. Naming an undo or redo means
 * following its `restore to` links back to a normal operation, so the window must cover a chain of
 * consecutive undos; 50 is far beyond what anyone chains, and still a single cheap read.
 */
internal const val UNDO_REDO_WINDOW = 50

/** What the next Undo and Redo of one repository would do, cached by [JujutsuStateModel.undoRedoPlans]. */
internal data class UndoRedoPlans(val undo: UndoRedoPlan, val redo: UndoRedoPlan) {
    companion object {
        val NOTHING = UndoRedoPlans(
            UndoRedoPlan.Unavailable(UndoRedoPlan.Reason.NOTHING),
            UndoRedoPlan.Unavailable(UndoRedoPlan.Reason.NOTHING)
        )
    }
}

internal fun plansFor(entries: List<OperationEntry>) = UndoRedoPlans(resolveUndo(entries), resolveRedo(entries))

/**
 * Reads the head of the operation log with one `jj op log -n[UNDO_REDO_WINDOW]` (it passes
 * `--ignore-working-copy`, so it never snapshots) and works out both plans from it. When [repoPath] is
 * given, also records the head operation in [OpHeadIds] so an active poll or auto-fetch never re-triggers
 * a refresh for an operation this refresh already covered. A failed read degrades to [UndoRedoPlans.NOTHING]:
 * disabled actions, never a guess.
 */
internal fun loadUndoRedoPlans(executor: CommandExecutor, repoPath: String? = null): UndoRedoPlans {
    val result = executor.opLog(UNDO_REDO_WINDOW, OP_LOG_TEMPLATE)
    if (result !is CommandExecutor.CommandResult.Success) return UndoRedoPlans.NOTHING
    val entries = parseOpLog(result.stdout)
    if (repoPath != null) {
        entries.firstOrNull()?.let { OpHeadIds.record(repoPath, it.id.toString()) }
    }
    return plansFor(entries)
}
