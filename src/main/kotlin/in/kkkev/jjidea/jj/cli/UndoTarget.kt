package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.OperationEntry
import `in`.kkkev.jjidea.jj.OperationId

/**
 * What `jj undo` / `jj redo` would do next, derived from the head of the operation log. Pure, so
 * the action's label never costs a subprocess and is testable without jj.
 *
 * The rules were established empirically on jj 0.37, 0.39, 0.44 and 0.45 (see
 * docs/design/undo-support-roadmap.md, Stage 2) and are cross-checked against real jj by
 * `UndoRedoContractTest`:
 * - `jj undo` after a normal op restores that op's parent; after an undo it keeps walking back
 *   (it undoes the op the undo restored *to*); after a redo it undoes what the redo reinstated.
 * - A snapshot op at the head is the trap for *naming*: `jj undo` takes its own snapshot first,
 *   so with uncommitted edits it undoes that snapshot - removing its content from disk - rather
 *   than the user's last real operation. A snapshot already at the head (e.g. IDEA writing
 *   `.idea` files into an unignored repo) behaves the same. It is a legitimate undo target and is
 *   named as such (the label says so); Redo brings the files back. A snapshot on top of an undo
 *   ends the redo chain (`jj redo` reports "Nothing to redo"), so Redo reports
 *   [Reason.WORKING_COPY_CHANGED] there.
 */
internal sealed interface UndoRedoPlan {
    /** [labelOp] is the operation whose effect is undone/reinstated; jj will restore to [restoresTo]. */
    data class Named(val labelOp: OperationEntry, val restoresTo: OperationId) : UndoRedoPlan

    /** Something undoable exists but the chain leaves the loaded window, so it cannot be named. */
    data object Unnamed : UndoRedoPlan

    data class Unavailable(val reason: Reason) : UndoRedoPlan

    enum class Reason {
        /** The log has nothing (more) to undo, or nothing to redo. */
        NOTHING,

        /** The head is a working-copy snapshot, which ends the redo chain. */
        WORKING_COPY_CHANGED
    }
}

private const val UNDO_PREFIX = "undo: restore to operation "
private const val REDO_PREFIX = "redo: restore to operation "

internal fun isUndoOperation(entry: OperationEntry) = entry.firstLine().startsWith(UNDO_PREFIX)

internal fun isRedoOperation(entry: OperationEntry) = entry.firstLine().startsWith(REDO_PREFIX)

/** The operation an undo/redo op restored to, or `null` for any other operation. */
internal fun restoreTarget(entry: OperationEntry): OperationId? {
    val line = entry.firstLine()
    val prefix = listOf(UNDO_PREFIX, REDO_PREFIX).firstOrNull { line.startsWith(it) } ?: return null
    return line.removePrefix(prefix).trim().takeIf { it.isNotEmpty() }?.let(::OperationId)
}

private fun OperationEntry.firstLine() = description.lineSequence().firstOrNull().orEmpty()

/**
 * Follows undo/redo ops to the operation whose effect they stand for: an undo of B restores to
 * B's parent, so "the current state" is that parent; a redo reinstates what its restore point
 * stands for. Returns `null` when the chain leaves [byId] (window too small).
 */
private fun effectiveOperation(start: OperationEntry, byId: Map<OperationId, OperationEntry>): OperationEntry? {
    var current = start
    // The chain is a DAG walking strictly towards older ops, but bound it anyway: a corrupted log
    // must degrade to "unnamed", never loop.
    repeat(byId.size + 1) {
        val target = restoreTarget(current) ?: return current
        current = byId[target] ?: return null
    }
    return null
}

