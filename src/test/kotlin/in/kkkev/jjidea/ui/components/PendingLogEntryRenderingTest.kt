package `in`.kkkev.jjidea.ui.components

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Regression tests for the crash fixed in jj-idea-0gtl: a [LogEntry.pending] preview row (e.g.
 * [in.kkkev.jjidea.ui.newchange.NewChangeDialog]'s "the change about to be created") was rendered
 * through the same path as a real commit, which unconditionally builds a `jjc://` navigation link
 * out of the entry's id - and a placeholder id containing characters like `<`/`>` (or one that
 * happens to collide with a real id, like jj's root's `zzzz...z`) broke that `URI` construction
 * mid-paint.
 *
 * [appendSummaryAndStatuses] (used for the preview table's tooltip) must never attempt that link
 * for a pending entry, regardless of what its placeholder id looks like.
 */
class PendingLogEntryRenderingTest {
    // A relaxed mock's JujutsuRepository is never actually touched for a pending entry - see the
    // assertions below - but LogEntry always requires one.
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    private fun pendingEntry(
        id: String,
        description: String = "In progress",
        isEmpty: Boolean = false,
        isWorkingCopy: Boolean = false
    ) = LogEntry(
        repo = repo,
        id = ChangeId(id, id),
        commitId = CommitId(id),
        underlyingDescription = description,
        pending = true,
        isEmpty = isEmpty,
        isWorkingCopy = isWorkingCopy
    )

    @Test
    fun `a pending entry with an id containing illegal URI characters doesn't throw`() {
        val entry = pendingEntry("<pending>")

        htmlString { appendSummaryAndStatuses(entry) }
    }

    @Test
    fun `a pending entry sharing jj's real root change id still doesn't throw or link`() {
        val entry = pendingEntry("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz")

        val html = htmlString { appendSummaryAndStatuses(entry) }

        html shouldNotContain "jjc://"
    }

    @Test
    fun `a pending entry never renders a jjc navigation link`() {
        val entry = pendingEntry("new")

        val html = htmlString { appendSummaryAndStatuses(entry) }

        html shouldNotContain "jjc://"
    }

    @Test
    fun `a pending entry's description is still shown`() {
        val entry = pendingEntry("new", description = "Insert a hotfix here")

        val html = htmlString { appendSummaryAndStatuses(entry) }

        html shouldContain "Insert a hotfix here"
    }

    @Test
    fun `an empty pending entry shows the empty indicator, like a real empty entry would`() {
        val entry = pendingEntry("new", isEmpty = true)

        val html = htmlString { appendSummaryAndStatuses(entry) }

        html shouldContain "(empty)"
    }

    @Test
    fun `appendPendingSummary is exactly what appendSummaryAndStatuses delegates to for a pending entry`() {
        val entry = pendingEntry("new")

        val delegated = htmlString { appendSummaryAndStatuses(entry) }
        val direct = htmlString { appendPendingSummary(entry) }

        delegated shouldBe direct
    }
}
