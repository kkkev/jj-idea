package `in`.kkkev.jjidea.ui.oplog

import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult.Success.Irreversible.Reason.READ_ONLY
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.OperationEntry
import `in`.kkkev.jjidea.jj.OperationId
import `in`.kkkev.jjidea.ui.log.RootFilterSelection
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

private const val NUL = "\u0000"

/** Pure merge rules and operation counts for the multi-repo Operations tab (jj-idea-qz9d). */
class OperationRowsTest {
    private val a = mockk<JujutsuRepository>(name = "a")
    private val b = mockk<JujutsuRepository>(name = "b")

    private fun entry(id: String, seconds: Long, snapshot: Boolean = false) = OperationEntry(
        OperationId(id),
        emptyList(),
        "op $id",
        Instant.fromEpochSeconds(seconds),
        "u",
        snapshot,
        false,
        ""
    )

    private fun List<OperationRow>.ids() = map { it.entry.id.toString() }

    private val perRepo = mapOf(
        a to listOf(entry("a3", 30), entry("a2", 20, snapshot = true), entry("a1", 10)),
        b to listOf(entry("b2", 25), entry("b1", 15))
    )

    @Test
    fun `selected repos interleave newest first and keep their repo`() {
        val rows = mergeOperations(perRepo, RootFilterSelection(), showSnapshots = true)

        rows.ids() shouldContainExactly listOf("a3", "b2", "a2", "b1", "a1")
        rows.first { it.entry.id.toString() == "b2" }.repo shouldBe b
    }

    @Test
    fun `snapshots are filtered per repo`() {
        mergeOperations(perRepo, RootFilterSelection(), showSnapshots = false).ids() shouldContainExactly
            listOf("a3", "b2", "b1", "a1")
    }

    @Test
    fun `include is an allowlist and exclude a denylist`() {
        mergeOperations(perRepo, RootFilterSelection(included = setOf(b)), true).ids() shouldContainExactly
            listOf("b2", "b1")
        mergeOperations(perRepo, RootFilterSelection(excluded = setOf(b)), true).ids() shouldContainExactly
            listOf("a3", "a2", "a1")
    }

    @Test
    fun `equal times keep repo order then own order`() {
        val tied = mapOf(a to listOf(entry("a2", 5), entry("a1", 5)), b to listOf(entry("b1", 5)))

        mergeOperations(tied, RootFilterSelection(), true).ids() shouldContainExactly listOf("a2", "a1", "b1")
    }

    private fun record(id: String) =
        listOf(id, "", "op $id", "1788000000", "kevin", "0", "0", "args").joinToString(NUL, postfix = NUL)

    private fun ok(stdout: String) = CommandExecutor.CommandResult.Success.Irreversible(stdout, "", READ_ONLY)

    @Test
    fun `operation counts scale with shown repos only`() {
        val execA = mockk<CommandExecutor>()
        val execB = mockk<CommandExecutor>()
        every { execA.opLog(1, any()) } returnsMany listOf(ok(record("a1")), ok(record("a1")), ok(record("a2")))
        every { execA.opLog(100, any()) } returnsMany
            listOf(ok(record("a1")), ok(record("a2") + record("a1")))
        every { execB.opLog(1, any()) } returns ok(record("b1"))
        every { execB.opLog(100, any()) } returns ok(record("b1"))
        val loaders = mapOf(a to OperationLogLoader(execA), b to OperationLogLoader(execB))
        val repos = listOf(a, b)
        val all = RootFilterSelection()

        loadShown(repos, all, loaders::getValue) // first load: 2 probes + 2 full
        verify(exactly = 1) { execA.opLog(100, any()) }
        verify(exactly = 1) { execB.opLog(100, any()) }

        loadShown(repos, all, loaders::getValue) // unchanged: 2 probes only
        verify(exactly = 1) { execA.opLog(100, any()) }
        verify(exactly = 1) { execB.opLog(100, any()) }
        verify(exactly = 2) { execB.opLog(1, any()) }

        loadShown(repos, all, loaders::getValue) // only A's head moved: one more full query, for A
        verify(exactly = 2) { execA.opLog(100, any()) }
        verify(exactly = 1) { execB.opLog(100, any()) }

        loadShown(repos, RootFilterSelection(excluded = setOf(b)), loaders::getValue) // B excluded: 0 calls to B
        verify(exactly = 3) { execB.opLog(1, any()) }
    }
}