/** The plan for the next `jj undo`, given [entries] newest-first (as [parseOpLog] returns them). */
internal fun resolveUndo(entries: List<OperationEntry>): UndoRedoPlan {
    val head = entries.firstOrNull() ?: return UndoRedoPlan.Unavailable(UndoRedoPlan.Reason.NOTHING)
    if (head.isRoot) return UndoRedoPlan.Unavailable(UndoRedoPlan.Reason.NOTHING)

    val byId = entries.associateBy { it.id }
    // An undo head means "keep walking back": the op to undo is the one it restored *to*, not the
    // undo itself (which would be a redo). Redo and normal heads are undone directly.
    val subject = if (isUndoOperation(head)) {
        restoreTarget(head)?.let(byId::get) ?: return UndoRedoPlan.Unnamed
    } else {
        head
    }
    val labelOp = effectiveOperation(subject, byId) ?: return UndoRedoPlan.Unnamed
    val parent = labelOp.parents.firstOrNull()?.let(byId::get) ?: return UndoRedoPlan.Unnamed
    // Undoing the very first operation would restore the empty repo and detach the workspace.
    if (labelOp.isRoot || parent.isRoot) return UndoRedoPlan.Unavailable(UndoRedoPlan.Reason.NOTHING)
    return UndoRedoPlan.Named(labelOp, parent.id)
}

/** The plan for the next `jj redo`, given [entries] newest-first. */
internal fun resolveRedo(entries: List<OperationEntry>): UndoRedoPlan {
    val head = entries.firstOrNull() ?: return UndoRedoPlan.Unavailable(UndoRedoPlan.Reason.NOTHING)
    if (head.isRoot) return UndoRedoPlan.Unavailable(UndoRedoPlan.Reason.NOTHING)
    if (head.isSnapshot) return UndoRedoPlan.Unavailable(UndoRedoPlan.Reason.WORKING_COPY_CHANGED)

    val byId = entries.associateBy { it.id }
    // Redo reverses the last undo: after an undo it restores to that undo's parent; after a
    // redo it continues only while the redo's own restore point was itself an undo.
    val undone = when {
        isUndoOperation(head) -> head
        isRedoOperation(head) -> restoreTarget(head)?.let(byId::get)?.takeIf(::isUndoOperation)
        else -> null
    } ?: return UndoRedoPlan.Unavailable(UndoRedoPlan.Reason.NOTHING)

    val restoresTo = undone.parents.firstOrNull()?.let(byId::get) ?: return UndoRedoPlan.Unnamed
    val labelOp = effectiveOperation(restoresTo, byId) ?: return UndoRedoPlan.Unnamed
    return UndoRedoPlan.Named(labelOp, restoresTo.id)
}

/** How a `jj undo`/`jj redo` actually went, judged from the operations it wrote. */
internal enum class UndoRedoOutcome {
    /** Exactly one new undo/redo operation: jj did what the plan said. */
    DONE,

    /**
     * A working-copy snapshot was written first and then undone: edits made after the plan was
     * resolved got in the way, so the repository is effectively unchanged. Needs a retry.
     */
    SNAPSHOT_ONLY,

    /** Anything else (a concurrent terminal operation, a window too small to tell): say so, don't claim success. */
    UNEXPECTED
}

/**
 * Classifies a finished undo/redo from [entries] (newest-first) and the head the click started
 * from. Same information a per-invocation token would give - which operations *this* click wrote -
 * without the token machinery, because "everything newer than the head we recorded" is exact for
 * a command we ran ourselves.
 */
internal fun classifyUndoRedoOutcome(entries: List<OperationEntry>, headBefore: OperationId): UndoRedoOutcome {
    if (entries.none { it.id == headBefore }) return UndoRedoOutcome.UNEXPECTED
    val written = entries.takeWhile { it.id != headBefore }
    val newest = written.firstOrNull() ?: return UndoRedoOutcome.UNEXPECTED
    if (!isUndoOperation(newest) && !isRedoOperation(newest)) return UndoRedoOutcome.UNEXPECTED
    return when {
        written.size == 1 -> UndoRedoOutcome.DONE
        written.size == 2 && written[1].isSnapshot -> UndoRedoOutcome.SNAPSHOT_ONLY
        else -> UndoRedoOutcome.UNEXPECTED
    }
}
