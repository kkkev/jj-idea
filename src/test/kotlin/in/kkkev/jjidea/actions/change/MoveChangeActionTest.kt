package `in`.kkkev.jjidea.actions.change

import `in`.kkkev.jjidea.actions.JujutsuDataKeys.LogNeighbours
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Tests for [moveTarget], the pure logic behind "Move Up"/"Move Down" (jj-idea-owje, GitHub #93):
 * whether the selected commit can swap with its single child (Up) or single parent (Down) in
 * the commit graph, and what `RebaseDestinationMode` that swap requires. Same-repo safety for
 * a multi-root log is tested at the neighbour-resolution level ([JujutsuLogTableNeighboursTest][
 * `in`.kkkev.jjidea.ui.log.JujutsuLogTableNeighboursTest] and [ChildrenIndexTest][
 * `in`.kkkev.jjidea.ui.log.ChildrenIndexTest]'s multi-repo groups), not here - by the time a
 * [LogNeighbours] reaches [moveTarget], its entries are structurally guaranteed same-repo.
 */
class MoveChangeActionTest {
    private val repo = mockk<JujutsuRepository>()
    private var nextId = 0

    private fun entry(immutable: Boolean = false) = LogEntry(
        repo = repo,
        id = ChangeId("change${nextId++}"),
        commitId = CommitId("0".repeat(40)),
        underlyingDescription = "Test commit",
        bookmarks = emptyList(),
        parentIds = emptyList(),
        isWorkingCopy = false,
        hasConflict = false,
        isEmpty = true,
        authorTimestamp = null,
        committerTimestamp = null,
        author = null,
        committer = null,
        immutable = immutable
    )

    @Test
    fun `move up targets the single child with INSERT_AFTER`() {
        val selected = entry()
        val child = entry()
        val neighbours = LogNeighbours(singleChild = child, singleParent = null)

        val target = moveTarget(selected, neighbours, MoveDirection.UP)

        target.shouldNotBeNull()
        target.entry shouldBe selected
        target.neighbour shouldBe child
        target.direction shouldBe MoveDirection.UP
    }

    @Test
    fun `move down targets the single parent with INSERT_BEFORE`() {
        val selected = entry()
        val parent = entry()
        val neighbours = LogNeighbours(singleChild = null, singleParent = parent)

        val target = moveTarget(selected, neighbours, MoveDirection.DOWN)

        target.shouldNotBeNull()
        target.entry shouldBe selected
        target.neighbour shouldBe parent
        target.direction shouldBe MoveDirection.DOWN
    }

    @Test
    fun `no selection is not movable`() {
        moveTarget(null, LogNeighbours(entry(), entry()), MoveDirection.UP).shouldBeNull()
    }

    @Test
    fun `no neighbours is not movable`() {
        moveTarget(entry(), null, MoveDirection.UP).shouldBeNull()
    }

    @Test
    fun `no child (zero or ambiguous) cannot move up`() {
        moveTarget(entry(), LogNeighbours(singleChild = null, singleParent = entry()), MoveDirection.UP).shouldBeNull()
    }

    @Test
    fun `no parent (zero or ambiguous) cannot move down`() {
        moveTarget(
            entry(),
            LogNeighbours(singleChild = entry(), singleParent = null),
            MoveDirection.DOWN
        ).shouldBeNull()
    }

    @Test
    fun `immutable selection cannot move`() {
        val selected = entry(immutable = true)
        val neighbours = LogNeighbours(singleChild = entry(), singleParent = entry())

        moveTarget(selected, neighbours, MoveDirection.UP).shouldBeNull()
        moveTarget(selected, neighbours, MoveDirection.DOWN).shouldBeNull()
    }

    @Test
    fun `immutable neighbour cannot be swapped with`() {
        val selected = entry()
        val immutableNeighbour = entry(immutable = true)

        moveTarget(
            selected,
            LogNeighbours(singleChild = immutableNeighbour, singleParent = null),
            MoveDirection.UP
        ).shouldBeNull()
        moveTarget(
            selected,
            LogNeighbours(singleChild = null, singleParent = immutableNeighbour),
            MoveDirection.DOWN
        ).shouldBeNull()
    }
}
