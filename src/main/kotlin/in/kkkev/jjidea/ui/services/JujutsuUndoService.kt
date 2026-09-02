package `in`.kkkev.jjidea.ui.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.OperationId
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-repo record of the most recent still-unreverted plugin-issued operation, so a dismissed
 * undo balloon isn't the only chance to undo it - [in.kkkev.jjidea.actions.undo.UndoLastOperationAction]
 * reads this to offer a persistent "Undo <last action>" affordance. Session-scoped, not
 * persisted: the durable, browsable story is the Stage 3 op-log browser (jj-idea-aii0).
 */
@Service(Service.Level.PROJECT)
class JujutsuUndoService {
    data class Record(val operation: OperationId, val label: String)

    private val records = ConcurrentHashMap<JujutsuRepository, Record>()

    /** Records [operation] as the last undoable action in [repo], replacing any earlier one. */
    fun record(repo: JujutsuRepository, operation: OperationId, label: String) {
        records[repo] = Record(operation, label)
    }

    /**
     * The single pending record, or `null` when there is none, or when more than one repo has a
     * pending record - [UndoLastOperationAction] has no repo picker, so an ambiguous case is
     * treated the same as "nothing to undo" rather than guessing which repo the user means.
     */
    fun current(): Pair<JujutsuRepository, Record>? = records.entries.singleOrNull()?.toPair()

    /**
     * Clears [repo]'s record, but only if it still points at [operation] - an older, specific
     * revert (e.g. from a stale balloon) must not clobber a newer record for the same repo.
     */
    fun clearIfCurrent(repo: JujutsuRepository, operation: OperationId) {
        records.computeIfPresent(repo) { _, existing -> existing.takeUnless { it.operation == operation } }
    }

    companion object {
        fun getInstance(project: Project): JujutsuUndoService = project.getService(JujutsuUndoService::class.java)
    }
}
