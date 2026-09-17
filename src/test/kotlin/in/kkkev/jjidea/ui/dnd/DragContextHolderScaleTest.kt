package `in`.kkkev.jjidea.ui.dnd

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.comparables.shouldBeLessThan
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Operation-count scale test for [DragContextHolder], per contributing.md § Performance & Scale
 * and design section 9: [in.kkkev.jjidea.ui.log.JujutsuLogTableDnD]'s target checker calls
 * [DragContextHolder.forPayload] on **every mouse-move** of a drag, so it must resolve to the
 * memoised [DragContext] instead of rescanning the loaded log once per pixel of pointer movement -
 * the same invariant [DragContextScaleTest] already asserts for [DragContext.forDrag] itself, one
 * level up the call chain this holder sits in front of.
 */
class DragContextHolderScaleTest {
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    private fun entry(id: String) = LogEntry(
        repo = repo,
        id = ChangeId(id, id, null),
        commitId = CommitId("commit-$id"),
        underlyingDescription = "desc $id"
    )

    /** Tracks how many full passes ([iterator] calls) callers make over [delegate]. */
    private class CountingEntryList(private val delegate: List<LogEntry>) : List<LogEntry> by delegate {
        var passCount = 0
            private set

        override fun iterator(): Iterator<LogEntry> {
            passCount++
            return delegate.iterator()
        }
    }

    @Test
    fun `200 mouse-move resolutions of one gesture over a 100k-entry log build the guard state once`() {
        val n = 100_000
        val entries = (0 until n).map { entry("e$it") }
        val counting = CountingEntryList(entries)
        val payload = DragPayload.Commit(listOf(entries.first()))
        val holder = DragContextHolder()

        repeat(200) { holder.forPayload(payload) { counting } }

        // forDrag itself makes a small constant number of passes (DragContextScaleTest already
        // bounds that at < 20); the holder's own job is making sure 200 simulated mouse-move
        // ticks over the SAME payload don't multiply that by 200. 20 stays a generous ceiling for
        // the one build this gesture should ever trigger.
        counting.passCount shouldBeLessThan 20
    }
}
