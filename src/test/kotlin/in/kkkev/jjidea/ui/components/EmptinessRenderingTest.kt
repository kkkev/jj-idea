package `in`.kkkev.jjidea.ui.components

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.Emptiness
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.MergeEmptiness
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/** jj-idea-2570.8: a deferred immutable merge shows "calculating" until its emptiness has been fetched. */
class EmptinessRenderingTest {
    private val mergeEmptiness = mockk<MergeEmptiness>()
    private val repo = mockk<JujutsuRepository>(relaxed = true).also {
        every { it.mergeEmptiness } returns
            mergeEmptiness
    }

    private val deferredMerge = LogEntry(
        repo = repo,
        id = ChangeId("m", "m"),
        commitId = CommitId("cm"),
        underlyingDescription = "Merge trunk",
        immutable = true,
        parentIds = listOf(ChangeId("p", "p"), ChangeId("q", "q")),
        emptyDeferred = true
    )

    private fun render() = htmlString { appendDescriptionAndEmptyIndicator(deferredMerge) }

    @Test
    fun `pending shows the calculating marker`() {
        every { mergeEmptiness.peek(deferredMerge) } returns Emptiness.PENDING

        render() shouldContain "(…)"
    }

    @Test
    fun `a fetched empty merge shows the empty marker`() {
        every { mergeEmptiness.peek(deferredMerge) } returns Emptiness.EMPTY

        render() shouldContain "(empty)"
    }

    @Test
    fun `a fetched non-empty merge shows no marker`() {
        every { mergeEmptiness.peek(deferredMerge) } returns Emptiness.NOT_EMPTY

        render().let {
            it shouldNotContain "(empty)"
            it shouldNotContain "(…)"
        }
    }
}
