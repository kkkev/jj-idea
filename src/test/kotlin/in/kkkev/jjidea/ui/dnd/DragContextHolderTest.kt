package `in`.kkkev.jjidea.ui.dnd

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * [DragContextHolder] memoises the one [DragContext] a gesture needs, rebuilt lazily from
 * whichever payload the live `DnDEvent` carries rather than one primed only by the log table's own
 * bean provider (jj-idea-yvry's fix for a cross-component drag - see [DragContextHolder]'s KDoc).
 */
class DragContextHolderTest {
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    private fun entry(id: String) = LogEntry(
        repo = repo,
        id = ChangeId(id, id, null),
        commitId = CommitId("commit-$id"),
        underlyingDescription = "desc $id"
    )

    private val a = entry("aaaaaaaa")
    private val entries = listOf(a)

    @Test
    fun `the same payload instance returns the memoised context, not a rebuilt one`() {
        val holder = DragContextHolder()
        val payload = DragPayload.Commit(listOf(a))
        var buildCount = 0

        val first = holder.forPayload(payload) {
            buildCount++
            entries
        }
        val second = holder.forPayload(payload) {
            buildCount++
            entries
        }

        second shouldBeSameInstanceAs first
        buildCount shouldBe 1
    }

    @Test
    fun `a different payload instance rebuilds the context`() {
        val holder = DragContextHolder()
        val first = holder.forPayload(DragPayload.Commit(listOf(a))) { entries }
        val second = holder.forPayload(DragPayload.Commit(listOf(a))) { entries }

        first shouldNotBe second
    }

    @Test
    fun `reset forces the next call to rebuild`() {
        val holder = DragContextHolder()
        val payload = DragPayload.Commit(listOf(a))
        var buildCount = 0

        holder.forPayload(payload) {
            buildCount++
            entries
        }
        holder.reset()
        holder.forPayload(payload) {
            buildCount++
            entries
        }

        buildCount shouldBe 2
    }
}
