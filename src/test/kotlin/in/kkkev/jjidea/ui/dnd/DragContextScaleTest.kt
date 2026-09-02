package `in`.kkkev.jjidea.ui.dnd

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Operation-count scale test for [DragContext] (the `drag-guard` hot path), per contributing.md
 * § Performance & Scale and design section 9: `DnDTargetChecker.update` fires on **every
 * mouse-move event during a drag**, so [DragContext.rejectionReason] must be O(1) per call once
 * [DragContext.forDrag] has built its guard state - never re-scanning the loaded log per pixel of
 * pointer movement. A correctness assertion alone can't catch a regression that adds an
 * accidental full scan inside [DragContext.rejectionReason]; only a work-count assertion can, so
 * this counts *passes over the entry list* via a wrapper that tracks [Iterable.iterator] calls -
 * the same idiom [in.kkkev.jjidea.ui.log.graph.GraphLayoutScaleTest] uses via an internal counter,
 * adapted here since [DragContext] has no counter of its own to expose.
 */
class DragContextScaleTest {
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
    fun `forDrag makes a bounded number of passes over a 100k-entry log, not one per entry`() {
        val n = 100_000
        val entries = (0 until n).map { entry("e$it") }
        val counting = CountingEntryList(entries)
        val payload = DragPayload.Commit(listOf(entries.first()))

        DragContext.forDrag(counting, payload)

        // forDrag does a small constant number of passes (excludedDestinationIds once,
        // invalidDestinationIds twice, the immutable-source check once) - nowhere near
        // proportional to n. 20 is a generous ceiling that still catches an accidental
        // per-destination or per-mouse-move rescan.
        counting.passCount shouldBeLessThan 20
    }

    @Test
    fun `rejectionReason makes zero further passes over the entry list, regardless of call count`() {
        val n = 100_000
        val entries = (0 until n).map { entry("e$it") }
        val counting = CountingEntryList(entries)
        val payload = DragPayload.Commit(listOf(entries.first()))
        val context = DragContext.forDrag(counting, payload)
        val passesAfterForDrag = counting.passCount

        // Simulate a long drag: many mouse-move-driven checks against many different rows.
        repeat(1000) { i ->
            context.rejectionReason(DropTarget.CommitRow(entries[i % n]), copy = false)
        }

        counting.passCount shouldBe passesAfterForDrag
    }
}
