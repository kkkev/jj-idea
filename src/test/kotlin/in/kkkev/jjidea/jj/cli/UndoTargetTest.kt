package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.OperationEntry
import `in`.kkkev.jjidea.jj.OperationId
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

/**
 * Pure tests for [resolveUndo]/[resolveRedo]. The fixtures reproduce the op-log shapes observed
 * with real jj 0.37-0.45 (docs/design/undo-support-roadmap.md, Stage 2): an undo/redo op's
 * description is `undo|redo: restore to operation <id>`, repeated `jj undo` walks back through
 * the chain, and a dirty working copy makes the head a snapshot op that `jj undo` would undo.
 */
class UndoTargetTest {
    private fun op(
        id: String,
        parent: String?,
        description: String,
        snapshot: Boolean = false,
        root: Boolean = false
    ) = OperationEntry(
        id = OperationId(id),
        parents = listOfNotNull(parent).map(::OperationId),
        description = description,
        time = Instant.fromEpochSeconds(0),
        user = "t@t",
        isSnapshot = snapshot,
        isRoot = root,
        args = ""
    )

    private val root = op("root", null, "", root = true)
    private val workspace = op("ws", "root", "add workspace 'default'")
    private val a = op("a", "ws", "describe commit AAA")
    private val b = op("b", "a", "new empty commit")

    /** Newest-first, like [parseOpLog]. */
    private fun log(vararg oldestFirst: OperationEntry) = oldestFirst.reversed()

    private fun undoOf(id: String, parent: String, restoresTo: String) =
        op(id, parent, "undo: restore to operation $restoresTo")

    private fun redoOf(id: String, parent: String, restoresTo: String) =
        op(id, parent, "redo: restore to operation $restoresTo")

    private fun named(plan: UndoRedoPlan) = (plan as UndoRedoPlan.Named)

    // --- undo ---

    @Test
    fun `undo after a normal op targets that op and restores its parent`() {
        val plan = named(resolveUndo(log(root, workspace, a, b)))

        plan.labelOp.id shouldBe OperationId("b")
        plan.restoresTo shouldBe OperationId("a")
    }

    @Test
    fun `second undo walks further back through the undo chain`() {
        // A, B, undo (restore to a), then undo again must undo A, restoring ws.
        val plan = named(resolveUndo(log(root, workspace, a, b, undoOf("u1", "b", "a"))))

        plan.labelOp.id shouldBe OperationId("a")
        plan.restoresTo shouldBe OperationId("ws")
    }

    @Test
    fun `undo after a redo names the op the redo reinstated`() {
        // A, B, undo, undo, redo: the redo reinstated A (via the first undo's restore point),
        // so undoing it names A, not an opaque "redo".
        val u1 = undoOf("u1", "b", "a")
        val u2 = undoOf("u2", "u1", "ws")
        val r1 = redoOf("r1", "u2", "u1")

        val plan = named(resolveUndo(log(root, workspace, a, b, u1, u2, r1)))

        plan.labelOp.id shouldBe OperationId("a")
    }

    @Test
    fun `undo with a snapshot head targets the snapshot itself, not the op beneath it`() {
        // jj undo would undo the snapshot (removing its files from disk); Redo brings them back, so
        // it is a legitimate - and honestly labelled - target rather than something to block.
        val snapshot = op("s", "b", "snapshot working copy", snapshot = true)

        val plan = named(resolveUndo(log(root, workspace, a, b, snapshot)))

        plan.labelOp.id shouldBe OperationId("s")
        plan.labelOp.isSnapshot shouldBe true
        plan.restoresTo shouldBe OperationId("b")
    }

    @Test
    fun `a second undo after undoing the snapshot reaches the real operation`() {
        val snapshot = op("s", "b", "snapshot working copy", snapshot = true)
        val u1 = undoOf("u1", "s", "b")

        named(resolveUndo(log(root, workspace, a, b, snapshot, u1))).labelOp.id shouldBe OperationId("b")
    }

    @Test
    fun `undo at the root operation has nothing to undo`() {
        resolveUndo(log(root)) shouldBe UndoRedoPlan.Unavailable(UndoRedoPlan.Reason.NOTHING)
    }

    @Test
    fun `undo of the workspace-add operation is unavailable`() {
        // ws's parent is the root op: undoing it would restore the empty repo - jj allows it, but
        // that detaches the workspace and is never what a user means by "undo".
        resolveUndo(log(root, workspace)) shouldBe UndoRedoPlan.Unavailable(UndoRedoPlan.Reason.NOTHING)
    }

    @Test
    fun `an undo whose restore target left the loaded window falls back to unnamed`() {
        // Only the tail of the log is loaded: u1's target "a" is not in the window.
        val u1 = undoOf("u1", "b", "a")

        resolveUndo(listOf(u1, b)) shouldBe UndoRedoPlan.Unnamed
    }

