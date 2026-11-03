package `in`.kkkev.jjidea.ui.oplog

import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.OperationEntry

/**
 * What the registered `Jujutsu.Oplog.*` actions need from the Operations tab beyond the selected row, published as
 * [in.kkkev.jjidea.actions.JujutsuDataKeys.OPERATION_LOG] by the panel (jj-idea-jpfx.2).
 */
interface OperationLogView {
    /** The repository's current head operation (including snapshot operations), if loaded. */
    fun head(repo: JujutsuRepository): OperationEntry?

    /** How many operations came after [row]'s in its repository (what Restore would discard). */
    fun laterCount(row: OperationRow): Int

    fun refresh()

    fun goToHead()

    fun goToHeadState(): RowActionState
}