    @Test
    fun `a plain op restore is a normal op not part of the undo chain`() {
        val restore = op("r", "b", "restore to operation a")

        named(resolveUndo(log(root, workspace, a, b, restore))).labelOp.id shouldBe OperationId("r")
    }

    // --- redo ---

    @Test
    fun `redo is unavailable after a normal op`() {
        resolveRedo(log(root, workspace, a, b)) shouldBe UndoRedoPlan.Unavailable(UndoRedoPlan.Reason.NOTHING)
    }

    @Test
    fun `redo after one undo reinstates the undone op`() {
        val plan = named(resolveRedo(log(root, workspace, a, b, undoOf("u1", "b", "a"))))

        plan.labelOp.id shouldBe OperationId("b")
        plan.restoresTo shouldBe OperationId("b")
    }

    @Test
    fun `redo after two undos reinstates the older undone op first`() {
        val u1 = undoOf("u1", "b", "a")
        val u2 = undoOf("u2", "u1", "ws")

        val plan = named(resolveRedo(log(root, workspace, a, b, u1, u2)))

        plan.labelOp.id shouldBe OperationId("a")
        plan.restoresTo shouldBe OperationId("u1")
    }

    @Test
    fun `second redo continues forward through the chain`() {
        val u1 = undoOf("u1", "b", "a")
        val u2 = undoOf("u2", "u1", "ws")
        val r1 = redoOf("r1", "u2", "u1")

        val plan = named(resolveRedo(log(root, workspace, a, b, u1, u2, r1)))

        plan.labelOp.id shouldBe OperationId("b")
        plan.restoresTo shouldBe OperationId("b")
    }

    @Test
    fun `redo is unavailable once the chain is fully redone`() {
        val u1 = undoOf("u1", "b", "a")
        val u2 = undoOf("u2", "u1", "ws")
        val r1 = redoOf("r1", "u2", "u1")
        val r2 = redoOf("r2", "r1", "b")

        resolveRedo(log(root, workspace, a, b, u1, u2, r1, r2)) shouldBe
            UndoRedoPlan.Unavailable(UndoRedoPlan.Reason.NOTHING)
    }

    @Test
    fun `redo is unavailable after a new op follows an undo`() {
        val u1 = undoOf("u1", "b", "a")
        val c = op("c", "u1", "new empty commit")

        resolveRedo(log(root, workspace, a, b, u1, c)) shouldBe
            UndoRedoPlan.Unavailable(UndoRedoPlan.Reason.NOTHING)
    }

    @Test
    fun `redo with a snapshot head is unavailable`() {
        val u1 = undoOf("u1", "b", "a")
        val snapshot = op("s", "u1", "snapshot working copy", snapshot = true)

        resolveRedo(log(root, workspace, a, b, u1, snapshot)) shouldBe
            UndoRedoPlan.Unavailable(UndoRedoPlan.Reason.WORKING_COPY_CHANGED)
    }

    @Test
    fun `descriptions are matched on the prefix with a full operation id`() {
        isUndoOperation(undoOf("u", "b", "abc123")) shouldBe true
        isRedoOperation(redoOf("r", "b", "abc123")) shouldBe true
        isUndoOperation(op("x", "b", "undo something else")) shouldBe false
        restoreTarget(undoOf("u", "b", "abc123")) shouldBe OperationId("abc123")
        restoreTarget(op("n", "b", "new empty commit")) shouldBe null
    }

    // --- outcome classification ---

    @Test
    fun `one new undo operation is a clean outcome`() {
        val u1 = undoOf("u1", "b", "a")

        classifyUndoRedoOutcome(log(root, workspace, a, b, u1), OperationId("b")) shouldBe UndoRedoOutcome.DONE
    }

    @Test
    fun `a snapshot followed by the undo means only the snapshot was undone`() {
        val snapshot = op("s", "b", "snapshot working copy", snapshot = true)
        val u1 = undoOf("u1", "s", "b")

        classifyUndoRedoOutcome(log(root, workspace, a, b, snapshot, u1), OperationId("b")) shouldBe
            UndoRedoOutcome.SNAPSHOT_ONLY
    }

    @Test
    fun `an unrelated or unlocatable outcome is never reported as done`() {
        val c = op("c", "b", "new empty commit")
        val u1 = undoOf("u1", "c", "b")

        classifyUndoRedoOutcome(log(root, workspace, a, b, c, u1), OperationId("b")) shouldBe UndoRedoOutcome.UNEXPECTED
        classifyUndoRedoOutcome(log(root, workspace, a, b), OperationId("b")) shouldBe UndoRedoOutcome.UNEXPECTED
        classifyUndoRedoOutcome(log(root, workspace, a, b, u1), OperationId("gone")) shouldBe
            UndoRedoOutcome.UNEXPECTED
    }
}
